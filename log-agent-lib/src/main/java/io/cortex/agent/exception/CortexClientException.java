package io.cortex.agent.exception;

import java.io.Serial;

/**
 * Unchecked exception thrown by the CORTEX agent SDK on any unrecoverable
 * client-side failure (HTTP transport, serialization, illegal builder
 * configuration, etc.).
 *
 * <p>The SDK is fail-soft by design: most call sites catch and log this
 * exception instead of propagating it to the host application.</p>
 */
public class CortexClientException extends RuntimeException {

    /** Stable serial version id (this exception is rarely serialized). */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message only.
     *
     * @param message human-readable description of the failure
     */
    public CortexClientException(final String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and a wrapped cause.
     *
     * @param message human-readable description of the failure
     * @param cause   underlying cause; may be {@code null}
     */
    public CortexClientException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
