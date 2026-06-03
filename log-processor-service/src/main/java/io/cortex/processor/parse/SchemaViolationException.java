package io.cortex.processor.parse;

/**
 * Thrown by {@link SchemaValidator} when a successfully-parsed
 * {@link RawLogEvent} fails a schema-conformance check (P5.1).
 *
 * <p>Surfaced by the consumer as DLQ header
 * {@code x-failure-reason=schema_violation} per ADR-0027 contract.</p>
 */
public class SchemaViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new schema violation with the supplied field
     * name and reason.
     *
     * @param field  the offending field name (e.g. {@code "level"},
     *               {@code "ts"})
     * @param reason short failure reason (e.g.
     *               {@code "not in allowlist [TRACE,DEBUG,INFO,WARN,ERROR]"})
     */
    public SchemaViolationException(final String field, final String reason) {
        super(field + ": " + reason);
    }
}
