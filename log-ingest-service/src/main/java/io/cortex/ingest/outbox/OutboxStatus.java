package io.cortex.ingest.outbox;

/**
 * Lifecycle states for {@link OutboxEvent} rows (P4.4 / ADR-0025).
 *
 * <p>Persisted as the {@code status VARCHAR(16)} column of
 * {@code outbox_events}. Transitions:</p>
 *
 * <ol>
 *   <li>Row is INSERTed with {@link #PENDING} by the per-row
 *       transactional writer alongside its sibling {@code raw_logs}
 *       row.</li>
 *   <li>The P4.4b poller publishes the payload via Spring Cloud
 *       Stream and transitions the row to {@link #PUBLISHED},
 *       stamping {@code published_at}.</li>
 *   <li>If publish fails after the configured maximum number of
 *       attempts (P4.4c), the row transitions to {@link #FAILED}
 *       and is routed to the DLQ binding.</li>
 * </ol>
 *
 * <p>The Postgres {@code CHECK} constraint
 * {@code status IN ('PENDING','PUBLISHED','FAILED')} mirrors this
 * enum and rejects any drift at the storage layer.</p>
 */
public enum OutboxStatus {

    /** Newly inserted; awaiting publish by the P4.4b poller. */
    PENDING,

    /** Successfully published to the binder; terminal state. */
    PUBLISHED,

    /** Maximum publish attempts exhausted; DLQ-routed (P4.4c). */
    FAILED;
}
