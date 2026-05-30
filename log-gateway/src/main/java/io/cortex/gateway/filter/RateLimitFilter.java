package io.cortex.gateway.filter;

import io.cortex.gateway.config.RateLimitProperties;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.exception.RateLimitedException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Per-request distributed rate-limit filter (B5.1, B5.2, P3.2).
 *
 * <p>Runs immediately AFTER the Spring Security filter chain so the
 * authenticated principal (if any) is available in
 * {@link SecurityContextHolder}. Authenticated callers consume from a
 * per-principal bucket; anonymous callers consume from a per-IP
 * bucket. The bucket capacity, refill window, Redis URI and excluded
 * paths come from {@link RateLimitProperties}; the bucket store comes
 * from {@link io.cortex.gateway.config.RateLimitConfig}.</p>
 *
 * <p>{@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining}, and
 * {@code X-RateLimit-Reset} are set on EVERY response (B5.2). When the
 * caller is over the limit, the filter throws a
 * {@link RateLimitedException} which the global exception handler
 * maps to a {@code 429} RFC 7807 response with a matching
 * {@code Retry-After} header.</p>
 */
@Slf4j
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
@ConditionalOnProperty(prefix = "cortex.gateway.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitFilter extends OncePerRequestFilter {

    /** Bucket4j proxy manager backed by Redis. */
    private final ProxyManager<String> proxyManager;

    /** Per-principal bucket configuration (authenticated traffic). */
    private final BucketConfiguration authenticatedConfig;

    /** Per-IP bucket configuration (anonymous traffic). */
    private final BucketConfiguration anonymousConfig;

    /** Cached refill window for the {@code X-RateLimit-Reset} header. */
    private final Duration refillPeriod;

    /** Typed rate-limit configuration (key prefix, excluded paths). */
    private final RateLimitProperties properties;

    /**
     * Spring MVC's composite {@link HandlerExceptionResolver} chain.
     * Used to route filter-thrown exceptions through
     * {@code @RestControllerAdvice} so the rate-limit 429 body and
     * {@code Retry-After} header come from
     * {@link io.cortex.gateway.exception.GlobalExceptionHandler}
     * (filters run outside the dispatcher servlet so exceptions
     * thrown here do not normally reach {@code @ControllerAdvice}).
     */
    private final HandlerExceptionResolver resolver;

    /** Ant-style matcher for {@link RateLimitProperties#excludedPaths()}. */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Constructor injection of the rate-limit collaborators.
     *
     * @param proxyManager        Bucket4j proxy manager
     * @param authenticatedConfig bucket config for authenticated traffic
     * @param anonymousConfig     bucket config for anonymous traffic
     * @param refillPeriod        refill window for the reset header
     * @param properties          typed configuration
     * @param resolver            Spring MVC exception resolver chain
     */
    public RateLimitFilter(
            final ProxyManager<String> proxyManager,
            @Qualifier("authenticatedBucketConfiguration") final BucketConfiguration authenticatedConfig,
            @Qualifier("anonymousBucketConfiguration") final BucketConfiguration anonymousConfig,
            @Qualifier("rateLimitRefillPeriod") final Duration refillPeriod,
            final RateLimitProperties properties,
            @Qualifier("handlerExceptionResolver") final HandlerExceptionResolver resolver) {
        this.proxyManager = proxyManager;
        this.authenticatedConfig = authenticatedConfig;
        this.anonymousConfig = anonymousConfig;
        this.refillPeriod = refillPeriod;
        this.properties = properties;
        this.resolver = resolver;
    }

    /**
     * Resolves the bucket key + configuration for this request, consumes
     * one token, writes the {@code X-RateLimit-*} headers on the response,
     * and either continues the chain (200/4xx upstream) or throws
     * {@link RateLimitedException} (429).
     *
     * @param request  inbound HTTP request
     * @param response outbound HTTP response
     * @param chain    remaining filter chain
     * @throws ServletException re-thrown from the chain
     * @throws IOException      re-thrown from the chain
     */
    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain) throws ServletException, IOException {

        if (isExcluded(request)) {
            chain.doFilter(request, response);
            return;
        }

        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getName());
        final BucketConfiguration config = authenticated ? authenticatedConfig : anonymousConfig;
        final String bucketKey = resolveKey(request, auth, authenticated);

        final Bucket bucket = proxyManager.builder().build(bucketKey, () -> config);
        final ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        final long capacity = config.getBandwidths()[0].getCapacity();
        final long remaining = Math.max(probe.getRemainingTokens(), 0L);
        final long resetSeconds = Math.max(
                TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()),
                refillPeriod.toSeconds());

        response.setHeader(HeaderNames.X_RATELIMIT_LIMIT, Long.toString(capacity));
        response.setHeader(HeaderNames.X_RATELIMIT_REMAINING, Long.toString(remaining));
        response.setHeader(HeaderNames.X_RATELIMIT_RESET, Long.toString(resetSeconds));

        if (!probe.isConsumed()) {
            final long retryAfter = Math.max(
                    TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()), 1L);
            log.warn("rate-limit: rejected key={} retryAfterSeconds={}", bucketKey, retryAfter);
            final RateLimitedException ex = new RateLimitedException(capacity, 0L, retryAfter);
            // Route through Spring MVC's exception resolver chain so the
            // @RestControllerAdvice handler renders the RFC 7807 body and
            // adds the Retry-After header. resolveException returns a
            // non-null ModelAndView when at least one resolver matched.
            if (resolver.resolveException(request, response, null, ex) == null) {
                throw ex;
            }
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Whether the request path matches any excluded pattern (actuator,
     * Swagger UI, etc.). Matched requests skip both rate-limit
     * consumption and the {@code X-RateLimit-*} headers.
     *
     * @param request inbound HTTP request
     * @return {@code true} if the path is allow-listed and must bypass the filter
     */
    private boolean isExcluded(final HttpServletRequest request) {
        final String path = request.getRequestURI();
        final List<String> patterns = properties.excludedPaths();
        for (final String pattern : patterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the Redis key for this request. Authenticated requests are
     * keyed by principal name; anonymous requests by remote address.
     * The {@link RateLimitProperties#keyPrefix()} keeps the namespace
     * stable so multiple environments can share a Redis instance
     * without collisions.
     *
     * @param request       inbound HTTP request
     * @param auth          Spring Security authentication (may be null)
     * @param authenticated whether the caller is authenticated
     * @return non-blank Redis bucket key
     */
    private String resolveKey(
            final HttpServletRequest request, final Authentication auth, final boolean authenticated) {
        if (authenticated) {
            return properties.keyPrefix() + "user:" + auth.getName();
        }
        return properties.keyPrefix() + "ip:" + clientIp(request);
    }

    /**
     * Best-effort client IP resolution. Honours {@code X-Forwarded-For}
     * when present (the gateway sits behind ingress in non-local
     * environments). Falls back to {@link HttpServletRequest#getRemoteAddr()}.
     *
     * @param request inbound HTTP request
     * @return non-null client IP string
     */
    private static String clientIp(final HttpServletRequest request) {
        final String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            final int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }
}
