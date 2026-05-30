package io.cortex.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.gateway.config.RateLimitProperties;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.exception.RateLimitedException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Unit tests for {@link RateLimitFilter} (B5.2, P3.2). Mocks the
 * Bucket4j {@link ProxyManager} and {@link BucketProxy} so the filter
 * logic is exercised without a live Redis; live-Redis behaviour is
 * covered by {@code scripts/smoke-p3-2.ps1}.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    /** Default capacity used by every test in this class. */
    private static final long CAPACITY = 10L;

    /** Default anonymous capacity used by every test in this class. */
    private static final long ANON_CAPACITY = 5L;

    /** Default refill period used by every test in this class. */
    private static final Duration REFILL = Duration.ofMinutes(1);

    /** Typed configuration shared across tests. */
    private RateLimitProperties properties;

    /** Authenticated bucket configuration shared across tests. */
    private BucketConfiguration authenticatedConfig;

    /** Anonymous bucket configuration shared across tests. */
    private BucketConfiguration anonymousConfig;

    /** Mocked proxy manager. */
    private ProxyManager<String> proxyManager;

    /** Mocked exception resolver chain. */
    private HandlerExceptionResolver resolver;

    /** Filter under test. */
    private RateLimitFilter filter;

    /**
     * Fresh wiring for every test: real {@link RateLimitProperties}
     * and {@link BucketConfiguration}, mocked {@link ProxyManager}
     * (so we can stub per-key bucket outcomes).
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        this.properties = new RateLimitProperties(
                true, CAPACITY, REFILL, ANON_CAPACITY,
                "redis://localhost:6379/0",
                "cortex:rl:",
                List.of("/actuator/**"));
        this.authenticatedConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(CAPACITY).refillGreedy(CAPACITY, REFILL).build())
                .build();
        this.anonymousConfig = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder().capacity(ANON_CAPACITY).refillGreedy(ANON_CAPACITY, REFILL).build())
                .build();
        this.proxyManager = (ProxyManager<String>) mock(ProxyManager.class);
        this.resolver = mock(HandlerExceptionResolver.class);
        this.filter = new RateLimitFilter(
                this.proxyManager,
                this.authenticatedConfig,
                this.anonymousConfig,
                REFILL,
                this.properties,
                this.resolver);
    }

    /**
     * Clears the security context after every test so authenticated
     * fixtures do not leak across cases.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Verifies that a successful consumption sets the three
     * {@code X-RateLimit-*} headers and continues the chain.
     *
     * @throws ServletException on chain error (never thrown by the test fixture)
     * @throws IOException      on chain error (never thrown by the test fixture)
     */
    @Test
    void setsHeadersAndChainsWhenTokenAvailable() throws ServletException, IOException {
        stubBucket("cortex:rl:user:alice", probe(true, 9L, 0L));
        authenticate("alice");
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/echo/ping");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicInteger chainCalls = new AtomicInteger();
        final FilterChain chain = (req, res) -> chainCalls.incrementAndGet();

        this.filter.doFilter(request, response, chain);

        assertThat(chainCalls).hasValue(1);
        assertThat(response.getHeader(HeaderNames.X_RATELIMIT_LIMIT)).isEqualTo(Long.toString(CAPACITY));
        assertThat(response.getHeader(HeaderNames.X_RATELIMIT_REMAINING)).isEqualTo("9");
        // Reset clamps to at least the refill period in seconds.
        assertThat(response.getHeader(HeaderNames.X_RATELIMIT_RESET))
                .isEqualTo(Long.toString(REFILL.toSeconds()));
        verify(this.resolver, never()).resolveException(any(), any(), any(), any());
    }

    /**
     * Verifies that a rejected consumption routes the
     * {@link RateLimitedException} through the resolver and does NOT
     * call the downstream chain.
     *
     * @throws ServletException on chain error (never thrown by the test fixture)
     * @throws IOException      on chain error (never thrown by the test fixture)
     */
    @Test
    void delegatesToResolverWhenTokenRejected() throws ServletException, IOException {
        final long waitNanos = TimeUnit.SECONDS.toNanos(42L);
        stubBucket("cortex:rl:user:bob", probe(false, 0L, waitNanos));
        authenticate("bob");
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/echo/ping");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicInteger chainCalls = new AtomicInteger();
        final FilterChain chain = (req, res) -> chainCalls.incrementAndGet();
        // Resolver MUST claim the exception (return a non-null ModelAndView)
        // so the filter does not rethrow it.
        when(this.resolver.resolveException(any(), any(), any(), any(RateLimitedException.class)))
                .thenReturn(new org.springframework.web.servlet.ModelAndView());

        this.filter.doFilter(request, response, chain);

        assertThat(chainCalls).hasValue(0);
        verify(this.resolver).resolveException(
                eq(request), eq(response), any(), any(RateLimitedException.class));
        assertThat(response.getHeader(HeaderNames.X_RATELIMIT_REMAINING)).isEqualTo("0");
    }

    /**
     * Verifies that when the resolver chain does NOT claim the
     * exception (returns null), the filter rethrows so the servlet
     * container surfaces a 500 rather than a silent skip. This guards
     * against a misconfigured handler chain.
     *
     * @throws ServletException on chain error (never thrown by the test fixture)
     * @throws IOException      on chain error (never thrown by the test fixture)
     */
    @Test
    void rethrowsWhenResolverDoesNotClaimException() throws ServletException, IOException {
        stubBucket("cortex:rl:user:carol", probe(false, 0L, TimeUnit.SECONDS.toNanos(5L)));
        authenticate("carol");
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/echo/ping");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        when(this.resolver.resolveException(any(), any(), any(), any(RateLimitedException.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> this.filter.doFilter(request, response, (req, res) -> { }))
                .isInstanceOf(RateLimitedException.class);
    }

    /**
     * Verifies that anonymous traffic is keyed by remote IP and uses
     * the smaller anonymous bucket configuration.
     *
     * @throws ServletException on chain error (never thrown by the test fixture)
     * @throws IOException      on chain error (never thrown by the test fixture)
     */
    @Test
    void anonymousTrafficKeyedByIp() throws ServletException, IOException {
        stubBucket("cortex:rl:ip:203.0.113.5", probe(true, 4L, 0L));
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.setRemoteAddr("203.0.113.5");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        this.filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(HeaderNames.X_RATELIMIT_LIMIT))
                .isEqualTo(Long.toString(ANON_CAPACITY));
        assertThat(response.getHeader(HeaderNames.X_RATELIMIT_REMAINING)).isEqualTo("4");
    }

    /**
     * Verifies that the {@code X-Forwarded-For} header (left-most
     * value) takes precedence over {@link MockHttpServletRequest#setRemoteAddr}.
     *
     * @throws ServletException on chain error (never thrown by the test fixture)
     * @throws IOException      on chain error (never thrown by the test fixture)
     */
    @Test
    void anonymousTrafficHonoursXForwardedFor() throws ServletException, IOException {
        stubBucket("cortex:rl:ip:198.51.100.7", probe(true, 4L, 0L));
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/health");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.1");
        final MockHttpServletResponse response = new MockHttpServletResponse();

        this.filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(HeaderNames.X_RATELIMIT_REMAINING)).isEqualTo("4");
    }

    /**
     * Verifies that requests to {@link RateLimitProperties#excludedPaths()}
     * bypass the filter entirely - no bucket lookup, no headers, no
     * resolver interaction.
     *
     * @throws ServletException on chain error (never thrown by the test fixture)
     * @throws IOException      on chain error (never thrown by the test fixture)
     */
    @Test
    void excludedPathsSkipTheFilter() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicInteger chainCalls = new AtomicInteger();

        this.filter.doFilter(request, response, (req, res) -> chainCalls.incrementAndGet());

        assertThat(chainCalls).hasValue(1);
        assertThat(response.getHeader(HeaderNames.X_RATELIMIT_LIMIT)).isNull();
        verify(this.proxyManager, never()).builder();
        verify(this.resolver, never()).resolveException(any(), any(), any(), any());
    }

    /**
     * Builds a {@link ConsumptionProbe} stub with the requested fields.
     *
     * @param consumed  what {@link ConsumptionProbe#isConsumed()} returns
     * @param remaining tokens left after the attempted consumption
     * @param waitNanos nanoseconds until the next token refills
     * @return a fully stubbed consumption probe
     */
    private static ConsumptionProbe probe(final boolean consumed, final long remaining, final long waitNanos) {
        final ConsumptionProbe p = mock(ConsumptionProbe.class);
        lenient().when(p.isConsumed()).thenReturn(consumed);
        lenient().when(p.getRemainingTokens()).thenReturn(remaining);
        lenient().when(p.getNanosToWaitForRefill()).thenReturn(waitNanos);
        return p;
    }

    /**
     * Stubs the proxy manager so that {@code build(key, ...)} returns a
     * bucket whose {@code tryConsumeAndReturnRemaining(1)} yields
     * {@code probe}.
     *
     * @param key   expected bucket key
     * @param probe probe returned by the bucket's consume call
     */
    @SuppressWarnings("unchecked")
    private void stubBucket(final String key, final ConsumptionProbe probe) {
        final BucketProxy bucket = mock(BucketProxy.class);
        when(bucket.tryConsumeAndReturnRemaining(1L)).thenReturn(probe);
        final RemoteBucketBuilder<String> builder = mock(RemoteBucketBuilder.class);
        when(builder.build(eq(key), any(Supplier.class))).thenReturn(bucket);
        when(this.proxyManager.builder()).thenReturn(builder);
    }

    /**
     * Populates the {@link SecurityContextHolder} with a USER-role principal.
     *
     * @param name principal name to authenticate
     */
    private static void authenticate(final String name) {
        final UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                name, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
