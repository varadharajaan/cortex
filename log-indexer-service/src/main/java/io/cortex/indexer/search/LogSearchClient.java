package io.cortex.indexer.search;

/**
 * Read-path SPI: forward a tenant-scoped
 * {@link SearchRequest} to the active search backend (Quickwit
 * in production, noop in default-dev) and return the bounded
 * {@link SearchResult} verdict (P7.4 / ADR-0042 D1).
 *
 * <p>Parallel to
 * {@link io.cortex.indexer.admin.QuickwitIndexAdmin} -- same
 * Open/Closed shape: adding a new backend (e.g. OpenSearch in a
 * hypothetical P9.x) is a new {@code @Component} that
 * implements this interface, gated by a new
 * {@code cortex.indexer.search.backend=<id>} property. The
 * {@link io.cortex.indexer.metrics.IndexerMetrics} bootstrap
 * loop picks up the new bean automatically because it injects
 * {@code List<LogSearchClient>}.</p>
 *
 * <p>Implementations MUST NOT throw -- every error path is
 * funneled into a {@link SearchResult#transientFailure(String,
 * String)} or {@link SearchResult#permanentFailure(String,
 * String)} verdict so the caller can metric + alert on it
 * uniformly. Implementations MUST tick the
 * {@code cortex.indexer.search_total} counter on every terminal
 * verdict.</p>
 */
public interface LogSearchClient {

    /**
     * Identifies the search backend the implementation drives;
     * stamped on the {@code cortex.indexer.search_total
     * {backend=...}} tag.
     *
     * @return one of the {@link SearchResult} {@code BACKEND_*}
     *         constants (e.g. {@code noop}, {@code quickwit});
     *         never {@code null}, never blank
     */
    String backendId();

    /**
     * Execute the supplied search request against the active
     * backend.
     *
     * <p>A {@code null} request is treated as a permanent
     * caller-side bug ({@code permanent_failure /
     * quickwit:null-request}); a non-null request whose
     * {@code indexId} does NOT carry the canonical
     * {@code cortex-<tenantId>-} prefix is rejected as
     * {@code permanent_failure / quickwit:tenant-mismatch}
     * (ADR-0042 D3). Otherwise the implementation forwards to
     * the backend and classifies the HTTP / transport / unknown
     * outcomes per ADR-0039 D3.</p>
     *
     * @param request the tenant-scoped search request; may be
     *                {@code null}
     * @return the verdict; never {@code null}
     */
    SearchResult search(SearchRequest request);
}
