package io.cortex.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the {@code getLogById} endpoint (P9.2b /
 * ADR-0004 / ADR-0049).
 *
 * <p>Bound to the {@code cortex.gateway.get-log-by-id} prefix. The whole
 * feature is gated by {@link #enabled()} so a deploy that wants the
 * gateway without the get-by-id endpoint can disable it at the property
 * layer; in that case neither the REST controller nor the GraphQL
 * resolver bean is registered (both are
 * {@code @ConditionalOnProperty}-gated), so {@code /api/v1/logs/{eventId}}
 * returns 404 and the {@code getLogById} GraphQL field has no resolver.</p>
 *
 * <p>The {@link #subBucketCapacity()} + {@link #subBucketRefillPeriod()}
 * + {@link #subBucketKeyPrefix()} fields drive the per-feature
 * {@code get-log-by-id} rate-limit sub-bucket composed on top of P3.2's
 * global bucket (mirrors the NL + search sub-buckets). They are
 * referenced by the {@code @RateLimitFeature} annotation on both
 * surfaces so REST and GraphQL share one bucket per JWT subject.</p>
 *
 * @param enabled               master switch (true by default; false in
 *                              tests that do not exercise the endpoint)
 * @param serviceId             discovery service id of the ingest backer;
 *                              defaults to {@link #DEFAULT_SERVICE_ID}
 * @param requestTimeout        connect + read timeout for the downstream
 *                              call; defaults to {@link #DEFAULT_REQUEST_TIMEOUT}
 * @param subBucketCapacity     per-principal tokens per refill window;
 *                              defaults to {@link #DEFAULT_SUB_BUCKET_CAPACITY}
 * @param subBucketRefillPeriod refill window for the sub-bucket;
 *                              defaults to {@link #DEFAULT_SUB_BUCKET_REFILL}
 * @param subBucketKeyPrefix    Redis key namespace for the sub-bucket;
 *                              defaults to {@link #DEFAULT_SUB_BUCKET_KEY_PREFIX}
 */
@ConfigurationProperties(prefix = "cortex.gateway.get-log-by-id")
public record GetLogByIdProperties(
        boolean enabled,
        String serviceId,
        Duration requestTimeout,
        Long subBucketCapacity,
        Duration subBucketRefillPeriod,
        String subBucketKeyPrefix) {

    /** Default discovery id of the downstream ingest backer. */
    public static final String DEFAULT_SERVICE_ID = "log-ingest-service";

    /** Default connect + read timeout for the downstream call. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default per-principal sub-bucket capacity. */
    public static final long DEFAULT_SUB_BUCKET_CAPACITY = 60L;

    /** Default refill window for the sub-bucket. */
    public static final Duration DEFAULT_SUB_BUCKET_REFILL = Duration.ofMinutes(1);

    /** Default Redis namespace for the sub-bucket keys. */
    public static final String DEFAULT_SUB_BUCKET_KEY_PREFIX = "cortex:rl:getlog:";

    /**
     * Canonical constructor with null-safe defaults so a partially
     * specified yaml block still yields a usable record (e.g. tests that
     * set only {@code enabled=false}).
     *
     * @param enabled               master switch
     * @param serviceId             ingest discovery id; defaults to {@link #DEFAULT_SERVICE_ID}
     * @param requestTimeout        downstream timeout; defaults to {@link #DEFAULT_REQUEST_TIMEOUT}
     * @param subBucketCapacity     per-principal tokens; defaults to {@link #DEFAULT_SUB_BUCKET_CAPACITY}
     * @param subBucketRefillPeriod refill window; defaults to {@link #DEFAULT_SUB_BUCKET_REFILL}
     * @param subBucketKeyPrefix    Redis namespace; defaults to {@link #DEFAULT_SUB_BUCKET_KEY_PREFIX}
     */
    public GetLogByIdProperties {
        if (serviceId == null || serviceId.isBlank()) {
            serviceId = DEFAULT_SERVICE_ID;
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
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
