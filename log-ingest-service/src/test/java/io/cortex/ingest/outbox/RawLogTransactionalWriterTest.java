package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.ingest.persistence.RawLog;
import io.cortex.ingest.persistence.RawLogRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

/**
 * Unit tests for {@link RawLogTransactionalWriter} (P4.4a /
 * ADR-0025).
 *
 * <p>Verifies the writer commits both saves in the correct order
 * (raw_logs first, outbox second) so the cold-path UNIQUE
 * constraint on {@code raw_logs} aborts the transaction BEFORE the
 * outbox INSERT, and propagates duplicate-key exceptions to the
 * caller untouched so {@code IngestServiceImpl} can absorb them.</p>
 */
class RawLogTransactionalWriterTest {

    /** Mocked raw-logs repository. */
    private RawLogRepository rawLogRepository;

    /** Mocked outbox repository. */
    private OutboxRepository outboxRepository;

    /** SUT - recreated per test for isolation. */
    private RawLogTransactionalWriter writer;

    /** Default constructor used by JUnit. */
    RawLogTransactionalWriterTest() {
        // no state
    }

    /** Resets mocks and rewires the SUT before each test. */
    @BeforeEach
    void initWriter() {
        this.rawLogRepository = Mockito.mock(RawLogRepository.class);
        this.outboxRepository = Mockito.mock(OutboxRepository.class);
        this.writer = new RawLogTransactionalWriter(
                this.rawLogRepository,
                this.outboxRepository,
                new OutboxEventFactory(new ObjectMapper()));
    }

    /**
     * Happy path: raw_logs save runs before outbox save so that a
     * raw_logs UNIQUE constraint can abort the transaction before
     * the outbox row is even attempted.
     */
    @Test
    void writesRawLogBeforeOutboxOnHappyPath() {
        final RawLog raw = sampleRaw();

        this.writer.writeRawLogAndOutbox(raw);

        final InOrder order = inOrder(this.rawLogRepository, this.outboxRepository);
        order.verify(this.rawLogRepository).save(raw);
        order.verify(this.outboxRepository).save(any(OutboxEvent.class));
    }

    /**
     * A {@link DuplicateKeyException} from the raw_logs INSERT
     * MUST propagate to the caller untouched, and the outbox
     * INSERT MUST NOT be attempted; the production
     * {@code @Transactional(REQUIRES_NEW)} boundary then rolls
     * back any partial state.
     */
    @Test
    void duplicateKeyOnRawLogAbortsBeforeOutbox() {
        final RawLog raw = sampleRaw();
        when(this.rawLogRepository.save(any(RawLog.class)))
                .thenThrow(new DuplicateKeyException("uk vio"));

        assertThatThrownBy(() -> this.writer.writeRawLogAndOutbox(raw))
                .isInstanceOf(DuplicateKeyException.class);

        verify(this.rawLogRepository).save(raw);
        verify(this.outboxRepository, never()).save(any(OutboxEvent.class));
    }

    /**
     * Constructs a representative {@link RawLog} fixture.
     *
     * @return populated raw_logs aggregate
     */
    private static RawLog sampleRaw() {
        return new RawLog(
                null,
                "cortex-dev",
                "evt-xyz",
                Instant.parse("2026-06-02T10:00:00Z"),
                "INFO",
                "cortex-it",
                "writer-fixture",
                Map.of("env", "test"),
                null,
                Instant.parse("2026-06-02T10:00:01Z"));
    }
}
