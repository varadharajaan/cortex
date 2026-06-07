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

    /**
     * Apply a retention policy to the index identified by
     * {@link IndexSpec#indexId()} (P7.2 / ADR-0040 D1).
     *
     * <p>Implementations translate the supplied {@link RetentionPolicy}
     * into a backend-native cutoff request: the Quickwit HTTP adapter
     * POSTs a {@code DeleteQuery} to
     * {@code /api/v1/&lt;indexId&gt;/delete-tasks} with
     * {@code end_timestamp = now - policy.ttl()} (epoch seconds);
     * the noop default records the call and returns
     * {@link IndexAdminResult#OUTCOME_NOOP}.</p>
     *
     * <p>This is the per-call alternative to dropping the whole
     * index ({@link #dropIndex(String)}): documents older than the
     * cutoff are scheduled for deletion server-side while the index
     * itself stays available for ingest. Operators wire a scheduler
     * (P7.3+) to call this on every tenant index at a documented
     * cadence.</p>
     *
     * @param spec   the target index spec; never {@code null}
     * @param policy the retention policy to apply; never {@code null}
     * @return the verdict; never {@code null}
     */
    IndexAdminResult applyRetention(IndexSpec spec, RetentionPolicy policy);
}
