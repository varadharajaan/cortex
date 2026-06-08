package io.cortex.gateway.interceptor;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.exception.RateLimitedException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC {@link HandlerInterceptor} that enforces per-feature
 * Bucket4j sub-buckets declared via
 * {@link RateLimitFeature @RateLimitFeature} (P3.4 / ADR-0021).
 *
 * <p>Skips silently when the handler is not a {@link HandlerMethod} or
 * carries no annotation. Otherwise:</p>
 * <ol>
 *   <li>Resolves {@link RateLimitFeature#capacity()} /
 *       {@link RateLimitFeature#refill()} /
 *       {@link RateLimitFeature#errorCode()} via {@link Environment}
 *       (supports literal values or {@code ${...}} placeholders).</li>
 *   <li>Computes the bucket key as
 *       {@code <keyPrefix><name>:user:<principal>} for authenticated
 *       callers, or {@code <keyPrefix><name>:ip:<remote-addr>} for
 *       anonymous callers (e.g. failed-login attempts).</li>
 *   <li>Atomically consumes one token via the shared Bucket4j
 *       {@link ProxyManager}; throws
 *       {@link RateLimitedException} (carrying the resolved
 *       {@link ErrorCodes}) when the bucket is empty so the
 *       {@code @RestControllerAdvice} renders the RFC 7807 body and
 *       sets {@code Retry-After}.</li>
 * </ol>
 *
 * <p>{@link BucketConfiguration} instances are cached per
 * {@link RateLimitFeature#name()} so the placeholder + parse cost is
 * paid once per feature.</p>
 *
 * <p>{@code @ConditionalOnProperty} matches the
 * {@code RateLimitFilter} gate so disabling rate-limit at the property
 * layer disables BOTH the global filter AND every per-feature
 * sub-bucket in one switch (consistent with LD44).</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cortex.gateway.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitFeatureInterceptor implements HandlerInterceptor {

    /** Shared Bucket4j proxy manager (Redis-backed via Lettuce). */
    private final ProxyManager<String> proxyManager;

    /** Spring {@link Environment} for resolving {@code ${...}} placeholders in annotation members. */
    private final Environment environment;

    /** Per-feature {@link BucketConfiguration} cache keyed by {@link RateLimitFeature#name()}. */
    private final ConcurrentHashMap<String, BucketConfiguration> configByName = new ConcurrentHashMap<>();

    /**
     * Constructor injection of the rate-limit collaborators.
     *
     * @param proxyManager shared Bucket4j proxy manager
     * @param environment  Spring environment for placeholder resolution
     */
    public RateLimitFeatureInterceptor(
            final ProxyManager<String> proxyManager, final Environment environment) {
        this.proxyManager = proxyManager;
        this.environment = environment;
    }

    /**
     * Looks up the {@link RateLimitFeature @RateLimitFeature} on the
     * matched handler (or its declaring class), consumes one token from
     * the per-feature bucket, and throws
     * {@link RateLimitedException} if exhausted.
     *
     * @param request  inbound HTTP request
     * @param response outbound HTTP response (unused; headers are owned by {@code RateLimitFilter})
     * @param handler  matched MVC handler
     * @return {@code true} to continue the dispatcher chain; the
     *         method throws {@link RateLimitedException} on rejection
     *         (no return)
     * @throws RateLimitedException when the bucket is exhausted
     */
    @Override
    public boolean preHandle(
            final HttpServletRequest request, final HttpServletResponse response, final Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        final RateLimitFeature methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RateLimitFeature.class);
        final RateLimitFeature feature = methodAnnotation != null
                ? methodAnnotation
                : AnnotatedElementUtils.findMergedAnnotation(
                        handlerMethod.getBeanType(), RateLimitFeature.class);
        if (feature == null) {
            return true;
        }

        final long capacity = parseLongPlaceholder(feature.capacity(), feature.name(), "capacity");
        final ErrorCodes errorCode = parseErrorCode(feature.errorCode(), feature.name());
        final BucketConfiguration config = this.configByName.computeIfAbsent(
                feature.name(), name -> buildConfig(name, capacity, feature.refill()));

        final String resolvedPrefix = this.environment.resolvePlaceholders(feature.keyPrefix());
        final String key = resolvedPrefix + feature.name() + ":" + resolveCallerKey(request);
        final Bucket bucket = this.proxyManager.builder().build(key, () -> config);
        final ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            final long retryAfter = Math.max(
                    TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()), 1L);
            log.warn("@RateLimitFeature[{}] rejected key={} retryAfterSeconds={}",
                    feature.name(), key, retryAfter);
            throw new RateLimitedException(errorCode, capacity, 0L, retryAfter);
        }
        return true;
    }

    /**
     * Resolves a {@code String} placeholder via {@link Environment} and parses
     * it as a {@code long}.
     *
     * @param expression placeholder or literal text from the annotation
     * @param featureName feature name (used in error messages)
     * @param memberName  annotation member name (used in error messages)
     * @return parsed long value
     * @throws IllegalStateException when the resolved value is not a parsable long
     */
    private long parseLongPlaceholder(
            final String expression, final String featureName, final String memberName) {
        final String resolved = this.environment.resolveRequiredPlaceholders(expression);
        try {
            return Long.parseLong(resolved.trim());
        } catch (final NumberFormatException ex) {
            throw new IllegalStateException(
                    "@RateLimitFeature[" + featureName + "]." + memberName
                            + " did not resolve to a long: '" + resolved + "'",
                    ex);
        }
    }

    /**
     * Resolves and validates the {@link ErrorCodes} enum constant named by
     * {@link RateLimitFeature#errorCode()}.
     *
     * @param expression placeholder or literal text from the annotation
     * @param featureName feature name (used in error messages)
     * @return resolved {@link ErrorCodes} constant
     * @throws IllegalStateException when the resolved name is not a known {@link ErrorCodes} constant
     */
    private ErrorCodes parseErrorCode(final String expression, final String featureName) {
        final String resolved = this.environment.resolveRequiredPlaceholders(expression).trim();
        try {
            return ErrorCodes.valueOf(resolved);
        } catch (final IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "@RateLimitFeature[" + featureName + "].errorCode is not a known ErrorCodes constant: '"
                            + resolved + "'",
                    ex);
        }
    }

    /**
     * Builds a {@link BucketConfiguration} with a single greedy-refill
     * bandwidth matching the annotation members.
     *
     * @param featureName       feature name (used in error messages)
     * @param capacity          parsed bucket capacity (tokens per window)
     * @param refillExpression  placeholder or literal ISO-8601 duration
     * @return immutable bucket configuration
     * @throws IllegalStateException when the resolved refill text is not a parsable {@link Duration}
     */
    private BucketConfiguration buildConfig(
            final String featureName, final long capacity, final String refillExpression) {
        final String resolved = this.environment.resolveRequiredPlaceholders(refillExpression).trim();
        final Duration refill;
        try {
            refill = Duration.parse(resolved);
        } catch (final java.time.format.DateTimeParseException ex) {
            throw new IllegalStateException(
                    "@RateLimitFeature[" + featureName + "].refill did not resolve to an ISO-8601 Duration: '"
                            + resolved + "'",
                    ex);
        }
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, refill)
                        .build())
                .build();
    }

    /**
     * Builds the principal-or-ip suffix for the bucket key. Authenticated
     * callers are keyed by Spring Security principal name; anonymous
     * callers fall back to {@code X-Forwarded-For} (first hop) or the
     * raw remote address. Matches the
     * {@link io.cortex.gateway.filter.RateLimitFilter} convention so the
     * global and per-feature buckets share their notion of "caller".
     *
     * @param request inbound HTTP request
     * @return non-blank caller key
     */
    private static String resolveCallerKey(final HttpServletRequest request) {
        final org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext()
                        .getAuthentication();
        final boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getName());
        if (authenticated) {
            return "user:" + auth.getName();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * Best-effort client IP resolution honouring {@code X-Forwarded-For}
     * (first hop only).
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
