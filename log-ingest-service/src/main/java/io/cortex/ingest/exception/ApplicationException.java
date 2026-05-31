package io.cortex.ingest.exception;

import io.cortex.ingest.constants.ErrorCodes;

/**
 * Base unchecked exception for the log-ingest-service domain.
 *
 * <p>Carries a stable {@link ErrorCodes} value so the
 * {@link GlobalExceptionHandler} can map to the matching HTTP
 * status without coupling controllers to HTTP status enums (rule
 * A10.5).</p>
 */
public class ApplicationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Stable error code surfaced in the RFC 7807 problem body. */
    private final ErrorCodes errorCode;

    /**
     * Constructs the exception with an error code and message.
     *
     * @param errorCode stable error code; must not be {@code null}
     * @param message   human-readable detail; must not be {@code null}
     */
    public ApplicationException(final ErrorCodes errorCode, final String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Constructs the exception with an error code, message, and cause.
     *
     * @param errorCode stable error code; must not be {@code null}
     * @param message   human-readable detail; must not be {@code null}
     * @param cause     wrapped throwable; may be {@code null}
     */
    public ApplicationException(final ErrorCodes errorCode, final String message,
                                final Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable error code carried by this exception.
     *
     * @return error code
     */
    public ErrorCodes getErrorCode() {
        return this.errorCode;
    }
}
