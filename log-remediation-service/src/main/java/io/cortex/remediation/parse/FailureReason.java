package io.cortex.remediation.parse;

/**
 * Allowlist of failure reasons stamped onto a parse failure of a
 * record polled off {@code cortex.anomalies.v1} (P6.0 / ADR-0032 D4).
 *
 * <p>Fixed enum so the future {@code cortex.anomalies.v1.dlq} writer
 * (P6.4) can route + dashboard on a bounded tag set per Part 17.
 * The {@link #header} value is the byte-for-byte string written to
 * the {@code x-failure-reason} Kafka header so a downstream operator
 * can grep DLQ records without inspecting payload.</p>
 */
public enum FailureReason {

    /** CloudEvents envelope failed to decode (malformed JSON or wrong content-type). */
    INVALID_ENVELOPE("invalid_envelope"),
    /** Envelope decoded but {@code type} or {@code specversion} did not match the contract. */
    WRONG_TYPE("wrong_type"),
    /** Envelope decoded but the {@code data} block was missing or null. */
    MISSING_DATA("missing_data");

    /** Wire string written to the {@code x-failure-reason} Kafka header. */
    private final String header;

    /**
     * @param header the wire string to stamp on the
     *               {@code x-failure-reason} Kafka header
     */
    FailureReason(final String header) {
        this.header = header;
    }

    /**
     * Get the wire string for this failure reason.
     *
     * @return the {@code x-failure-reason} Kafka header value
     */
    public String header() {
        return this.header;
    }
}
