package io.cortex.processor.sink;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SinkMetrics} lazy counter registration
 * (P5.3 / ADR-0030, LD106).
 */
class SinkMetricsTest {

    /**
     * Counter families are bootstrapped at construction so metric
     * names are visible on {@code /actuator/metrics} from process
     * start; per-tenant series are still added lazily on first tick.
     */
    @Test
    void countersAreLazyUntilFirstTick() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final SinkMetrics metrics = new SinkMetrics(registry);

        // Four bootstrap series (one per family) registered at
        // construction with tenant=unknown so dashboards see the
        // counter names at zero before any traffic.
        assertThat(registry.getMeters()).hasSize(4);
        assertThat(registry.find(SinkMetrics.METRIC_LOKI_PUBLISHED)
                .tag(SinkMetrics.TAG_TENANT, "unknown")
                .counter().count()).isEqualTo(0.0d);

        metrics.lokiPublished("cortex-dev");
        assertThat(registry.find(SinkMetrics.METRIC_LOKI_PUBLISHED)
                .tag(SinkMetrics.TAG_TENANT, "cortex-dev")
                .counter().count()).isEqualTo(1.0d);
    }

    /** Each (metric, tenant, reason) combination is its own series. */
    @Test
    void perTenantPerReasonSeriesAreIsolated() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final SinkMetrics metrics = new SinkMetrics(registry);

        metrics.lokiFailed("acme", SinkMetrics.Reason.HTTP_STATUS);
        metrics.lokiFailed("acme", SinkMetrics.Reason.HTTP_STATUS);
        metrics.lokiFailed("acme", SinkMetrics.Reason.TIMEOUT);
        metrics.lokiFailed("beta", SinkMetrics.Reason.HTTP_STATUS);
        metrics.quickwitFailed("acme", SinkMetrics.Reason.SERIALIZATION);

        assertThat(registry.get(SinkMetrics.METRIC_LOKI_FAILED)
                .tag(SinkMetrics.TAG_TENANT, "acme")
                .tag(SinkMetrics.TAG_REASON, "http_status")
                .counter().count()).isEqualTo(2.0d);
        assertThat(registry.get(SinkMetrics.METRIC_LOKI_FAILED)
                .tag(SinkMetrics.TAG_TENANT, "acme")
                .tag(SinkMetrics.TAG_REASON, "timeout")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(SinkMetrics.METRIC_LOKI_FAILED)
                .tag(SinkMetrics.TAG_TENANT, "beta")
                .tag(SinkMetrics.TAG_REASON, "http_status")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(SinkMetrics.METRIC_QUICKWIT_FAILED)
                .tag(SinkMetrics.TAG_TENANT, "acme")
                .tag(SinkMetrics.TAG_REASON, "serialization")
                .counter().count()).isEqualTo(1.0d);
    }

    /** Null/blank tenant coerces to {@code unknown}. */
    @Test
    void blankTenantCoercedToUnknown() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final SinkMetrics metrics = new SinkMetrics(registry);

        metrics.lokiPublished(null);
        metrics.quickwitPublished("   ");

        assertThat(registry.get(SinkMetrics.METRIC_LOKI_PUBLISHED)
                .tag(SinkMetrics.TAG_TENANT, "unknown")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(SinkMetrics.METRIC_QUICKWIT_PUBLISHED)
                .tag(SinkMetrics.TAG_TENANT, "unknown")
                .counter().count()).isEqualTo(1.0d);
    }

    /** Reason enum maps to lowercase snake_case tag values. */
    @Test
    void reasonEnumMapsToSnakeCaseTag() {
        assertThat(SinkMetrics.Reason.HTTP_STATUS.tag()).isEqualTo("http_status");
        assertThat(SinkMetrics.Reason.TIMEOUT.tag()).isEqualTo("timeout");
        assertThat(SinkMetrics.Reason.TRANSPORT.tag()).isEqualTo("transport");
        assertThat(SinkMetrics.Reason.SERIALIZATION.tag()).isEqualTo("serialization");
        assertThat(SinkMetrics.Reason.UNKNOWN.tag()).isEqualTo("unknown");
    }

    /** Null reason defaults to UNKNOWN. */
    @Test
    void nullReasonCoercedToUnknown() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final SinkMetrics metrics = new SinkMetrics(registry);

        metrics.lokiFailed("acme", null);
        metrics.quickwitFailed("acme", null);

        assertThat(registry.get(SinkMetrics.METRIC_LOKI_FAILED)
                .tag(SinkMetrics.TAG_TENANT, "acme")
                .tag(SinkMetrics.TAG_REASON, "unknown")
                .counter().count()).isEqualTo(1.0d);
        assertThat(registry.get(SinkMetrics.METRIC_QUICKWIT_FAILED)
                .tag(SinkMetrics.TAG_TENANT, "acme")
                .tag(SinkMetrics.TAG_REASON, "unknown")
                .counter().count()).isEqualTo(1.0d);
    }
}
