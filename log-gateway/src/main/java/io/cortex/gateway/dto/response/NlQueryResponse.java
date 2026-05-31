package io.cortex.gateway.dto.response;

/**
 * Natural-language query response body returned by
 * {@code POST /api/v1/query/nl} (B20.1, P3.3 / ADR-0018).
 *
 * <p>The {@code logql} string is a syntactically-valid LogQL query that
 * the future {@code log-query-service} (P7) will execute against the
 * indexer. {@code confidence} is the model's self-reported confidence
 * in {@code [0.0, 1.0]}; callers may surface it to end users or reject
 * answers below a threshold. {@code explanation} is a one-paragraph
 * justification of the chosen filters, intended for UI display.</p>
 *
 * @param logql       LogQL query string; non-blank, ASCII, max 1024 chars
 * @param confidence  model-reported confidence in {@code [0.0, 1.0]}
 * @param explanation human-readable explanation; max 2048 chars
 */
public record NlQueryResponse(
        String logql,
        double confidence,
        String explanation) {
}
