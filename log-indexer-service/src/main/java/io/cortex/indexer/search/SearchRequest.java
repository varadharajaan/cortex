package io.cortex.indexer.search;

/**
 * Immutable input record describing a tenant-scoped Quickwit
 * search request (P7.4 / ADR-0042 D2).
 *
 * <p>Carries the four fields the {@link LogSearchClient} SPI needs
 * to fan out the call:</p>
 * <ul>
 *   <li>{@code tenantId} -- tenant the request belongs to;
 *       stamped on the {@code cortex.indexer.search_total}
 *       counter and used by the adapter's tenant-prefix
 *       guardrail (ADR-0042 D3).</li>
 *   <li>{@code indexId} -- the Quickwit index id to query
 *       (e.g. {@code cortex-tenantA-v1}). MUST start with
 *       {@code cortex-<tenantId>-} or the adapter returns a
 *       permanent {@code quickwit:tenant-mismatch} verdict.</li>
 *   <li>{@code query} -- the Quickwit query string forwarded
 *       verbatim into the {@code POST /api/v1/{indexId}/search}
 *       JSON body's {@code query} field.</li>
 *   <li>{@code maxHits} -- the upper bound on hits to return.
 *       Must be strictly positive (a zero / negative ceiling
 *       is meaningless and would be an obvious foot-gun if
 *       silently allowed through).</li>
 * </ul>
 *
 * <p>The cross-field tenant-prefix invariant is intentionally
 * NOT validated in the compact constructor: per ADR-0042 D6 the
 * SPI returns the mismatch as a normal {@code permanent_failure}
 * verdict so the caller can metric + alert on it, rather than as
 * an unhandled exception. The compact-ctor only blocks the
 * obviously-broken cases (null / blank / non-positive).</p>
 *
 * @param tenantId tenant id; never {@code null}, never blank
 * @param indexId  Quickwit index id; never {@code null}, never blank
 * @param query    Quickwit query string; never {@code null},
 *                 never blank
 * @param maxHits  upper bound on hits returned; strictly positive
 */
public record SearchRequest(String tenantId, String indexId,
                            String query, int maxHits) {

    /**
     * Compact validator-style canonical constructor. Defends
     * against null / blank string fields and non-positive
     * {@code maxHits} so downstream HTTP/JSON code paths can skip
     * the same defensive checks.
     *
     * @throws IllegalArgumentException when any string field is
     *                                  {@code null} or blank, or
     *                                  when {@code maxHits} is not
     *                                  strictly positive
     */
    public SearchRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                    "tenantId must not be blank");
        }
        if (indexId == null || indexId.isBlank()) {
            throw new IllegalArgumentException(
                    "indexId must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(
                    "query must not be blank");
        }
        if (maxHits <= 0) {
            throw new IllegalArgumentException(
                    "maxHits must be strictly positive; got " + maxHits);
        }
    }
}
