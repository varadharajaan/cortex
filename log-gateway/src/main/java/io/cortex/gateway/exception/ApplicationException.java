package io.cortex.gateway.exception;

import io.cortex.gateway.constants.ErrorCodes;
import java.io.Serial;
import lombok.Getter;

/**
 * Root unchecked exception for every gateway-side failure (rule A10.3).
 *
 * <p>Carries an {@link ErrorCodes} value so the global handler can map
 * to a stable HTTP status without string comparisons. Service code
 * should throw a subclass rather than instantiating this class
 * directly.</p>
 */
@Getter
public class ApplicationException extends RuntimeException {

    /** Serial version UID. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Stable error code for client switching. */
    private final ErrorCodes errorCode;

    /**
     * Builds an application exception with a stable error code.
     *
     * @param errorCode stable error code; must not be {@code null}
     * @param message   human-readable message safe to surface to clients
     */
    public ApplicationException(final ErrorCodes errorCode, final String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Builds an application exception with a cause.
     *
     * @param errorCode stable error code; must not be {@code null}
     * @param message   human-readable message safe to surface to clients
     * @param cause     underlying cause; may be {@code null}
     */
    public ApplicationException(final ErrorCodes errorCode, final String message, final Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
