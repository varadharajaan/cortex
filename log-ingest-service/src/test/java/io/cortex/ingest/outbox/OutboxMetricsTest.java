package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxMetrics} (P4.4c / ADR-0027 D3 / D4).
 *
 * <p>Verifies the three counter helpers register the documented
 * {@code (topic, tenant_id, reason)} tag triples and that repeated
 * calls for the same triple resolve to the same underlying
 * {@link io.micrometer.core.instrument.Counter} instance (Micrometer
 * caches by meter id, so calling the helper twice MUST NOT register
 * two separate counters).</p>
 */
class OutboxMetricsTest {

    /** Sample destination topic for the tag assertions. */
    private static final String TOPIC = "cortex.logs.events.v1";

    /** Sample DLQ topic for the tag assertions. */
    private static final String DLQ_TOPIC = "cortex.logs.events.v1.dlq";

    /** Sample tenant id for the tag assertions. */
    private static final String TENANT = "cortex-dev";

    /** Backing registry; recreated per test. */
    private MeterRegistry registry;

    /** SUT. */
    private OutboxMetrics metrics;

    /** Default constructor used by JUnit. */
    OutboxMetricsTest() {
        // no state
    }

    /** Resets the registry before each test. */
    @BeforeEach
    void initMetrics() {
        this.registry = new SimpleMeterRegistry();
        this.metrics = new OutboxMetrics(this.registry);
    }

    /**
     * {@link OutboxMetrics#incrementPublished} writes to a counter
     * tagged with {@code (topic, tenant_id)}.
     */
    @Test
    void incrementPublishedTagsTopicAndTenant() {
        this.metrics.incrementPublished(TOPIC, TENANT);
        this.metrics.incrementPublished(TOPIC, TENANT);

        assertThat(this.registry.counter(
                OutboxMetrics.METRIC_PUBLISHED,
                OutboxMetrics.TAG_TOPIC, TOPIC,
                OutboxMetrics.TAG_TENANT, TENANT).count())
                .isEqualTo(2.0d);
    }

    /**
     * {@link OutboxMetrics#incrementFailed} writes to a counter
     * tagged with {@code (topic, tenant_id, reason)} and keeps
     * separate counts per reason.
     */
    @Test
    void incrementFailedTagsTopicTenantAndReasonSeparately() {
        this.metrics.incrementFailed(TOPIC, TENANT, FailureReason.KAFKA_TIMEOUT);
        this.metrics.incrementFailed(TOPIC, TENANT, FailureReason.KAFKA_EXECUTE);
        this.metrics.incrementFailed(TOPIC, TENANT, FailureReason.KAFKA_TIMEOUT);

        assertThat(this.registry.counter(
                OutboxMetrics.METRIC_FAILED,
                OutboxMetrics.TAG_TOPIC, TOPIC,
                OutboxMetrics.TAG_TENANT, TENANT,
                OutboxMetrics.TAG_REASON, FailureReason.KAFKA_TIMEOUT).count())
                .isEqualTo(2.0d);
        assertThat(this.registry.counter(
                OutboxMetrics.METRIC_FAILED,
                OutboxMetrics.TAG_TOPIC, TOPIC,
                OutboxMetrics.TAG_TENANT, TENANT,
                OutboxMetrics.TAG_REASON, FailureReason.KAFKA_EXECUTE).count())
                .isEqualTo(1.0d);
    }

    /**
     * {@link OutboxMetrics#incrementDlq} writes to a counter tagged
     * with the DLQ topic name + {@code (tenant_id, reason)} and is
     * independent of the same-tagged failed counter.
     */
    @Test
    void incrementDlqIsIndependentOfFailedCounter() {
        this.metrics.incrementFailed(TOPIC, TENANT, FailureReason.OUTBOX_POISON);
        this.metrics.incrementDlq(DLQ_TOPIC, TENANT, FailureReason.OUTBOX_POISON);

        assertThat(this.registry.counter(
                OutboxMetrics.METRIC_DLQ,
                OutboxMetrics.TAG_TOPIC, DLQ_TOPIC,
                OutboxMetrics.TAG_TENANT, TENANT,
                OutboxMetrics.TAG_REASON, FailureReason.OUTBOX_POISON).count())
                .isEqualTo(1.0d);
        assertThat(this.registry.counter(
                OutboxMetrics.METRIC_FAILED,
                OutboxMetrics.TAG_TOPIC, TOPIC,
                OutboxMetrics.TAG_TENANT, TENANT,
                OutboxMetrics.TAG_REASON, FailureReason.OUTBOX_POISON).count())
                .isEqualTo(1.0d);
    }
}
