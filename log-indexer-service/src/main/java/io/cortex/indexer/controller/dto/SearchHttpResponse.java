package io.cortex.indexer.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * Outbound HTTP response body for a successful search
 * ({@code 200 OK}) on {@code POST /api/v1/search}
 * (P9.1a / ADR-0042 Amendment 1).
 *
 * <p>Exposes only the operator-facing payload from the internal
 * {@link io.cortex.indexer.search.SearchResult} verdict: the
 * pre-limit {@code numHits} count and the materialised
 * {@code hits} list. The verdict's internal mechanics
 * ({@code backend}, {@code outcome}, {@code reason}) are NOT
 * leaked onto the wire on the happy path; on a failure verdict the
 * controller surfaces those as the HTTP status plus an RFC 7807
 * problem body instead.</p>
 *
 * @param numHits total hit count reported by the search backend
 *                (the pre-limit cardinality, not the length of the
 *                {@code hits} list)
 * @param hits    immutable copy of the hit payload list; never
 *                {@code null} (an empty list on a zero-hit query)
 */
public record SearchHttpResponse(long numHits, List<Map<String, Object>> hits) {

    /**
     * Compact constructor; defensively copies the supplied
     * {@code hits} list (or substitutes the canonical empty list
     * for a {@code null}) so the published response cannot leak a
     * caller's mutable backing list.
     */
    public SearchHttpResponse {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
