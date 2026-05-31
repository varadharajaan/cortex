package io.cortex.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the NL-to-LogQL endpoint (B20, P3.3 / ADR-0018).
 *
 * <p>Bound to the {@code cortex.gateway.nl-query} prefix. The whole feature
 * is gated by {@link #enabled()} so a deploy that wants the gateway without
 * the NL endpoint (e.g. a tenant on a plan without AI features) can disable
 * it at the property layer; in that case the controller bean is not
 * registered and {@code /api/v1/query/nl} returns 404 via the standard
 * {@code NoResourceFoundException} path.</p>
 *
 * <p>The {@link #subBucketCapacity()} + {@link #subBucketRefillPeriod()}
 * fields drive the per-feature rate-limit composed on top of P3.2's global
 * bucket (ADR-0018 section 3). They are intentionally separate from
 * {@link RateLimitProperties} so the global and feature caps can be tuned
 * independently and so the validator + tests for each layer stay focused.</p>
 *
 * @param enabled               master switch (true by default; false in
 *                              tests that do not exercise the endpoint)
 * @param model                 Ollama model id (e.g. {@code mistral},
 *                              {@code llama3.2}); ignored when WireMock
 *                              is in front of the Ollama URL
 * @param temperature           model temperature in {@code [0.0, 1.0]};
 *                              defaults to {@code 0.2} for deterministic
 *                              LogQL generation
 * @param maxTokens             upper bound on model output tokens; defaults
 *                              to {@code 512} (LogQL queries are short)
 * @param confidenceFloor       minimum acceptable model-reported confidence;
 *                              responses below this become 422 NL_QUERY_REFUSED
 * @param subBucketCapacity     per-principal tokens per refill window for
 *                              the NL endpoint; defaults to {@code 10}
 * @param subBucketRefillPeriod refill window for the NL sub-bucket;
 *                              defaults to {@code PT1M}
 * @param subBucketKeyPrefix    Redis key namespace for NL sub-bucket;
 *                              defaults to {@link #DEFAULT_SUB_BUCKET_KEY_PREFIX}
 */
@ConfigurationProperties(prefix = "cortex.gateway.nl-query")
public record NlQueryProperties(
        boolean enabled,
        String model,
        Double temperature,
        Integer maxTokens,
        Double confidenceFloor,
        Long subBucketCapacity,
        Duration subBucketRefillPeriod,
        String subBucketKeyPrefix) {

    /** Default Ollama model id when none configured. */
    public static final String DEFAULT_MODEL = "mistral";

    /** Default temperature for deterministic LogQL output. */
    public static final double DEFAULT_TEMPERATURE = 0.2;

    /** Default max tokens for the bounded LogQL output. */
    public static final int DEFAULT_MAX_TOKENS = 512;

    /** Default minimum confidence; responses below become 422 NL_QUERY_REFUSED. */
    public static final double DEFAULT_CONFIDENCE_FLOOR = 0.3;

    /** Default per-principal NL sub-bucket capacity. */
    public static final long DEFAULT_SUB_BUCKET_CAPACITY = 10L;

    /** Default refill window for the NL sub-bucket. */
    public static final Duration DEFAULT_SUB_BUCKET_REFILL = Duration.ofMinutes(1);

    /** Default Redis namespace for the NL sub-bucket keys. */
    public static final String DEFAULT_SUB_BUCKET_KEY_PREFIX = "cortex:rl:nlq:";

    /**
     * Canonical constructor with null-safe defaults so a partially specified
     * yaml block still yields a usable record (e.g. tests that set only
     * {@code enabled=false}).
     *
     * @param enabled               master switch
     * @param model                 Ollama model id; defaults to {@link #DEFAULT_MODEL}
     * @param temperature           model temperature; defaults to {@link #DEFAULT_TEMPERATURE}
     * @param maxTokens             max output tokens; defaults to {@link #DEFAULT_MAX_TOKENS}
     * @param confidenceFloor       minimum acceptable confidence; defaults to {@link #DEFAULT_CONFIDENCE_FLOOR}
     * @param subBucketCapacity     per-principal tokens; defaults to {@link #DEFAULT_SUB_BUCKET_CAPACITY}
     * @param subBucketRefillPeriod refill window; defaults to {@link #DEFAULT_SUB_BUCKET_REFILL}
     * @param subBucketKeyPrefix    Redis namespace; defaults to {@link #DEFAULT_SUB_BUCKET_KEY_PREFIX}
     */
    public NlQueryProperties {
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        if (temperature == null) {
            temperature = DEFAULT_TEMPERATURE;
        }
        if (maxTokens == null) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
        if (confidenceFloor == null) {
            confidenceFloor = DEFAULT_CONFIDENCE_FLOOR;
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
