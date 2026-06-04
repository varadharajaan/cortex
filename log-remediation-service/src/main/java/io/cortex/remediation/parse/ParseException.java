package io.cortex.remediation.parse;

/**
 * Checked exception raised by {@link AnomalyEnvelopeParser} when an
 * incoming record on {@code cortex.anomalies.v1} cannot be decoded
 * into an {@link AnomalyEvent} (P6.0 / ADR-0032 D4).
 *
 * <p>The {@link #reason()} accessor returns one of the
 * {@link FailureReason} allowlist values so the future P6.4 DLQ
 * publisher can stamp the {@code x-failure-reason} Kafka header
 * without re-deriving it from the exception message.</p>
 */
public class ParseException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Categorical reason this record could not be parsed. */
    private final FailureReason reason;

    /**
     * Construct a parse exception with no cause.
     *
     * @param reason  categorical {@link FailureReason}
     * @param message human-readable diagnostic
     */
    public ParseException(final FailureReason reason, final String message) {
        super(message);
        this.reason = reason;
    }

    /**
     * Construct a parse exception wrapping an upstream cause.
     *
     * @param reason  categorical {@link FailureReason}
     * @param message human-readable diagnostic
     * @param cause   upstream throwable (e.g. {@code IOException}
     *                from {@code JsonFormat.deserialize})
     */
    public ParseException(final FailureReason reason, final String message,
                          final Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * Get the categorical reason this record could not be parsed.
     *
     * @return the {@link FailureReason}
     */
    public FailureReason reason() {
        return this.reason;
    }
}
