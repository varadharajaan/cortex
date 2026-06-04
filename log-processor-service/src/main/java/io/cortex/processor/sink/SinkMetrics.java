package io.cortex.processor.sink;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters published by the P5.3 fan-out sinks
 * (ADR-0030 D4; Part 17 allowlist).
 *
 * <p>Two counter families per sink:</p>
 * <ul>
 *   <li>{@code cortex.processor.sink.{loki|quickwit}.published_total
 *       {tenant_id}} -- ticks once per successful send. Tag
 *       cardinality bounded by the configured tenant set.</li>
 *   <li>{@code cortex.processor.sink.{loki|quickwit}.failed_total
 *       {tenant_id, reason}} -- ticks once per failure. The
 *       {@code reason} tag is the only addition to the Part 17
 *       allowlist for this sub-phase; values are the bounded set
 *       declared in {@link Reason}.</li>
 * </ul>
 *
 * <p>Counters are lazy-registered per (tenant, reason) combination
 * through a {@link ConcurrentMap} so the registry only holds series
 * we have actually emitted (LD106). All counter names + the bounded
 * reason vocabulary are exposed as {@code public static final}
 * constants so tests + the smoke script can assert against the
 * Prometheus exposition without duplicating string literals.</p>
 */
@Component("cortexSinkMetrics")
@EnableConfigurationProperties(SinkProperties.class)
public class SinkMetrics {

    /** Metric name for the Loki published counter family. */
    public static final String METRIC_LOKI_PUBLISHED =
            "cortex.processor.sink.loki.published_total";
    /** Metric name for the Loki failed counter family. */
    public static final String METRIC_LOKI_FAILED =
            "cortex.processor.sink.loki.failed_total";
    /** Metric name for the Quickwit published counter family. */
    public static final String METRIC_QUICKWIT_PUBLISHED =
            "cortex.processor.sink.quickwit.published_total";
    /** Metric name for the Quickwit failed counter family. */
    public static final String METRIC_QUICKWIT_FAILED =
            "cortex.processor.sink.quickwit.failed_total";

    /** Tag key for the tenant id. */
    public static final String TAG_TENANT = "tenant_id";
    /** Tag key for the failure reason (failed counter only). */
    public static final String TAG_REASON = "reason";

    /** Sentinel tenant tag value when the event carries no tenant. */
    private static final String UNKNOWN_TENANT = "unknown";

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * Spring constructor. Bootstraps a zero-valued series for each
     * counter family (tenant=unknown, reason=unknown) so the metric
     * names are visible on {@code /actuator/metrics} and the
     * Prometheus exposition from process start, before any sink has
     * fired. Per-tenant series are still added lazily on first tick.
     *
     * @param registry the autoconfigured Micrometer registry
     */
    public SinkMetrics(final MeterRegistry registry) {
        this.registry = registry;
        bootstrapPublished(METRIC_LOKI_PUBLISHED);
        bootstrapPublished(METRIC_QUICKWIT_PUBLISHED);
        bootstrapFailed(METRIC_LOKI_FAILED);
        bootstrapFailed(METRIC_QUICKWIT_FAILED);
    }

    /**
     * Register a zero-valued bootstrap series for a published counter family.
     *
     * @param metric counter metric name
     */
    private void bootstrapPublished(final String metric) {
        final String key = metric + "|" + UNKNOWN_TENANT;
        this.counters.computeIfAbsent(key, ignored ->
                Counter.builder(metric)
                        .description("ParsedEventSink successful publish count")
                        .tag(TAG_TENANT, UNKNOWN_TENANT)
                        .register(this.registry));
    }

    /**
     * Register a zero-valued bootstrap series for a failed counter family.
     *
     * @param metric counter metric name
     */
    private void bootstrapFailed(final String metric) {
        final String key = metric + "|" + UNKNOWN_TENANT + "|" + Reason.UNKNOWN.tag();
        this.counters.computeIfAbsent(key, ignored ->
                Counter.builder(metric)
                        .description("ParsedEventSink failed publish count")
                        .tag(TAG_TENANT, UNKNOWN_TENANT)
                        .tag(TAG_REASON, Reason.UNKNOWN.tag())
                        .register(this.registry));
    }

