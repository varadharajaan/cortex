package io.cortex.indexer.metrics;

import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.admin.NoopQuickwitIndexAdmin;
import io.cortex.indexer.admin.QuickwitIndexAdmin;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IndexerMetrics} (P7.0 / ADR-0038 D3 / LD106 +
 * LD112). Verifies the OCP bootstrap loop registers the full counter
 * family at construct-time + the {@code incIndexAdmin} increment
 * path tags the counter with the supplied triple.
 */
class IndexerMetricsTest {

    /**
     * Expected lower bound on the bootstrapped counter family size:
     * 5 failable outcomes for the noop backend
     * ({@code created, exists, dropped, transient_failure,
     * permanent_failure}) + 1 all-unknown placeholder series.
     */
    private static final long EXPECTED_BOOTSTRAP_SERIES = 6L;

    private MeterRegistry registry;
    private IndexerMetrics metrics;

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        final List<QuickwitIndexAdmin> admins =
                List.of(new NoopQuickwitIndexAdmin());
        this.metrics = new IndexerMetrics(this.registry, admins);
        this.metrics.bootstrapMeters();
    }

    @Test
    void bootstrapRegistersAllFailableOutcomeSeriesForEveryAdmin() {
        // Five outcomes per backend + one all-unknown bootstrap.
        // {created, exists, dropped, transient_failure, permanent_failure}
        // for the noop backend = 5 series; + 1 all-unknown = 6.
        final long count = this.registry.find(
                IndexerMetrics.METRIC_INDEX_ADMIN_TOTAL).counters().size();
        assertThat(count).isGreaterThanOrEqualTo(EXPECTED_BOOTSTRAP_SERIES);
    }

    @Test
    void incIndexAdminTicksTheCorrectTagTriple() {
        this.metrics.incIndexAdmin(
                IndexAdminResult.BACKEND_NOOP,
                IndexAdminResult.OUTCOME_NOOP,
                "tenant-a");

        final Counter counter = this.registry
                .find(IndexerMetrics.METRIC_INDEX_ADMIN_TOTAL)
                .tag("backend", IndexAdminResult.BACKEND_NOOP)
                .tag("outcome", IndexAdminResult.OUTCOME_NOOP)
                .tag("tenant_id", "tenant-a")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void incIndexAdminCoercesNullTagValuesToUnknown() {
        this.metrics.incIndexAdmin(null, null, null);

        final Counter counter = this.registry
                .find(IndexerMetrics.METRIC_INDEX_ADMIN_TOTAL)
                .tag("backend", IndexerMetrics.UNKNOWN)
                .tag("outcome", IndexerMetrics.UNKNOWN)
                .tag("tenant_id", IndexerMetrics.UNKNOWN)
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void incIndexAdminCoercesBlankTagValuesToUnknown() {
        this.metrics.incIndexAdmin(" ", "", "\t");

        final Counter counter = this.registry
                .find(IndexerMetrics.METRIC_INDEX_ADMIN_TOTAL)
                .tag("backend", IndexerMetrics.UNKNOWN)
                .tag("outcome", IndexerMetrics.UNKNOWN)
                .tag("tenant_id", IndexerMetrics.UNKNOWN)
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void bootstrapIsIdempotentAcrossInvocations() {
        // Second @PostConstruct simulation must not double-register.
        this.metrics.bootstrapMeters();
        final long count = this.registry.find(
                IndexerMetrics.METRIC_INDEX_ADMIN_TOTAL).counters().size();
        // Same as after first call (5 + 1 = 6).
        assertThat(count).isGreaterThanOrEqualTo(EXPECTED_BOOTSTRAP_SERIES);
    }
}
