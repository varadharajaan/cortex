package io.cortex.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the {@code searchLogs} endpoint (P9.1b /
 * ADR-0004 / ADR-0049).
 *
 * <p>Bound to the {@code cortex.gateway.search-logs} prefix. The whole
 * feature is gated by {@link #enabled()} so a deploy that wants the
 * gateway without the search endpoint can disable it at the property
 * layer; in that case neither the REST controller nor the GraphQL
 * resolver bean is registered (both are
 * {@code @ConditionalOnProperty}-gated), so {@code /api/v1/logs/search}
 * returns 404 and the {@code searchLogs} GraphQL field has no resolver.</p>
 *
 * <p>The {@link #subBucketCapacity()} + {@link #subBucketRefillPeriod()}
 * + {@link #subBucketKeyPrefix()} fields drive the per-feature
 * {@code search-logs} rate-limit sub-bucket composed on top of P3.2's
 * global bucket (mirrors the NL endpoint's sub-bucket; P9.0a). They are
 * referenced by the {@code @RateLimitFeature} annotation on both
 * surfaces so REST and GraphQL share one bucket per JWT subject.</p>
 *
 * @param enabled               master switch (true by default; false in
 *                              tests that do not exercise the endpoint)
 * @param serviceId             discovery service id of the indexer;
 *                              defaults to {@link #DEFAULT_SERVICE_ID}
 * @param requestTimeout        connect + read timeout for the downstream
 *                              call; defaults to {@link #DEFAULT_REQUEST_TIMEOUT}
 * @param defaultMaxHits        hit ceiling applied when the caller omits
 *                              {@code maxHits}; defaults to {@link #DEFAULT_MAX_HITS}
 * @param maxHitsCeiling        hard upper bound; a larger requested
 *                              {@code maxHits} is clamped here; defaults
 *                              to {@link #DEFAULT_MAX_HITS_CEILING}
 * @param subBucketCapacity     per-principal tokens per refill window for
 *                              the search endpoint; defaults to
 *                              {@link #DEFAULT_SUB_BUCKET_CAPACITY}
 * @param subBucketRefillPeriod refill window for the search sub-bucket;
 *                              defaults to {@link #DEFAULT_SUB_BUCKET_REFILL}
 * @param subBucketKeyPrefix    Redis key namespace for the search
 *                              sub-bucket; defaults to
 *                              {@link #DEFAULT_SUB_BUCKET_KEY_PREFIX}
 */
@ConfigurationProperties(prefix = "cortex.gateway.search-logs")
public record SearchLogsProperties(
        boolean enabled,
        String serviceId,
        Duration requestTimeout,
        Integer defaultMaxHits,
        Integer maxHitsCeiling,
        Long subBucketCapacity,
        Duration subBucketRefillPeriod,
        String subBucketKeyPrefix) {

    /** Default discovery id of the downstream indexer. */
    public static final String DEFAULT_SERVICE_ID = "log-indexer-service";

    /** Default connect + read timeout for the downstream search call. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default hit ceiling when the caller omits {@code maxHits}. */
    public static final int DEFAULT_MAX_HITS = 50;

    /** Default hard upper bound on requested {@code maxHits}. */
    public static final int DEFAULT_MAX_HITS_CEILING = 1000;

    /** Default per-principal search sub-bucket capacity. */
    public static final long DEFAULT_SUB_BUCKET_CAPACITY = 30L;

    /** Default refill window for the search sub-bucket. */
    public static final Duration DEFAULT_SUB_BUCKET_REFILL = Duration.ofMinutes(1);

    /** Default Redis namespace for the search sub-bucket keys. */
    public static final String DEFAULT_SUB_BUCKET_KEY_PREFIX = "cortex:rl:search:";

    /**
     * Canonical constructor with null-safe defaults so a partially
     * specified yaml block still yields a usable record (e.g. tests that
     * set only {@code enabled=false}).
     *
     * @param enabled               master switch
     * @param serviceId             indexer discovery id; defaults to {@link #DEFAULT_SERVICE_ID}
     * @param requestTimeout        downstream timeout; defaults to {@link #DEFAULT_REQUEST_TIMEOUT}
     * @param defaultMaxHits        default hit ceiling; defaults to {@link #DEFAULT_MAX_HITS}
     * @param maxHitsCeiling        hard hit ceiling; defaults to {@link #DEFAULT_MAX_HITS_CEILING}
     * @param subBucketCapacity     per-principal tokens; defaults to {@link #DEFAULT_SUB_BUCKET_CAPACITY}
     * @param subBucketRefillPeriod refill window; defaults to {@link #DEFAULT_SUB_BUCKET_REFILL}
     * @param subBucketKeyPrefix    Redis namespace; defaults to {@link #DEFAULT_SUB_BUCKET_KEY_PREFIX}
     */
    public SearchLogsProperties {
        if (serviceId == null || serviceId.isBlank()) {
            serviceId = DEFAULT_SERVICE_ID;
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        if (defaultMaxHits == null || defaultMaxHits <= 0) {
            defaultMaxHits = DEFAULT_MAX_HITS;
        }
        if (maxHitsCeiling == null || maxHitsCeiling <= 0) {
            maxHitsCeiling = DEFAULT_MAX_HITS_CEILING;
        }
        if (subBucketCapacity == null) {
            subBucketCapacity = DEFAULT_SUB_BUCKET_CAPACITY;
        }
        if (subBucketRefillPeriod == null) {
            subBucketRefillPeriod = DEFAULT_SUB_BUCKET_REFILL;
        }
        if (subBucketKeyPrefix == null || subBucketKeyPrefix.isBlank()) {
            subBucketKeyPrefix = DEFAULT_SUB_BUCKET_KEY_PREFIX;
        }
    }
}
