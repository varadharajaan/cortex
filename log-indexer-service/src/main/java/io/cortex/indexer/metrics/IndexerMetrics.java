package io.cortex.indexer.metrics;

import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.admin.QuickwitIndexAdmin;
import io.cortex.indexer.search.LogSearchClient;
import io.cortex.indexer.search.SearchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Micrometer counter published by the log-indexer-service
 * (P7.0 / Part 17 / ADR-0038 D3).
 *
 * <p>One counter establishes the stable metric surface that Grafana
 * dashboards subscribe to from P7.0 onwards:
 * {@code cortex.indexer.index_admin_total{backend, outcome,
 * tenant_id}}. Bootstrap-registered at construct-time with all-three
 * placeholder values per LD106 + LD112 so the
 * {@code /actuator/prometheus} scrape sees the counter family before
 * the first admin call ticks.</p>
 *
 * <p>OCP-flipped bootstrap loop (mirror P6.0a / ADR-0036): iterates
 * over the list of {@link QuickwitIndexAdmin} beans active in the
 * current profile (Spring injects an autowired {@code List<T>} bound
 * by the conditionals on each admin impl) and bootstraps the
 * failable outcome series for each backend's
 * {@link QuickwitIndexAdmin#backendId()}. Adding a new admin
 * backend therefore requires zero edits here -- the Open/Closed
 * Principle is honoured at the bootstrap boundary as well as at
 * the SPI boundary.</p>
 *
 * <p>Tag-key allowlist enforced per Part 17 rule: only
 * {@code backend}, {@code outcome}, and {@code tenant_id} are
 * emitted on this counter. Free-form values are bounded by the
 * {@link IndexAdminResult} constants
 * ({@code BACKEND_NOOP}, {@code OUTCOME_NOOP}, ...).</p>
 */
@Component
@RequiredArgsConstructor
public class IndexerMetrics {

    /** The index-admin counter metric name (kept public so tests can reference it). */
    public static final String METRIC_INDEX_ADMIN_TOTAL =
            "cortex.indexer.index_admin_total";

    /**
     * The search counter metric name (P7.4 / ADR-0042 D7;
     * kept public so tests can reference it). Tagged with the
     * same {@code backend, outcome, tenant_id} Part 17 allowlist
     * as {@link #METRIC_INDEX_ADMIN_TOTAL} so the existing
     * Grafana cardinality guard rails apply uniformly.
     */
    public static final String METRIC_SEARCH_TOTAL =
            "cortex.indexer.search_total";

    /** Placeholder tag value used for the bootstrap-registration counter (LD106). */
    public static final String UNKNOWN = "unknown";

    private static final String TAG_BACKEND = "backend";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_TENANT = "tenant_id";

    private static final String COUNTER_DESCRIPTION =
            "Quickwit index admin calls handled by QuickwitIndexAdmin (P7.0 / ADR-0038)";

    private static final String SEARCH_COUNTER_DESCRIPTION =
            "Tenant-scoped search calls handled by LogSearchClient (P7.4 / ADR-0042)";

    private final MeterRegistry registry;
    private final List<QuickwitIndexAdmin> admins;
    private final List<LogSearchClient> searchClients;

    /**
     * Bootstrap the counter family per LD106 + LD112 so the
     * {@code /actuator/prometheus} scrape sees a stable surface even
     * before the first admin call ticks.
     *
     * <p>Loops over every active {@link QuickwitIndexAdmin} bean and
     * bootstraps the failable outcome series for its backend id.
     * The all-{@code unknown} placeholder series is always
     * registered so the counter family is visible even when the
     * admin list is empty (test fixtures).</p>
     */
    @PostConstruct
    void bootstrapMeters() {
        bootstrap(UNKNOWN, UNKNOWN);
        for (final QuickwitIndexAdmin admin : this.admins) {
            final String backend = admin.backendId();
            bootstrap(backend, IndexAdminResult.OUTCOME_CREATED);
            bootstrap(backend, IndexAdminResult.OUTCOME_EXISTS);
            bootstrap(backend, IndexAdminResult.OUTCOME_DROPPED);
            bootstrap(backend, IndexAdminResult.OUTCOME_RETENTION_APPLIED);
            bootstrap(backend, IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
            bootstrap(backend, IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        }
        bootstrapSearch(UNKNOWN, UNKNOWN);
        for (final LogSearchClient client : this.searchClients) {
            final String backend = client.backendId();
            bootstrapSearch(backend, SearchResult.OUTCOME_SEARCH_OK);
            bootstrapSearch(backend, SearchResult.OUTCOME_TRANSIENT_FAILURE);
            bootstrapSearch(backend, SearchResult.OUTCOME_PERMANENT_FAILURE);
        }
    }

    /**
     * Increment the index-admin counter for the supplied tag triple.
     * Counter series are lazy-registered by Micrometer on first
     * call; the bootstrap series with all-unknown tags remains so
     * dashboards never flatline.
     *
     * @param backend  one of the {@code IndexAdminResult.BACKEND_*}
     *                 constants (e.g. {@code noop} in P7.0;
     *                 {@code quickwit} in P7.1+)
     * @param outcome  one of the {@code IndexAdminResult.OUTCOME_*}
     *                 constants ({@code noop}, {@code created},
     *                 {@code exists}, {@code dropped},
     *                 {@code retention_applied},
     *                 {@code transient_failure},
     *                 {@code permanent_failure})
     * @param tenantId tenant id from the {@link io.cortex.indexer.admin.IndexSpec}
     */
    public void incIndexAdmin(final String backend, final String outcome,
                              final String tenantId) {
        Counter.builder(METRIC_INDEX_ADMIN_TOTAL)
                .description(COUNTER_DESCRIPTION)
                .tag(TAG_BACKEND, coerce(backend))
                .tag(TAG_OUTCOME, coerce(outcome))
                .tag(TAG_TENANT, coerce(tenantId))
                .register(this.registry)
                .increment();
    }

    /**
     * Increment the search counter for the supplied tag triple
     * (P7.4 / ADR-0042 D7). Counter series are lazy-registered by
     * Micrometer on first call; the bootstrap series with
     * all-unknown tags remains so dashboards never flatline.
     *
     * @param backend  one of the {@code SearchResult.BACKEND_*}
     *                 constants ({@code noop}, {@code quickwit})
     * @param outcome  one of the {@code SearchResult.OUTCOME_*}
     *                 constants ({@code noop}, {@code search_ok},
     *                 {@code transient_failure},
     *                 {@code permanent_failure})
     * @param tenantId tenant id from the
     *                 {@link io.cortex.indexer.search.SearchRequest}
     */
    public void incSearch(final String backend, final String outcome,
                          final String tenantId) {
        Counter.builder(METRIC_SEARCH_TOTAL)
                .description(SEARCH_COUNTER_DESCRIPTION)
                .tag(TAG_BACKEND, coerce(backend))
                .tag(TAG_OUTCOME, coerce(outcome))
                .tag(TAG_TENANT, coerce(tenantId))
                .register(this.registry)
                .increment();
    }

    /**
     * Registers a zero-valued counter for the {@code backend} /
     * {@code outcome} pair with a placeholder tenant tag so the
     * series is visible to {@code /actuator/prometheus} before any
     * admin call has incremented it.
     */
    private void bootstrap(final String backend, final String outcome) {
        Counter.builder(METRIC_INDEX_ADMIN_TOTAL)
                .description(COUNTER_DESCRIPTION)
                .tag(TAG_BACKEND, coerce(backend))
                .tag(TAG_OUTCOME, coerce(outcome))
                .tag(TAG_TENANT, UNKNOWN)
                .register(this.registry);
    }

    /**
     * Registers a zero-valued search counter for the
     * {@code backend} / {@code outcome} pair with a placeholder
     * tenant tag so the series is visible to
     * {@code /actuator/prometheus} before any search call has
     * incremented it (P7.4 / ADR-0042 D7).
     */
    private void bootstrapSearch(final String backend, final String outcome) {
        Counter.builder(METRIC_SEARCH_TOTAL)
                .description(SEARCH_COUNTER_DESCRIPTION)
                .tag(TAG_BACKEND, coerce(backend))
                .tag(TAG_OUTCOME, coerce(outcome))
                .tag(TAG_TENANT, UNKNOWN)
                .register(this.registry);
    }

    /**
     * Coerce a null or blank tag value to the
     * {@link #UNKNOWN} placeholder so Micrometer never sees a null
     * tag value (which would NPE at counter registration).
     */
    private static String coerce(final String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }
}
