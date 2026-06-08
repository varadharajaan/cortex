package io.cortex.gateway.dto.response;

import java.util.List;
import java.util.Map;

/**
 * Result of a {@code searchLogs} operation, returned byte-identically
 * by the REST surface ({@code GET /api/v1/logs/search}) and the GraphQL
 * surface ({@code searchLogs(input): LogSearchResult!}) (P9.1b /
 * ADR-0004 / ADR-0049).
 *
 * <p>Mirrors the P9.1a indexer response
 * ({@code io.cortex.indexer.controller.dto.SearchHttpResponse}): the
 * pre-limit {@code numHits} count plus the materialised {@code hits}
 * payload. Each hit is an opaque JSON object (a Quickwit document) so
 * the gateway does not couple itself to the indexer's document schema;
 * on the GraphQL surface {@code hits} is exposed through the
 * {@code JSON} scalar so both surfaces return the same raw objects (the
 * P9.1b parity contract).</p>
 *
 * @param numHits total hit count reported by the indexer (the pre-limit
 *                cardinality, not the length of the {@code hits} list)
 * @param hits    immutable copy of the hit payload list; never
 *                {@code null} (an empty list on a zero-hit query)
 */
public record LogSearchResult(long numHits, List<Map<String, Object>> hits) {

    /**
     * Compact constructor; defensively copies the supplied {@code hits}
     * list (or substitutes the canonical empty list for a {@code null})
     * so the published result cannot leak a mutable backing list across
     * threads.
     */
    public LogSearchResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
