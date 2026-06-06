package io.cortex.indexer.admin;

/**
 * Service Provider Interface for administering Quickwit index
 * lifecycle from the CORTEX log-indexer-service (P7.0 / ADR-0038 D1).
 *
 * <p>Implementations decide whether + how to manage the per-tenant
 * Quickwit index: the default {@link NoopQuickwitIndexAdmin} returns
 * {@link IndexAdminResult#noop(String)} so the P7.0 scaffold runs
 * end-to-end without any Quickwit dependency. P7.1+ will land the
 * real HTTP admin client gated by
 * {@code cortex.indexer.admin.backend=quickwit}.</p>
 *
 * <p>Selection at runtime is driven by the {@code cortex.indexer
 * .admin.backend} property + {@code @ConditionalOnProperty} on each
 * implementation. Only one admin bean is active in a given
 * profile.</p>
 *
 * <p>Implementations MUST be thread-safe. The admin surface will be
 * called from REST controllers (P7.4 search-proxy + admin endpoints)
 * + scheduled retention sweepers (P7.2) concurrently.</p>
 *
 * <p>Implementations MUST NOT throw on transient downstream
 * failures (e.g. Quickwit 429 / 5xx). Returning an
 * {@link IndexAdminResult} with
 * {@code outcome=transient_failure} ticks the failed-outcome
 * counter and lets the caller retry per its policy; the contract
 * is symmetric with the P6 {@code RemediationDispatcher} SPI per
 * ADR-0032 D6.</p>
 */
public interface QuickwitIndexAdmin {

    /**
     * Stable backend identifier used by {@link IndexAdminResult} +
     * {@link io.cortex.indexer.metrics.IndexerMetrics} bootstrap to
     * publish the per-backend outcome series before the first admin
     * call.
     *
     * @return the backend id; one of the
     *         {@link IndexAdminResult}{@code .BACKEND_*} constants
     *         ({@code noop}, {@code quickwit}); never {@code null},
     *         never blank
     */
    String backendId();

    /**
     * Ensure an index exists for the supplied {@link IndexSpec}. Idempotent:
     * implementations MUST treat a pre-existing index as success and
     * surface {@link IndexAdminResult#OUTCOME_EXISTS} in the verdict.
     *
     * @param spec the spec to materialise; never {@code null}
     * @return the verdict; never {@code null}
     */
    IndexAdminResult ensureIndex(IndexSpec spec);

    /**
     * Drop the index identified by {@code indexId}. Idempotent:
     * implementations MUST treat a missing index as success
     * ({@link IndexAdminResult#OUTCOME_DROPPED}). The retention
     * sweeper (P7.2) calls this on every expired index.
     *
     * @param indexId the index id to drop; never {@code null}, never blank
     * @return the verdict; never {@code null}
     */
    IndexAdminResult dropIndex(String indexId);
}