    /**
     * Tick the Loki published counter for the supplied tenant.
     *
     * @param tenantId source event tenant; coerced to
     *                 {@code "unknown"} when blank
     */
    public void lokiPublished(final String tenantId) {
        incPublished(METRIC_LOKI_PUBLISHED, tenantId);
    }

    /**
     * Tick the Loki failed counter for the supplied tenant + reason.
     *
     * @param tenantId source event tenant
     * @param reason   failure category
     */
    public void lokiFailed(final String tenantId, final Reason reason) {
        incFailed(METRIC_LOKI_FAILED, tenantId, reason);
    }

    /**
     * Tick the Quickwit published counter for the supplied tenant.
     *
     * @param tenantId source event tenant
     */
    public void quickwitPublished(final String tenantId) {
        incPublished(METRIC_QUICKWIT_PUBLISHED, tenantId);
    }

    /**
     * Tick the Quickwit failed counter for the supplied tenant +
     * reason.
     *
     * @param tenantId source event tenant
     * @param reason   failure category
     */
    public void quickwitFailed(final String tenantId, final Reason reason) {
        incFailed(METRIC_QUICKWIT_FAILED, tenantId, reason);
    }

    /**
     * Lazily registers and increments a per-tenant published_total counter.
     *
     * @param metric   counter metric name
     * @param tenantId source event tenant (may be null/blank)
     */
    private void incPublished(final String metric, final String tenantId) {
        final String tenant = sanitiseTenant(tenantId);
        final String key = metric + "|" + tenant;
        this.counters.computeIfAbsent(key, ignored ->
                Counter.builder(metric)
                        .description("ParsedEventSink successful publish count")
                        .tag(TAG_TENANT, tenant)
                        .register(this.registry)).increment();
    }

    /**
     * Lazily registers and increments a per-tenant per-reason failed_total counter.
     *
     * @param metric   counter metric name
     * @param tenantId source event tenant (may be null/blank)
     * @param reason   failure reason (null coerced to {@link Reason#UNKNOWN})
     */
    private void incFailed(final String metric, final String tenantId,
                           final Reason reason) {
        final String tenant = sanitiseTenant(tenantId);
        final String reasonTag = reason == null
                ? Reason.UNKNOWN.tag() : reason.tag();
        final String key = metric + "|" + tenant + "|" + reasonTag;
        this.counters.computeIfAbsent(key, ignored ->
                Counter.builder(metric)
                        .description("ParsedEventSink failed publish count")
                        .tag(TAG_TENANT, tenant)
                        .tag(TAG_REASON, reasonTag)
                        .register(this.registry)).increment();
    }

    /**
     * Coerces null or blank tenant ids to {@value #UNKNOWN_TENANT} for stable cardinality.
     *
     * @param tenantId raw tenant id (may be null/blank)
     * @return tenant id or {@value #UNKNOWN_TENANT}
     */
    private static String sanitiseTenant(final String tenantId) {
        return tenantId == null || tenantId.isBlank() ? UNKNOWN_TENANT : tenantId;
    }

    /**
     * Bounded vocabulary for the {@code reason} tag on the
     * {@code failed_total} counter families (Part 17 tag-cardinality
     * discipline).
     */
    public enum Reason {

        /** Sink returned a non-2xx HTTP status. */
        HTTP_STATUS("http_status"),
        /** Sink call timed out at the JDK HTTP layer. */
        TIMEOUT("timeout"),
        /** Sink call surfaced an IO or transport error. */
        TRANSPORT("transport"),
        /** Body serialisation failed before the HTTP call started. */
        SERIALIZATION("serialization"),
        /** Catch-all for any other RuntimeException. */
        UNKNOWN("unknown");

        private final String tag;

        /** @param tag lowercase snake_case tag value emitted on the {@code reason} tag */
        Reason(final String tag) {
            this.tag = tag;
        }

        /**
         * Get the tag value (lowercase, snake_case) emitted on the
         * {@code reason} tag.
         *
         * @return tag value
         */
        public String tag() {
            return this.tag;
        }
    }
}
