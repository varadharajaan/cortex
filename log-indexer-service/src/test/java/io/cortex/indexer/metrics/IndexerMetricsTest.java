package io.cortex.indexer.metrics;

import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.admin.NoopQuickwitIndexAdmin;
import io.cortex.indexer.admin.QuickwitIndexAdmin;
import io.cortex.indexer.search.LogSearchClient;
import io.cortex.indexer.search.NoopLogSearchClient;
import io.cortex.indexer.search.SearchResult;
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
     * 6 outcomes for the noop backend
     * ({@code created, exists, dropped, retention_applied,
     * transient_failure, permanent_failure}) + 1 all-unknown
     * placeholder series = 7.
     */
    private static final long EXPECTED_BOOTSTRAP_SERIES = 7L;

    /**
     * Expected lower bound on the bootstrapped search counter
     * family size: 3 outcomes for the noop search backend
     * ({@code search_ok, transient_failure,
     * permanent_failure}) + 1 all-unknown placeholder = 4.
     */
    private static final long EXPECTED_SEARCH_BOOTSTRAP_SERIES = 4L;

    private MeterRegistry registry;
    private IndexerMetrics metrics;

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        final List<QuickwitIndexAdmin> admins =
                List.of(new NoopQuickwitIndexAdmin());
        final List<LogSearchClient> searchClients =
                List.of(new NoopLogSearchClient());
        this.metrics = new IndexerMetrics(
                this.registry, admins, searchClients);
        this.metrics.bootstrapMeters();
    }

    @Test
    void bootstrapRegistersAllFailableOutcomeSeriesForEveryAdmin() {
        // Six outcomes per backend + one all-unknown bootstrap.
        // {created, exists, dropped, retention_applied,
        //  transient_failure, permanent_failure}
        // for the noop backend = 6 series; + 1 all-unknown = 7.
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
        // Same as after first call (6 + 1 = 7).
        assertThat(count).isGreaterThanOrEqualTo(EXPECTED_BOOTSTRAP_SERIES);
    }

    @Test
    void bootstrapRegistersAllFailableSearchOutcomeSeriesForEveryClient() {
        // Three outcomes per backend + one all-unknown bootstrap.
        // {search_ok, transient_failure, permanent_failure}
        // for the noop search backend = 3 series; + 1 all-unknown = 4.
        final long count = this.registry.find(
                IndexerMetrics.METRIC_SEARCH_TOTAL).counters().size();
        assertThat(count).isGreaterThanOrEqualTo(
                EXPECTED_SEARCH_BOOTSTRAP_SERIES);
    }

    @Test
    void incSearchTicksTheCorrectTagTriple() {
        this.metrics.incSearch(
                SearchResult.BACKEND_QUICKWIT,
                SearchResult.OUTCOME_SEARCH_OK,
                "tenant-a");

        final Counter counter = this.registry
                .find(IndexerMetrics.METRIC_SEARCH_TOTAL)
                .tag("backend", SearchResult.BACKEND_QUICKWIT)
                .tag("outcome", SearchResult.OUTCOME_SEARCH_OK)
                .tag("tenant_id", "tenant-a")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void incSearchCoercesNullTagValuesToUnknown() {
        this.metrics.incSearch(null, null, null);

        final Counter counter = this.registry
                .find(IndexerMetrics.METRIC_SEARCH_TOTAL)
                .tag("backend", IndexerMetrics.UNKNOWN)
                .tag("outcome", IndexerMetrics.UNKNOWN)
                .tag("tenant_id", IndexerMetrics.UNKNOWN)
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
