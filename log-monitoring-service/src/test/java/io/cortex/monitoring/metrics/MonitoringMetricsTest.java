package io.cortex.monitoring.metrics;

import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.NoopServiceHealthProbe;
import io.cortex.monitoring.probe.ServiceHealthProbe;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MonitoringMetrics} (P8.0).
 *
 * <p>Verifies (a) bootstrap registers the counter family per
 * probe x outcome at construct-time so the
 * {@code /actuator/prometheus} scrape sees the surface from
 * cold start, (b) bootstrap is idempotent across repeated
 * {@code @PostConstruct} firings (multi-context test scenarios),
 * (c) {@code incProbe} increments the counter for the supplied
 * tag triple, and (d) null / blank tag values coerce to
 * {@code unknown}.</p>
 */
class MonitoringMetricsTest {

    private MeterRegistry registry;
    private MonitoringMetrics metrics;

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        final List<ServiceHealthProbe> probes =
                List.of(new NoopServiceHealthProbe());
        this.metrics = new MonitoringMetrics(this.registry, probes);
        this.metrics.bootstrapMeters();
    }

    @Test
    void bootstrapRegistersUnknownPlaceholderSeries() {
        assertThat(this.registry.find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", MonitoringMetrics.UNKNOWN)
                .tag("outcome", MonitoringMetrics.UNKNOWN)
                .tag("service_id", MonitoringMetrics.UNKNOWN)
                .counter()).isNotNull();
    }

    @Test
    void bootstrapRegistersHealthySeriesForEachProbe() {
        assertThat(this.registry.find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", HealthSnapshot.BACKEND_NOOP)
                .tag("outcome", HealthSnapshot.OUTCOME_HEALTHY)
                .tag("service_id", MonitoringMetrics.UNKNOWN)
                .counter()).isNotNull();
    }

    @Test
    void bootstrapRegistersTransientFailureSeriesForEachProbe() {
        assertThat(this.registry.find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", HealthSnapshot.BACKEND_NOOP)
                .tag("outcome", HealthSnapshot.OUTCOME_TRANSIENT_FAILURE)
                .tag("service_id", MonitoringMetrics.UNKNOWN)
                .counter()).isNotNull();
    }

    @Test
    void bootstrapIsIdempotent() {
        // Repeat the @PostConstruct call. Counter series must
        // dedupe by tag set inside Micrometer; we just assert no
        // exception + count remains zero.
        this.metrics.bootstrapMeters();
        this.metrics.bootstrapMeters();
        assertThat(this.registry.find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", HealthSnapshot.BACKEND_NOOP)
                .tag("outcome", HealthSnapshot.OUTCOME_HEALTHY)
                .tag("service_id", MonitoringMetrics.UNKNOWN)
                .counter().count()).isZero();
    }

    @Test
    void incProbeIncrementsCounterForTagTriple() {
        this.metrics.incProbe(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                HealthSnapshot.OUTCOME_HEALTHY,
                "log-indexer-service");
        assertThat(this.registry.find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", HealthSnapshot.BACKEND_EUREKA_ACTUATOR)
                .tag("outcome", HealthSnapshot.OUTCOME_HEALTHY)
                .tag("service_id", "log-indexer-service")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    void incProbeCoercesNullTagsToUnknown() {
        this.metrics.incProbe(null, null, null);
        assertThat(this.registry.find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", MonitoringMetrics.UNKNOWN)
                .tag("outcome", MonitoringMetrics.UNKNOWN)
                .tag("service_id", MonitoringMetrics.UNKNOWN)
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    void incProbeCoercesBlankTagsToUnknown() {
        this.metrics.incProbe("  ", "", "\t");
        assertThat(this.registry.find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", MonitoringMetrics.UNKNOWN)
                .tag("outcome", MonitoringMetrics.UNKNOWN)
                .tag("service_id", MonitoringMetrics.UNKNOWN)
                .counter().count()).isEqualTo(1.0d);
    }
}
