package io.cortex.ingest.outbox;

import io.cortex.ingest.persistence.RawLog;
import io.cortex.ingest.persistence.RawLogRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-row transactional writer that commits a {@link RawLog} row
 * and its sibling {@link OutboxEvent} row in the SAME transaction
 * (P4.4a / ADR-0025 / strict rule B10.1).
 *
 * <p>The {@code @Transactional(propagation = REQUIRES_NEW)}
 * boundary lives on its own bean (not on
 * {@code IngestServiceImpl.persistBatchWithMasking}) so that
 * Spring AOP intercepts every call site, the batch loop in
 * {@code IngestServiceImpl} stays under a single auto-commit
 * outer context, and a single cold-path dedupe rollback affects
 * ONLY one row pair rather than the whole batch.</p>
 *
 * <p>Rollback rules: {@link DuplicateKeyException} (and
 * {@link DbActionExecutionException} wrapping it) raised by the
 * {@code raw_logs} INSERT causes the entire {@code REQUIRES_NEW}
 * transaction to roll back, so the sibling {@code outbox_events}
 * row is never visible to the P4.4b poller. The exception
 * propagates to {@code IngestServiceImpl} which catches it,
 * increments the cold-path dedupe counter, and continues the
 * batch loop with the next entry. Any other exception type also
 * rolls back; the caller is responsible for handling it.</p>
 *
 * <p>The sibling outbox event is built INSIDE the transactional
 * method by the injected {@link OutboxEventFactory}; this keeps
 * the caller's surface area to a single {@link RawLog} argument
 * and guarantees the {@code (rawLog, outboxEvent)} pair is
 * always derived from the same source of truth without forcing
 * {@code IngestServiceImpl} to grow another constructor parameter
 * (Checkstyle ParameterNumber ceiling).</p>
 */
@Service
public class RawLogTransactionalWriter {

    /** Spring Data JDBC gateway to {@code raw_logs}. */
    private final RawLogRepository rawLogRepository;

    /** Spring Data JDBC gateway to {@code outbox_events}. */
    private final OutboxRepository outboxRepository;

    /** Builds the sibling {@link OutboxEvent} for each {@link RawLog} insert. */
    private final OutboxEventFactory outboxEventFactory;

    /**
     * Constructs the writer with both repositories and the sibling
     * outbox factory.
     *
     * @param rawLogRepository   {@link RawLog} repository; must not
     *                           be {@code null}
     * @param outboxRepository   {@link OutboxEvent} repository; must
     *                           not be {@code null}
     * @param outboxEventFactory builds the sibling {@link OutboxEvent}
     *                           from the raw_logs row about to be
     *                           inserted; must not be {@code null}
     */
    public RawLogTransactionalWriter(final RawLogRepository rawLogRepository,
                                     final OutboxRepository outboxRepository,
                                     final OutboxEventFactory outboxEventFactory) {
        this.rawLogRepository = rawLogRepository;
        this.outboxRepository = outboxRepository;
        this.outboxEventFactory = outboxEventFactory;
    }

    /**
     * Inserts the raw_logs row and its sibling outbox row in the
     * same REQUIRES_NEW transaction. On any exception the entire
     * pair-INSERT rolls back.
     *
     * @param raw aggregate to insert into {@code raw_logs}; must
     *            not be {@code null}. The sibling outbox row is
     *            built from the same {@code raw} via the injected
     *            {@link OutboxEventFactory} so the two rows carry
     *            identical {@code (tenant_id, event_id)}
     * @throws DuplicateKeyException        cold-path dedupe; the
     *                                      {@code raw_logs} UNIQUE
     *                                      constraint rejected this
     *                                      {@code (tenant_id, event_id)}
     *                                      pair, so neither row commits
     * @throws DbActionExecutionException   when Spring Data JDBC
     *                                      wraps the underlying
     *                                      {@link DuplicateKeyException}
     *                                      (relational module choice);
     *                                      callers should unwrap with
     *                                      {@code instanceof}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeRawLogAndOutbox(final RawLog raw) {
        final OutboxEvent outbox = this.outboxEventFactory.toPendingEvent(raw);
        this.rawLogRepository.save(raw);
        this.outboxRepository.save(outbox);
    }
}
