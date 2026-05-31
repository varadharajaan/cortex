package io.cortex.ingest.constants;

/**
 * Stable error-code enumeration surfaced in RFC 7807 problem bodies
 * via the {@code errorCode} extension field.
 *
 * <p>Mirrors the gateway pattern (see
 * {@code io.cortex.gateway.constants.ErrorCodes}) so cross-service
 * clients can pattern-match on a single uniform vocabulary.</p>
 */
public enum ErrorCodes {

    /** Bean Validation / parse failure on inbound request. */
    VALIDATION_FAILED,

    /** Generic malformed request (non-validation reason). */
    BAD_REQUEST,

    /** Caller authentication missing or invalid. */
    UNAUTHENTICATED,

    /** Caller authenticated but lacking permission. */
    FORBIDDEN,

    /** Requested resource does not exist. */
    NOT_FOUND,

    /** Upstream dependency (DB / queue / Redis) unavailable. */
    UPSTREAM_UNAVAILABLE,

    /** Rate-limit ceiling reached for the caller / tenant. */
    RATE_LIMITED,

    /** Catch-all internal error. */
    INTERNAL_ERROR
}
