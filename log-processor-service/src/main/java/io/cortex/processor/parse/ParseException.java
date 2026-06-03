package io.cortex.processor.parse;

/**
 * Thrown by {@link LogEventParser} when the inbound Kafka record
 * value cannot be decoded into a {@link RawLogEvent} (P5.1).
 *
 * <p>Surfaced by the consumer as DLQ header
 * {@code x-failure-reason=parse_error} per ADR-0027 contract.</p>
 */
public class ParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new parse exception with the supplied message
     * and cause.
     *
     * @param message short failure description (the cause's class +
     *                message); cited in the consumer's WARN log
     * @param cause   the underlying Jackson / CloudEvents exception
     */
    public ParseException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
