package io.cortex.gateway.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.exception.RateLimitedException;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.method.HandlerMethod;

/**
 * Unit tests for {@link RateLimitFeatureInterceptor} (P3.4 / ADR-0021).
 *
 * <p>Mockito stubs Bucket4j's {@link ProxyManager}/{@link BucketProxy}
 * so the interceptor's branching is exercised without a Redis fixture.
 * Placeholder resolution is exercised via {@link MockEnvironment}.</p>
 */
class RateLimitFeatureInterceptorTest {

    /** Mocked Bucket4j proxy manager. */
    private ProxyManager<String> proxyManager;

    /** Mocked builder returned by the proxy manager. */
    private RemoteBucketBuilder<String> bucketBuilder;

    /** Mocked bucket returned by the builder. */
    private BucketProxy bucket;

    /** Mock environment with placeholder values for the annotation members. */
    private MockEnvironment environment;

    /** Subject under test. */
    private RateLimitFeatureInterceptor interceptor;

    /** Mocked servlet request shared by tests. */
    private HttpServletRequest request;

    /** Mocked servlet response shared by tests. */
    private HttpServletResponse response;

    /**
     * Fresh mocks per test so state never leaks. Configures a default
     * proxy-manager -&gt; builder -&gt; bucket chain consumed in success cases.
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.proxyManager = (ProxyManager<String>) mock(ProxyManager.class);
        this.bucketBuilder = (RemoteBucketBuilder<String>) mock(RemoteBucketBuilder.class);
        this.bucket = mock(BucketProxy.class);
        when(this.proxyManager.builder()).thenReturn(this.bucketBuilder);
        when(this.bucketBuilder.build(any(String.class), any(Supplier.class))).thenReturn(this.bucket);

        this.environment = new MockEnvironment()
                .withProperty("cortex.gateway.nl-query.sub-bucket-capacity", "3")
                .withProperty("cortex.gateway.nl-query.sub-bucket-refill-period", "PT1M")
                .withProperty("cortex.gateway.security.login-rate-limit-capacity", "5")
                .withProperty("cortex.gateway.security.login-rate-limit-refill", "PT1M");
        this.interceptor = new RateLimitFeatureInterceptor(this.proxyManager, this.environment);

        this.request = mock(HttpServletRequest.class);
        this.response = mock(HttpServletResponse.class);
        SecurityContextHolder.clearContext();
    }

    /**
     * Handlers without a {@link RateLimitFeature} pass through untouched.
     *
     * @throws Exception if the interceptor preHandle throws (it should not here)
     */
    @Test
    void unannotatedHandlerSkipsBucketLookup() throws Exception {
        final HandlerMethod handlerMethod = handlerFor("plain");

        final boolean allowed = this.interceptor.preHandle(this.request, this.response, handlerMethod);

        assertThat(allowed).isTrue();
        verify(this.proxyManager, never()).builder();
    }

    /**
     * Non-{@link HandlerMethod} handlers (e.g. static resources) pass through too.
     *
     * @throws Exception if the interceptor preHandle throws (it should not here)
     */
    @Test
    void nonHandlerMethodSkips() throws Exception {
        final boolean allowed = this.interceptor.preHandle(this.request, this.response, new Object());
        assertThat(allowed).isTrue();
        verify(this.proxyManager, never()).builder();
    }

