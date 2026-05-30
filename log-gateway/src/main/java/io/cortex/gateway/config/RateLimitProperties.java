package io.cortex.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the gateway rate-limit filter (B5, P3.2).
 *
 * <p>Bound to the {@code cortex.gateway.rate-limit} prefix. Authenticated
 * callers get a per-principal bucket sized at {@link #capacity()} tokens
 * that refill greedily over {@link #refillPeriod()}. Anonymous callers
 * (no Spring Security principal) fall back to a per-IP bucket sized at
 * {@link #anonymousCapacity()} tokens; this protects the public login
 * and health endpoints from credential-stuffing or probe storms.</p>
 *
 * <p>Requests whose path matches any entry in {@link #excludedPaths()}
 * bypass the filter entirely; actuator probes and Swagger UI default
 * to this list so health checks and dev tooling never spend tokens.</p>
 *
 * @param enabled          master switch (false in test classpath)
 * @param capacity         tokens per authenticated principal per refill window
 * @param refillPeriod     window over which {@link #capacity()} is restored
 * @param anonymousCapacity tokens per remote IP per refill window for anonymous traffic
 * @param redisUri         Lettuce-format Redis URI ({@code redis://host:port[/db]})
 * @param keyPrefix        Redis key namespace, defaults to {@code cortex:rl:}
 * @param excludedPaths    path patterns ({@link org.springframework.util.AntPathMatcher}) that skip the filter
 */
@ConfigurationProperties(prefix = "cortex.gateway.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        long capacity,
        Duration refillPeriod,
        long anonymousCapacity,
        String redisUri,
        String keyPrefix,
        List<String> excludedPaths) {

    /** Default key namespace if {@link #keyPrefix()} is null or blank. */
    public static final String DEFAULT_KEY_PREFIX = "cortex:rl:";

    /** Default excluded patterns: actuator, OpenAPI docs, Swagger UI, public liveness. */
    public static final List<String> DEFAULT_EXCLUDED_PATHS = List.of(
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api/v1/health");

    /**
     * Canonical constructor with null-safe defaults so a partially
     * specified yaml block still yields a usable record (e.g. tests
     * setting only {@code enabled=false}).
     *
     * @param enabled           master switch
     * @param capacity          tokens per authenticated principal
     * @param refillPeriod      refill window; defaults to 1 minute when null
     * @param anonymousCapacity tokens per IP for anonymous traffic
     * @param redisUri          Lettuce-format Redis URI
     * @param keyPrefix         Redis namespace; defaults to {@link #DEFAULT_KEY_PREFIX}
     * @param excludedPaths     bypass patterns; defaults to {@link #DEFAULT_EXCLUDED_PATHS}
     */
    public RateLimitProperties {
        if (refillPeriod == null) {
            refillPeriod = Duration.ofMinutes(1);
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = DEFAULT_KEY_PREFIX;
        }
        if (excludedPaths == null || excludedPaths.isEmpty()) {
            excludedPaths = DEFAULT_EXCLUDED_PATHS;
        }
    }
}
