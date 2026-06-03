package io.cortex.processor.parse;

/**
 * Consumer-side failure-reason allowlist used as the
 * {@code x-failure-reason} Kafka header value on records published
 * to {@code cortex.logs.events.v1.dlq} (P5.1 / ADR-0027 contract
 * mirror).
 *
 * <p>Distinct from {@code io.cortex.ingest.outbox.FailureReason}
 * (P4.4c) which covers producer-side outbox publish failures; this
 * class covers consumer-side parse + validate failures. The
 * allowlist + header naming convention are inherited from
 * ADR-0027 so dashboards and alert routing rules can treat the two
 * sources symmetrically.</p>
 */
public final class FailureReason {

    /** CloudEvent envelope or data JSON failed to deserialize. */
    public static final String PARSE_ERROR = "parse_error";

    /** Parsed {@link RawLogEvent} failed schema conformance. */
    public static final String SCHEMA_VIOLATION = "schema_violation";

    /** Constants only; cannot be instantiated. */
    private FailureReason() {
        // constants only
    }
}
