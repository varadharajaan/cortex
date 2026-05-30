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

    /** Fallback for any unexpected internal failure. */
    INTERNAL_ERROR;
}