    /**
     * Happy path: authenticated principal, token consumed, request proceeds.
     *
     * @throws Exception if the interceptor preHandle throws (it should not here)
     */
    @Test
    @SuppressWarnings("unchecked")
    void consumesTokenAndProceedsForAuthenticatedCaller() throws Exception {
        authenticateAs("alice");
        whenConsumeReturns(true, 0L);

        final HandlerMethod handlerMethod = handlerFor("annotatedDefault");
        final boolean allowed = this.interceptor.preHandle(this.request, this.response, handlerMethod);

        assertThat(allowed).isTrue();
        final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.bucketBuilder).build(keyCaptor.capture(), any(Supplier.class));
        assertThat(keyCaptor.getValue()).isEqualTo("cortex:rl:feat:nl-query:user:alice");
        verify(this.bucket, times(1)).tryConsumeAndReturnRemaining(1);
    }

    /** Exhaustion path: throws {@link RateLimitedException} carrying the annotation's error code. */
    @Test
    void exhaustionThrowsRateLimitedExceptionWithAnnotationErrorCode() {
        authenticateAs("alice");
        whenConsumeReturns(false, TimeUnit.SECONDS.toNanos(7));

        final HandlerMethod handlerMethod = handlerFor("annotatedDefault");

        assertThatThrownBy(() -> this.interceptor.preHandle(this.request, this.response, handlerMethod))
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
     * Anonymous callers are keyed by {@code X-Forwarded-For} (first hop).
     *
     * @throws Exception if the interceptor preHandle throws (it should not here)
     */
    @Test
    @SuppressWarnings("unchecked")
    void anonymousCallerIsKeyedByXForwardedFor() throws Exception {
        when(this.request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 10.0.0.1");
        whenConsumeReturns(true, 0L);

        final HandlerMethod handlerMethod = handlerFor("annotatedLogin");
        this.interceptor.preHandle(this.request, this.response, handlerMethod);

        final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.bucketBuilder).build(keyCaptor.capture(), any(Supplier.class));
        assertThat(keyCaptor.getValue()).isEqualTo("cortex:rl:auth:auth-login:ip:203.0.113.1");
    }

    /**
     * Anonymous callers without XFF fall back to the raw remote address.
     *
     * @throws Exception if the interceptor preHandle throws (it should not here)
     */
    @Test
    @SuppressWarnings("unchecked")
    void anonymousCallerFallsBackToRemoteAddr() throws Exception {
        when(this.request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(this.request.getRemoteAddr()).thenReturn("198.51.100.7");
        whenConsumeReturns(true, 0L);

        final HandlerMethod handlerMethod = handlerFor("annotatedLogin");
        this.interceptor.preHandle(this.request, this.response, handlerMethod);

        final ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(this.bucketBuilder).build(keyCaptor.capture(), any(Supplier.class));
        assertThat(keyCaptor.getValue()).isEqualTo("cortex:rl:auth:auth-login:ip:198.51.100.7");
    }

    /**
     * Placeholder resolution + bucket-config cache: second invocation does not re-parse.
     *
     * @throws Exception if the interceptor preHandle throws (it should not here)
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bucketConfigurationCachedAcrossCalls() throws Exception {
        authenticateAs("alice");
        whenConsumeReturns(true, 0L);
        final HandlerMethod handlerMethod = handlerFor("annotatedDefault");

        this.interceptor.preHandle(this.request, this.response, handlerMethod);
        this.interceptor.preHandle(this.request, this.response, handlerMethod);

        final ArgumentCaptor rawCaptor = ArgumentCaptor.forClass(Supplier.class);
        final ArgumentCaptor<Supplier<BucketConfiguration>> supplierCaptor =
                (ArgumentCaptor<Supplier<BucketConfiguration>>) rawCaptor;
        verify(this.bucketBuilder, times(2)).build(any(String.class), supplierCaptor.capture());
        final BucketConfiguration cfg1 = supplierCaptor.getAllValues().get(0).get();
        final BucketConfiguration cfg2 = supplierCaptor.getAllValues().get(1).get();
        assertThat(cfg1).isSameAs(cfg2);
        assertThat(cfg1.getBandwidths()[0].getCapacity()).isEqualTo(3L);
    }

    /** {@code errorCode} that does not resolve to an enum constant fails fast. */
    @Test
    void unknownErrorCodeIsRejected() {
        authenticateAs("alice");
        final HandlerMethod handlerMethod = handlerFor("annotatedBadErrorCode");

        assertThatThrownBy(() -> this.interceptor.preHandle(this.request, this.response, handlerMethod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("errorCode is not a known ErrorCodes constant");
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
     * given consumed/wait values for every {@code tryConsumeAndReturnRemaining}
     * invocation in the current test.
     *
     * @param consumed value returned by {@link ConsumptionProbe#isConsumed()}
     * @param nanosToWait value returned by {@link ConsumptionProbe#getNanosToWaitForRefill()}
     */
    private void whenConsumeReturns(final boolean consumed, final long nanosToWait) {
        final ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(consumed);
        when(probe.getNanosToWaitForRefill()).thenReturn(nanosToWait);
        when(this.bucket.tryConsumeAndReturnRemaining(anyLong())).thenReturn(probe);
    }

    /**
     * Materialises a {@link HandlerMethod} that points at the named method
     * on {@link TestHandlers} so each test can pick the desired annotation set.
     *
     * @param methodName the name of the {@link TestHandlers} method to bind
     * @return a {@link HandlerMethod} bound to that method
     * @throws IllegalStateException if the method does not exist on {@link TestHandlers}
     */
    private static HandlerMethod handlerFor(final String methodName) {
        try {
            final Method method = TestHandlers.class.getMethod(methodName);
            return new HandlerMethod(new TestHandlers(), method);
        } catch (final NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Method holder used to materialise {@link HandlerMethod} instances with various annotations. */
    public static class TestHandlers {

        /** Plain handler with no annotation; interceptor MUST skip it. */
        public void plain() {
        }

        /** Default-prefix annotated handler with NL error code. */
        @RateLimitFeature(
                name = "nl-query",
                capacity = "${cortex.gateway.nl-query.sub-bucket-capacity}",
                refill = "${cortex.gateway.nl-query.sub-bucket-refill-period}",
                errorCode = "NL_QUERY_RATE_LIMITED")
        public void annotatedDefault() {
        }

        /** Login-style handler with a custom key prefix. */
        @RateLimitFeature(
                name = "auth-login",
                capacity = "${cortex.gateway.security.login-rate-limit-capacity}",
                refill = "${cortex.gateway.security.login-rate-limit-refill}",
                errorCode = "RATE_LIMITED",
                keyPrefix = "cortex:rl:auth:")
        public void annotatedLogin() {
        }

        /** Annotation with an invalid {@code errorCode} member; must fail fast. */
        @RateLimitFeature(
                name = "bad-error-code",
                capacity = "1",
                refill = "PT1M",
                errorCode = "DOES_NOT_EXIST")
        public void annotatedBadErrorCode() {
        }
    }

    /** Compile-time hint that the {@link Duration} class is in use via the placeholders. */
    @SuppressWarnings("unused")
    private static final Duration ENSURE_DURATION_IMPORT = Duration.ZERO;
}
