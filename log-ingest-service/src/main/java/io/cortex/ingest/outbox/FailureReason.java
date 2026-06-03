package io.cortex.ingest.outbox;

/**
 * Allowlist of short failure-reason categories used as the
 * {@code reason} tag on the {@code cortex.ingest.outbox.failed} and
 * {@code cortex.ingest.outbox.dlq} Micrometer counters AND as the
 * {@code x-failure-reason} Kafka header value on DLQ records
 * (P4.4c / ADR-0027 D4).
 *
 * <p>The set is intentionally small (~5 values) so Prometheus cardinality
 * stays bounded. Adding a new value requires an ADR-0027 amendment and a
 * dashboard update. Free-form exception messages are NOT used as tag
 * values; the exception's class name maps into the allowlist via
 * {@link #fromThrowable(Throwable)}.</p>
 */
public final class FailureReason {

    /** Broker did not acknowledge inside the send-timeout budget. */
    public static final String KAFKA_TIMEOUT = "kafka.timeout";

    /** Wrapped broker exception (NACK, leader-not-available, etc.). */
    public static final String KAFKA_EXECUTE = "kafka.execute";

    /** Producer thread interrupted while awaiting acknowledgment. */
    public static final String KAFKA_INTERRUPTED = "kafka.interrupted";

    /** Envelope build / serialize failed before the row reached the broker. */
    public static final String OUTBOX_POISON = "outbox.poison";

    /** Fallback bucket for any RuntimeException not in the allowlist above. */
    public static final String UNKNOWN = "unknown";

    /** Utility class; not instantiable. */
    private FailureReason() {
        // constants only
    }

    /**
     * Maps a throwable to an allowlist category. The mapping is
     * deliberately conservative -- unmapped exception classes fall to
     * {@link #UNKNOWN} so a misbehaving driver cannot leak a new tag
     * value.
     *
     * @param ex throwable observed by the poller during a publish attempt;
     *           may be {@code null}
     * @return short reason category from the allowlist
     */
    public static String fromThrowable(final Throwable ex) {
        if (ex == null) {
            return UNKNOWN;
        }
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return KAFKA_TIMEOUT;
        }
        if (ex instanceof InterruptedException) {
            return KAFKA_INTERRUPTED;
        }
        if (ex instanceof java.util.concurrent.ExecutionException) {
            return KAFKA_EXECUTE;
        }
        if (ex.getClass().getName().startsWith("org.apache.kafka")) {
            return KAFKA_EXECUTE;
        }
        return UNKNOWN;
    }
}
