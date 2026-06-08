package io.cortex.gateway.constants;

/**
 * Stable error codes returned in RFC 7807 {@code ProblemDetail} responses.
 *
 * <p>Every {@link io.cortex.gateway.exception.ApplicationException}
 * carries one of these values. Clients should switch on the code, not
 * on the human-readable message (rule A10.3, A10.7).</p>
 */
public enum ErrorCodes {

    /** Generic validation failure; details are in the {@code violations} array. */
    VALIDATION_FAILED,

    /** Request was syntactically valid but logically rejected. */
    BAD_REQUEST,

    /** Authentication is required and was not provided or was invalid. */
    UNAUTHENTICATED,

    /** Caller is authenticated but lacks permission for the resource. */
    FORBIDDEN,

    /** Target resource does not exist. */
    NOT_FOUND,

    /** Downstream dependency was unavailable; retry may succeed. */
    UPSTREAM_UNAVAILABLE,

    /** Caller exceeded their rate-limit bucket (B5, RFC 6585 section 4). */
    RATE_LIMITED,

    /** Caller exceeded the per-feature NL-query sub-bucket (B20 + B5, P3.3 / ADR-0018). */
    NL_QUERY_RATE_LIMITED,

    /** NL-to-LogQL model output failed schema or content validation (P3.3 / ADR-0018). */
    NL_QUERY_INVALID,

    /** NL-to-LogQL model refused to answer (safety / policy marker in response). */
    NL_QUERY_REFUSED,

    /** NL-to-LogQL upstream model call failed (Ollama unreachable, timeout, parse error). */
    NL_QUERY_UPSTREAM_FAILED,

    /** Caller exceeded the per-feature {@code searchLogs} sub-bucket (B5, P9.1b / ADR-0049). */
    SEARCH_LOGS_RATE_LIMITED,

    /** searchLogs request was rejected as permanently unprocessable by the indexer (P9.1b). */
    SEARCH_LOGS_INVALID,

    /** searchLogs downstream (log-indexer-service / Quickwit) was unavailable or failed transiently (P9.1b). */
    SEARCH_LOGS_UPSTREAM_FAILED,

    /** Fallback for any unexpected internal failure. */
    INTERNAL_ERROR;
}
