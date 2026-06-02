package io.cortex.ingest.outbox;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC aggregate root for the {@code outbox_events}
 * table (P4.4a / ADR-0025).
 *
 * <p>Mirrors the V3 Flyway schema column-for-column. Persisted by
 * {@link OutboxRepository} from inside
 * {@link RawLogTransactionalWriter#writeRawLogAndOutbox} so the
 * raw_logs row and its sibling outbox row commit (or roll back)
 * atomically per-row.</p>
 *
 * <p>{@code payload} is opaque JSON text in PR-1; the P4.4b poller
 * wraps it in a CloudEvents 1.0 envelope at publish time.
 * {@code status} starts as {@link OutboxStatus#PENDING}; the
 * poller advances it to {@link OutboxStatus#PUBLISHED} or
 * {@link OutboxStatus#FAILED}. {@code attempts} starts at zero and
 * is incremented by each publish failure; {@code next_attempt_at}
 * starts at insert time and is pushed out by an exponential
 * backoff on failure (P4.4b/P4.4c).</p>
 *
 * <p>{@code id} stays {@code null} on insert so Spring Data JDBC
 * issues an INSERT (versus an UPDATE for an attached id) and the
 * Postgres {@code BIGSERIAL} sequence supplies the value.</p>
 *
 * @param id            surrogate primary key; {@code null} on
 *                      insert, populated by the database
 * @param tenantId      resolved tenant identifier (same value as
 *                      the sibling {@code raw_logs.tenant_id})
 * @param eventId       server-computed dedupe key (same value as
 *                      the sibling {@code raw_logs.event_id})
 * @param payload       opaque JSON text to publish; wrapped in a
 *                      CloudEvents 1.0 envelope by the P4.4b
 *                      poller at publish time
 * @param status        lifecycle state; persisted as a string
 *                      matching {@link OutboxStatus#name()}
 * @param attempts      number of publish attempts so far; starts
 *                      at zero, incremented on every failure
 * @param nextAttemptAt earliest UTC instant at which the poller
 *                      may attempt to publish this row; starts at
 *                      insert time, pushed out by exponential
 *                      backoff on failure
 * @param lastError     short description of the most recent
 *                      publish failure ({@code <ExceptionClass>:
 *                      <message>}); {@code null} until the first
 *                      failure
 * @param createdAt     insert timestamp pinned by the database
 * @param publishedAt   publish-success timestamp pinned by the
 *                      poller; {@code null} while the row is
 *                      {@link OutboxStatus#PENDING} or
 *                      {@link OutboxStatus#FAILED}
 */
@Table("outbox_events")
public record OutboxEvent(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("event_id") String eventId,
        @Column("payload") String payload,
        @Column("status") String status,
        @Column("attempts") int attempts,
        @Column("next_attempt_at") Instant nextAttemptAt,
        @Column("last_error") String lastError,
        @Column("created_at") Instant createdAt,
        @Column("published_at") Instant publishedAt) {

    /**
     * Builds a fresh PENDING outbox row for the supplied
     * {@code (tenantId, eventId, payload)} tuple. {@code attempts}
     * starts at zero, {@code nextAttemptAt} starts at
     * {@code receivedAt} so the poller picks it up on the next
     * sweep, {@code lastError} and {@code publishedAt} are
     * {@code null}, {@code createdAt} mirrors {@code receivedAt}
     * for stable ordering with the sibling raw_logs row.
     *
     * @param tenantId   resolved tenant id; must not be {@code null}
     * @param eventId    server-computed event id; must not be
     *                   {@code null}
     * @param payload    opaque JSON text to publish; must not be
     *                   {@code null}
     * @param receivedAt server-side acceptance timestamp; reused for
     *                   {@code nextAttemptAt} and {@code createdAt}
     *                   so the outbox row's lineage is anchored to
     *                   the same moment as the raw_logs row
     * @return new {@link OutboxEvent} ready for
     *         {@code OutboxRepository.save(...)}
     */
    public static OutboxEvent pending(final String tenantId,
                                      final String eventId,
                                      final String payload,
                                      final Instant receivedAt) {
        return new OutboxEvent(
                null,
                tenantId,
                eventId,
                payload,
                OutboxStatus.PENDING.name(),
                0,
                receivedAt,
                null,
                receivedAt,
                null);
    }
}
