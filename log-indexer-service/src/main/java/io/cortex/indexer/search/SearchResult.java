package io.cortex.indexer.search;

import java.util.List;
import java.util.Map;

/**
 * Immutable verdict returned by a {@link LogSearchClient} for a
 * single search call (P7.4 / ADR-0042 D1).
 *
 * <p>Read-path counterpart to
 * {@link io.cortex.indexer.admin.IndexAdminResult}: carries the
 * three pieces of metric-tag information ({@code backend},
 * {@code outcome}, {@code reason}) plus the search-specific
 * payload ({@code numHits}, immutable {@code hits} list).</p>
 *
 * <p>Outcome strings are bounded by the {@code OUTCOME_*}
 * constants below so the {@code cortex.indexer.search_total}
 * counter tag cardinality stays under control (Part 17 allowlist:
 * only {@code backend}, {@code outcome}, {@code tenant_id} tags
 * are emitted).</p>
 *
 * @param backend one of {@code noop} or {@code quickwit}
 * @param outcome one of {@code noop}, {@code search_ok},
 *                {@code transient_failure}, {@code permanent_failure}
 * @param reason  short human-readable explanation; may be blank
 *                on the happy path
 * @param numHits total hit count surfaced by Quickwit (the
 *                pre-limit cardinality; not the length of the
 *                {@code hits} list)
 * @param hits    immutable copy of the hit payload list; empty
 *                on every non-{@code search_ok} verdict
 */
public record SearchResult(String backend, String outcome,
                           String reason, long numHits,
                           List<Map<String, Object>> hits) {

    /** Backend value emitted by {@link NoopLogSearchClient}. */
    public static final String BACKEND_NOOP = "noop";

    /**
     * Backend value emitted by the Quickwit HTTP search adapter
     * (P7.4+).
     */
    public static final String BACKEND_QUICKWIT = "quickwit";

    /** Outcome value: noop backend returned without contacting Quickwit. */
    public static final String OUTCOME_NOOP = "noop";

    /** Outcome value: Quickwit accepted the query and returned hits. */
    public static final String OUTCOME_SEARCH_OK = "search_ok";

    /** Outcome value: downstream returned 4xx / unrecoverable error. */
    public static final String OUTCOME_PERMANENT_FAILURE = "permanent_failure";

    /** Outcome value: downstream timed out / 5xx / 429 / transport. */
    public static final String OUTCOME_TRANSIENT_FAILURE = "transient_failure";

    /**
     * Compact constructor; defensively copies the supplied
     * {@code hits} list (or replaces a {@code null} with the
     * canonical empty list) so the published record cannot leak
     * the caller's mutable backing list.
     */
    public SearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /**
     * Convenience factory for the "no action taken" verdict
     * returned by {@link NoopLogSearchClient}.
     *
     * @param reason short human-readable explanation
     * @return a {@link SearchResult} with {@code backend=noop},
     *         {@code outcome=noop}, zero hits
     */
    public static SearchResult noop(final String reason) {
        return new SearchResult(BACKEND_NOOP, OUTCOME_NOOP,
                reason == null ? "" : reason, 0L, List.of());
    }

    /**
     * Convenience factory for the happy search verdict.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param numHits Quickwit's reported total hit count
     * @param hits    materialised hits; may be empty
     * @return a {@link SearchResult} with
     *         {@code outcome=search_ok}, blank reason
     */
    public static SearchResult searchOk(final String backend,
                                        final long numHits,
                                        final List<Map<String, Object>> hits) {
        return new SearchResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_SEARCH_OK, "",
                Math.max(0L, numHits),
                hits == null ? List.of() : hits);
    }

    /**
     * Convenience factory for a retriable downstream failure
     * (5xx / 429 / IOException / timeout). Per ADR-0042 D5 the
     * SPI MUST NOT throw on these.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param reason  categorical explanation, e.g. {@code
     *                quickwit:5xx:500}
     * @return a {@link SearchResult} with
     *         {@code outcome=transient_failure}, zero hits
     */
    public static SearchResult transientFailure(final String backend,
                                                final String reason) {
        return new SearchResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_TRANSIENT_FAILURE,
                reason == null ? "" : reason, 0L, List.of());
    }

    /**
     * Convenience factory for a non-retriable downstream failure
     * (e.g. 404 missing index, 400 bad query, 401 unauthorised,
     * tenant-prefix mismatch).
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param reason  categorical explanation, e.g. {@code
     *                quickwit:4xx:404} or {@code
     *                quickwit:tenant-mismatch}
     * @return a {@link SearchResult} with
     *         {@code outcome=permanent_failure}, zero hits
     */
    public static SearchResult permanentFailure(final String backend,
                                                final String reason) {
        return new SearchResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_PERMANENT_FAILURE,
                reason == null ? "" : reason, 0L, List.of());
    }
}
