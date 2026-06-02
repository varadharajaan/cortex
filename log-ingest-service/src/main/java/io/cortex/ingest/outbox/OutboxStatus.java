package io.cortex.ingest.outbox;

/**
 * Lifecycle states for {@link OutboxEvent} rows (P4.4 / ADR-0025 +
 * ADR-0027).
 *
 * <p>Persisted as the {@code status VARCHAR(16)} column of
 * {@code outbox_events}. Transitions:</p>
 *
 * <ol>
 *   <li>Row is INSERTed with {@link #PENDING} by the per-row
 *       transactional writer alongside its sibling {@code raw_logs}
 *       row.</li>
 *   <li>The P4.4b poller publishes the payload via Spring Kafka's
 *       {@code KafkaTemplate} and transitions the row to
 *       {@link #PUBLISHED}, stamping {@code published_at}.</li>
 *   <li>If publish fails repeatedly the poller bumps {@code attempts}
 *       and reschedules {@code next_attempt_at} with exponential
 *       backoff capped at {@code backoff-max-ms}. When the row would
 *       have been rescheduled at the cap (P4.4c retry-exhausted
 *       boundary per ADR-0027 D2), the poller routes the row to the
 *       DLQ topic {@code cortex.logs.events.v1.dlq} and transitions
 *       it to {@link #DEAD}.</li>
 * </ol>
 *
 * <p>The Postgres {@code CHECK} constraint
 * {@code status IN ('PENDING','PUBLISHED','FAILED','DEAD')} mirrors
 * this enum and rejects any drift at the storage layer.</p>
 */
public enum OutboxStatus {

    /** Newly inserted; awaiting publish by the P4.4b poller. */
    PENDING,

    /** Successfully published to the binder; terminal state. */
    PUBLISHED,

    /**
     * Legacy enum value retained for backwards-compatibility with rows
     * that may have been hand-flagged outside the poller. The
     * application code never writes {@code FAILED}; the
     * retry-exhausted path writes {@link #DEAD} instead.
     */
    FAILED,

    /**
     * Retry budget exhausted and the row was routed to the DLQ topic
     * {@code cortex.logs.events.v1.dlq} (P4.4c / ADR-0027). Terminal
     * state -- the poller skips DEAD rows forever (the partial index
     * {@code outbox_events_pending_idx} already excludes non-PENDING
     * rows so this is a no-op at the storage layer).
     */
    DEAD;
}
