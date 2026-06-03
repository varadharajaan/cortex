package io.cortex.processor.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link ProcessorMetrics} (P5.0 / Part 17 / ADR-0028 D6).
 *
 * <p>Asserts the three counters register against a Micrometer
 * registry with the correct names, tags, and that the increment
 * helpers fire. The metric surface is stable from P5.0 onwards so
 * Grafana dashboards don't break when the real classifier lands in
 * P5.2.</p>
 */
class ProcessorMetricsTest {

    /** Asserts the three Micrometer counters register with the correct names + tag allowlist. */
    @Test
    void registersThreeCountersWithTagAllowlist() {
        final var registry = new SimpleMeterRegistry();
        final var metrics = new ProcessorMetrics(registry);

        // Increment so the counters report non-zero values.
        metrics.incConsumed();
        metrics.incConsumed();
        metrics.incParsed();
        metrics.incDlqReplay();

        assertThat(registry.get("cortex.processor.events.consumed_total")
                .tag("topic", "cortex.logs.events.v1")
                .tag("tenant_id", "unknown")
                .counter()
                .count()).isEqualTo(2.0);

        assertThat(registry.get("cortex.processor.events.parsed_total")
                .tag("topic", "cortex.logs.events.v1")
                .tag("tenant_id", "unknown")
                .counter()
                .count()).isEqualTo(1.0);

        assertThat(registry.get("cortex.processor.events.dlq_replay_total")
                .tag("topic", "cortex.logs.events.v1.dlq")
                .tag("tenant_id", "unknown")
                .counter()
                .count()).isEqualTo(1.0);
    }

    /**
     * Asserts {@link ProcessorMetrics#incClassified(String)} routes
     * every documented outcome to its pre-registered counter, and
     * that an unknown outcome falls through to {@code error} so the
     * tag cardinality stays bounded (P5.2 / ADR-0029 D5 / Part 17).
     */
    @Test
    void incClassifiedRoutesEveryOutcomeIncludingUnknown() {
        final var registry = new SimpleMeterRegistry();
        final var metrics = new ProcessorMetrics(registry);

        metrics.incClassified(ProcessorMetrics.OUTCOME_ANOMALY);
        metrics.incClassified(ProcessorMetrics.OUTCOME_NORMAL);
        metrics.incClassified(ProcessorMetrics.OUTCOME_LOW_CONFIDENCE);
        metrics.incClassified(ProcessorMetrics.OUTCOME_ERROR);
        // Unknown outcome -> default arm -> error counter.
        metrics.incClassified("unrecognised-outcome");

        assertThat(registry.get(ProcessorMetrics.METRIC_CLASSIFIED_TOTAL)
                .tag("outcome", ProcessorMetrics.OUTCOME_ANOMALY)
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(ProcessorMetrics.METRIC_CLASSIFIED_TOTAL)
                .tag("outcome", ProcessorMetrics.OUTCOME_NORMAL)
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(ProcessorMetrics.METRIC_CLASSIFIED_TOTAL)
                .tag("outcome", ProcessorMetrics.OUTCOME_LOW_CONFIDENCE)
                .counter().count()).isEqualTo(1.0);
        // Two error ticks: explicit OUTCOME_ERROR + the default arm.
        assertThat(registry.get(ProcessorMetrics.METRIC_CLASSIFIED_TOTAL)
                .tag("outcome", ProcessorMetrics.OUTCOME_ERROR)
                .counter().count()).isEqualTo(2.0);
    }
}
