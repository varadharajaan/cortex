package io.cortex.ingest.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Resolves tagged Micrometer counters for the P4.4c outbox observability
 * surface (ADR-0027 D3 / D4).
 *
 * <p>Three counters with bounded cardinality:</p>
 *
 * <ul>
 *   <li>{@value #METRIC_PUBLISHED}{@code {topic, tenant_id}} -- one
 *       increment per row that drained successfully.</li>
 *   <li>{@value #METRIC_FAILED}{@code {topic, tenant_id, reason}} -- one
 *       increment per publish attempt that failed (a single row may
 *       increment this counter N times before going to DLQ).</li>
 *   <li>{@value #METRIC_DLQ}{@code {topic, tenant_id, reason}} -- one
 *       increment per row that was terminally routed to the DLQ and
 *       flipped to {@link OutboxStatus#DEAD}.</li>
 * </ul>
 *
 * <p>Tag values are constrained: {@code topic} is one of two known
 * topic names; {@code tenant_id} is bounded by the {@code tenants}
 * table; {@code reason} is one of the {@link FailureReason} allowlist
 * constants. Micrometer caches the {@link Counter} handles internally
 * keyed by the name + tag pairs, so repeated lookups for the same tag
 * combination return the same counter instance without registry churn.</p>
 */
@Component
public class OutboxMetrics {

    /** Micrometer counter for rows that drained successfully. */
    public static final String METRIC_PUBLISHED = "cortex.ingest.outbox.published";

    /** Micrometer counter for publish attempts that failed (per-attempt). */
    public static final String METRIC_FAILED = "cortex.ingest.outbox.failed";

    /** Micrometer counter for rows terminally routed to the DLQ. */
    public static final String METRIC_DLQ = "cortex.ingest.outbox.dlq";

    /** Tag key for the topic the publish attempt targeted. */
    public static final String TAG_TOPIC = "topic";

    /** Tag key for the tenant id of the source outbox row. */
    public static final String TAG_TENANT = "tenant_id";

    /** Tag key for the failure-reason allowlist value. */
    public static final String TAG_REASON = "reason";

    /** Backing Micrometer registry. */
    private final MeterRegistry registry;

    /**
     * Constructs the helper.
     *
     * @param registry Micrometer registry supplied by Spring Boot's
     *                 auto-configuration (Prometheus-backed at runtime)
     */
    public OutboxMetrics(final MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Increments the {@value #METRIC_PUBLISHED} counter for the supplied
     * {@code (topic, tenant_id)} pair.
     *
     * @param topic   destination topic name
     * @param tenant  tenant id of the source row
     */
    public void incrementPublished(final String topic, final String tenant) {
        Counter.builder(METRIC_PUBLISHED)
                .description("Outbox rows successfully published to the destination topic")
                .tag(TAG_TOPIC, topic)
                .tag(TAG_TENANT, tenant)
                .register(this.registry)
                .increment();
    }

    /**
     * Increments the {@value #METRIC_FAILED} counter for the supplied
     * {@code (topic, tenant_id, reason)} triple.
     *
     * @param topic   destination topic name
     * @param tenant  tenant id of the source row
     * @param reason  short reason category from {@link FailureReason}
     */
    public void incrementFailed(final String topic,
                                final String tenant,
                                final String reason) {
        Counter.builder(METRIC_FAILED)
                .description("Outbox publish attempts that failed and were rescheduled")
                .tag(TAG_TOPIC, topic)
                .tag(TAG_TENANT, tenant)
                .tag(TAG_REASON, reason)
                .register(this.registry)
                .increment();
    }

    /**
     * Increments the {@value #METRIC_DLQ} counter for the supplied
     * {@code (topic, tenant_id, reason)} triple. The {@code topic} tag
     * carries the DLQ topic name, not the production topic name (the
     * production topic is recoverable from the {@code x-orig-topic}
     * Kafka header on the DLQ record).
     *
     * @param topic   DLQ topic name
     * @param tenant  tenant id of the source row
     * @param reason  short reason category from {@link FailureReason}
     */
    public void incrementDlq(final String topic,
                             final String tenant,
                             final String reason) {
        Counter.builder(METRIC_DLQ)
                .description("Outbox rows terminally routed to the dead-letter queue")
                .tag(TAG_TOPIC, topic)
                .tag(TAG_TENANT, tenant)
                .tag(TAG_REASON, reason)
                .register(this.registry)
                .increment();
    }
}
