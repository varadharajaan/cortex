package io.cortex.gateway.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.NlQueryResponse;
import io.cortex.gateway.exception.RateLimitedException;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link RateLimitGraphQlInterceptor}
 * (P9.0a / ADR-0049 Amendment 1).
 *
 * <p>Mockito stubs Bucket4j's {@link ProxyManager} / {@link BucketProxy}
 * chain plus the Spring for GraphQL {@link WebGraphQlRequest} and
 * {@link WebGraphQlInterceptor.Chain} so the interceptor branching is
 * exercised without bootstrapping the full GraphQL runtime. Placeholder
 * resolution is exercised via {@link MockEnvironment}. The registry is
 * built off a stubbed {@link ApplicationContext} that returns the
 * inner {@link RegisteredController} bean.</p>
 *
 * <p>Async assertions block on the returned {@link Mono} directly
 * (rather than via {@code reactor.test.StepVerifier}) because
 * {@code reactor-test} is not on the {@code log-gateway} test
 * classpath and pulling it in solely for this slice would be
 * scope-creep; the interceptor never schedules onto another thread,
 * so {@code Mono#block()} is deterministic here.</p>
 */
class RateLimitGraphQlInterceptorTest {

    /** Mocked Bucket4j proxy manager. */
    private ProxyManager<String> proxyManager;

    /** Mocked builder returned by the proxy manager. */
    private RemoteBucketBuilder<String> bucketBuilder;

    /** Mocked bucket returned by the builder. */
    private BucketProxy bucket;

    /** Mock environment with placeholder values matching the annotation members. */
    private MockEnvironment environment;

    /** Mocked application context returning the {@link RegisteredController} bean. */
    private ApplicationContext applicationContext;

    /** Subject under test. */
    private RateLimitGraphQlInterceptor interceptor;

    /** Mocked Spring for GraphQL request shared by tests. */
    private WebGraphQlRequest request;

    /** Mocked interceptor chain returning a non-error response. */
    private WebGraphQlInterceptor.Chain chain;

    /** Mocked successful downstream response handed back by the chain. */
    private WebGraphQlResponse downstreamResponse;

    /**
     * Fresh mocks per test so state never leaks. Configures a default
     * proxy-manager -&gt; builder -&gt; bucket chain consumed in success cases
     * and a chain that returns a non-error {@link Mono}.
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.proxyManager = (ProxyManager<String>) mock(ProxyManager.class);
        this.bucketBuilder = (RemoteBucketBuilder<String>) mock(RemoteBucketBuilder.class);
        this.bucket = mock(BucketProxy.class);
        when(this.proxyManager.builder()).thenReturn(this.bucketBuilder);
        when(this.bucketBuilder.build(any(String.class), this.anyConfigSupplier())).thenReturn(this.bucket);

        this.environment = new MockEnvironment()
                .withProperty("cortex.gateway.nl-query.sub-bucket-capacity", "3")
                .withProperty("cortex.gateway.nl-query.sub-bucket-refill-period", "PT1M")
                .withProperty("cortex.gateway.nl-query.sub-bucket-key-prefix", "cortex:rl:nlq:");

        this.applicationContext = mock(ApplicationContext.class);
        when(this.applicationContext.getBeansWithAnnotation(eq(Controller.class)))
                .thenReturn(Map.of("registeredController", new RegisteredController()));

        this.interceptor = new RateLimitGraphQlInterceptor(
                this.proxyManager, this.environment, this.applicationContext);
        this.interceptor.buildRegistry();

        this.request = mock(WebGraphQlRequest.class);
        this.downstreamResponse = mock(WebGraphQlResponse.class);
        this.chain = req -> Mono.just(this.downstreamResponse);
        SecurityContextHolder.clearContext();
    }

    /** Always clear the {@link SecurityContextHolder} after each test so state never leaks. */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Documents that do NOT mention any registered field pass through
     * untouched; no bucket lookup happens.
     */
    @Test
    void unannotatedFieldSkipsBucketLookup() {
        when(this.request.getDocument()).thenReturn("{ unrelatedField { id } }");

        final WebGraphQlResponse response = this.interceptor.intercept(this.request, this.chain).block();

        assertThat(response).isSameAs(this.downstreamResponse);
        verify(this.proxyManager, never()).builder();
    }

    /**
     * Happy path: authenticated principal, registered field, token
     * consumed, chain proceeds. Bucket key matches the MVC interceptor
     * convention so REST and GraphQL share a single bucket.
     */
    @Test
    @SuppressWarnings("unchecked")
    void consumesTokenAndProceedsForAuthenticatedCaller() {
        authenticateAs("alice");
        whenConsumeReturns(true, 0L);
        when(this.request.getDocument())
                .thenReturn("{ nlToLogQL(prompt: \"errors today\") { logql confidence explanation } }");

        final WebGraphQlResponse response = this.interceptor.intercept(this.request, this.chain).block();

        assertThat(response).isSameAs(this.downstreamResponse);
        final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.bucketBuilder).build(keyCaptor.capture(), this.anyConfigSupplier());
        assertThat(keyCaptor.getValue()).isEqualTo("cortex:rl:nlq:nl-query:user:alice");
        verify(this.bucket, times(1)).tryConsumeAndReturnRemaining(1);
    }

    /**
     * Exhaustion path: {@link Mono} carries
     * {@link RateLimitedException} with the annotation's error code
     * so the existing
     * {@link io.cortex.gateway.exception.GlobalExceptionHandler}
     * maps it to a 429 RFC 7807 body identical to the REST surface.
     */
    @Test
    void exhaustionEmitsRateLimitedExceptionWithAnnotationErrorCode() {
        authenticateAs("alice");
        whenConsumeReturns(false, TimeUnit.SECONDS.toNanos(7));
        when(this.request.getDocument())
                .thenReturn("{ nlToLogQL(prompt: \"x\") { logql } }");

        final Mono<WebGraphQlResponse> result = this.interceptor.intercept(this.request, this.chain);

        assertThatThrownBy(result::block)
                .isInstanceOf(RateLimitedException.class)
                .satisfies(ex -> {
                    final RateLimitedException rle = (RateLimitedException) ex;
                    assertThat(rle.getErrorCode()).isEqualTo(ErrorCodes.NL_QUERY_RATE_LIMITED);
                    assertThat(rle.getCapacity()).isEqualTo(3L);
                    assertThat(rle.getRemaining()).isZero();
                    assertThat(rle.getRetryAfterSeconds()).isEqualTo(7L);
                });
    }

    /**
     * Anonymous callers (no Spring Security principal) are keyed by
     * {@code X-Forwarded-For} (first hop) so the bucket aligns with
     * the MVC convention from
     * {@link io.cortex.gateway.filter.RateLimitFilter}.
     */
    @Test
    void anonymousCallerIsKeyedByXForwardedFor() {
        whenConsumeReturns(true, 0L);
        when(this.request.getDocument())
                .thenReturn("{ nlToLogQL(prompt: \"x\") { logql } }");
        final HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "203.0.113.1, 10.0.0.1");
        when(this.request.getHeaders()).thenReturn(headers);

        final WebGraphQlResponse response = this.interceptor.intercept(this.request, this.chain).block();

        assertThat(response).isSameAs(this.downstreamResponse);
        final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.bucketBuilder).build(keyCaptor.capture(), this.anyConfigSupplier());
        assertThat(keyCaptor.getValue()).isEqualTo("cortex:rl:nlq:nl-query:ip:203.0.113.1");
    }

    /**
     * Anonymous callers without {@code X-Forwarded-For} fall back to
     * the stable {@code "unknown"} marker (Spring for GraphQL's
     * {@link WebGraphQlRequest} intentionally does not expose the raw
     * servlet remote address; documented in the interceptor Javadoc).
     */
    @Test
    void anonymousCallerWithoutForwardedHeaderFallsBackToUnknown() {
        whenConsumeReturns(true, 0L);
        when(this.request.getDocument())
                .thenReturn("{ nlToLogQL(prompt: \"x\") { logql } }");
        when(this.request.getHeaders()).thenReturn(new HttpHeaders());

        final WebGraphQlResponse response = this.interceptor.intercept(this.request, this.chain).block();

        assertThat(response).isSameAs(this.downstreamResponse);
        final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.bucketBuilder).build(keyCaptor.capture(), this.anyConfigSupplier());
        assertThat(keyCaptor.getValue()).isEqualTo("cortex:rl:nlq:nl-query:ip:unknown");
    }

    /**
     * Documents with mixed selections (one registered field + one
     * unrelated field) consume exactly one token from the registered
     * field's bucket. Unregistered fields are silently skipped.
     */
    @Test
    void mixedSelectionsOnlyConsumeRegisteredFields() {
        authenticateAs("alice");
        whenConsumeReturns(true, 0L);
        when(this.request.getDocument())
                .thenReturn("{ nlToLogQL(prompt: \"x\") { logql } unrelatedField { id } }");

        final WebGraphQlResponse response = this.interceptor.intercept(this.request, this.chain).block();

        assertThat(response).isSameAs(this.downstreamResponse);
        verify(this.bucketBuilder, times(1)).build(any(String.class), this.anyConfigSupplier());
    }

    /**
     * A registry built from an application context with no annotated
     * resolvers short-circuits to the chain without any document
     * parsing.
     */
    @Test
    void emptyRegistryShortCircuits() {
        when(this.applicationContext.getBeansWithAnnotation(eq(Controller.class)))
                .thenReturn(Map.of());
        final RateLimitGraphQlInterceptor empty = new RateLimitGraphQlInterceptor(
                this.proxyManager, this.environment, this.applicationContext);
        empty.buildRegistry();

        when(this.request.getDocument())
                .thenReturn("{ nlToLogQL(prompt: \"x\") { logql } }");

        final WebGraphQlResponse response = empty.intercept(this.request, this.chain).block();

        assertThat(response).isSameAs(this.downstreamResponse);
        verify(this.proxyManager, never()).builder();
    }

    /**
     * Pushes a {@link UsernamePasswordAuthenticationToken} into the
     * SecurityContext so the interceptor's principal-resolution branch
     * is exercised.
     *
     * @param username principal name to authenticate as
     */
    private void authenticateAs(final String username) {
        final UserDetails principal = User.withUsername(username).password("x").roles("USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "x", principal.getAuthorities()));
    }

    /**
     * Stubs the mocked bucket to return a {@link ConsumptionProbe} with the
     * given consumed/wait values for every
     * {@code tryConsumeAndReturnRemaining} invocation in the current test.
     *
     * @param consumed     value returned by {@link ConsumptionProbe#isConsumed()}
     * @param nanosToWait  value returned by {@link ConsumptionProbe#getNanosToWaitForRefill()}
     */
    private void whenConsumeReturns(final boolean consumed, final long nanosToWait) {
        final ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(consumed);
        when(probe.getNanosToWaitForRefill()).thenReturn(nanosToWait);
        when(this.bucket.tryConsumeAndReturnRemaining(anyLong())).thenReturn(probe);
    }

    /**
     * Generic-friendly Mockito matcher for
     * {@code Supplier<BucketConfiguration>} used by
     * {@link RemoteBucketBuilder#build(Object, Supplier)} -- factored
     * into a helper so the {@code -Werror} unchecked-warning
     * suppression only lives on one method, not at every call site.
     *
     * @return a Mockito {@code any()} matcher typed to the bucket
     *         configuration supplier
     */
    @SuppressWarnings("unchecked")
    private Supplier<BucketConfiguration> anyConfigSupplier() {
        return any(Supplier.class);
    }

    /**
     * Minimal {@link Controller} stand-in registered with the mocked
     * application context. The {@code nlToLogQL} method carries the
     * NL sub-bucket annotation so registry-building picks it up.
     */
    @Controller
    public static class RegisteredController {

        /**
         * Annotated query method matching
         * {@code NlQueryGraphQlController.nlToLogQL}'s posture.
         *
         * @param prompt unused; the test never invokes this method
         * @return a stub response so the method has a non-void return type
         */
        @QueryMapping
        @RateLimitFeature(
                name = "nl-query",
                capacity = "${cortex.gateway.nl-query.sub-bucket-capacity}",
                refill = "${cortex.gateway.nl-query.sub-bucket-refill-period}",
                errorCode = "NL_QUERY_RATE_LIMITED",
                keyPrefix = "${cortex.gateway.nl-query.sub-bucket-key-prefix}")
        public NlQueryResponse nlToLogQL(@Argument final String prompt) {
            return new NlQueryResponse("", 0.0, "");
        }
    }
}
