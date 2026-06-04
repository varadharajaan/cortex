package io.cortex.remediation.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RemediationMetrics} (P6.0 / LD106 + LD112).
 *
 * <p>Assertions:</p>
 * <ul>
 *   <li>The {@code cortex.remediation.dispatched_total} counter
 *       family is bootstrap-registered at construct-time with the
 *       three-{@code unknown} tag placeholder.</li>
 *   <li>{@link RemediationMetrics#incDispatched(String, String, String)}
 *       registers a new series for non-{@code unknown} tag values
 *       and increments it without overwriting the bootstrap
 *       series.</li>
 *   <li>Null tag values fall back to the bootstrap {@code unknown}
 *       placeholder so tag cardinality stays bounded.</li>
 * </ul>
 */
class RemediationMetricsTest {

    /**
     * Construction must publish the counter family with all-three-{@code unknown}
     * tags so the scrape sees it before the first anomaly (LD106 + LD112).
     */
    @Test
    void bootstrapRegistersDispatchedCounterWithUnknownTags() {
        final MeterRegistry registry = new SimpleMeterRegistry();

        new RemediationMetrics(registry);

        final Counter bootstrap = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", RemediationMetrics.UNKNOWN)
                .tag("outcome", RemediationMetrics.UNKNOWN)
                .tag("tenant_id", RemediationMetrics.UNKNOWN)
                .counter();
        assertThat(bootstrap)
                .as("bootstrap counter with all-unknown tags must exist after construct")
                .isNotNull();
        assertThat(bootstrap.count()).isZero();
    }

    /** A real {@code incDispatched} call must register a new timeseries without disturbing the bootstrap one. */
    @Test
    void incDispatchedRegistersDistinctSeries() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry);

        metrics.incDispatched("noop", "skipped", "tenant-a");

        final Counter dispatched = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", "noop")
                .tag("outcome", "skipped")
                .tag("tenant_id", "tenant-a")
                .counter();
        assertThat(dispatched).isNotNull();
        assertThat(dispatched.count()).isEqualTo(1.0d);

        // Bootstrap series untouched by the real tick.
        final Counter bootstrap = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", RemediationMetrics.UNKNOWN)
                .tag("outcome", RemediationMetrics.UNKNOWN)
                .tag("tenant_id", RemediationMetrics.UNKNOWN)
                .counter();
        assertThat(bootstrap).isNotNull();
        assertThat(bootstrap.count()).isZero();
    }

    /**
     * Null tag arguments must coerce to {@link RemediationMetrics#UNKNOWN}
     * so the timeseries cardinality stays bounded.
     */
    @Test
    void incDispatchedFallsBackToUnknownOnNullTags() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry);

        metrics.incDispatched(null, null, null);

        final Counter bootstrap = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", RemediationMetrics.UNKNOWN)
                .tag("outcome", RemediationMetrics.UNKNOWN)
                .tag("tenant_id", RemediationMetrics.UNKNOWN)
                .counter();
        assertThat(bootstrap).isNotNull();
        assertThat(bootstrap.count()).isEqualTo(1.0d);
    }
}
