package io.cortex.ingest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.agent.LogEntry;
import io.cortex.agent.LogLevel;
import io.cortex.ingest.dedupe.IdempotencyDedupeService;
import io.cortex.ingest.dto.request.IngestBatchRequest;
import io.cortex.ingest.dto.response.IngestAcceptedResponse;
import io.cortex.ingest.persistence.RawLog;
import io.cortex.ingest.persistence.RawLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbAction;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;

/**
 * Unit tests for {@link IngestServiceImpl} that drive the dedupe
 * absorption branches not reachable through the
 * {@link io.cortex.ingest.IngestPersistenceIT IT} happy path
 * (P4.1 / LD3 / Rule 12.5 / 13.4).
 *
 * <p>Two branches are exercised here:</p>
 * <ul>
 *   <li>{@link RawLogRepository#save(Object)} throwing a bare
 *       {@link DuplicateKeyException} - kept as a fallback because
 *       a future Spring Data JDBC release could unwrap the cause
 *       before it reaches the service.</li>
 *   <li>{@link RawLogRepository#save(Object)} throwing a
 *       {@link DbActionExecutionException} whose cause is NOT a
 *       {@link DuplicateKeyException} - must be rethrown so the
 *       caller sees the real failure.</li>
 * </ul>
 */
class IngestServiceImplTest {

    /** Fixed clock so the {@code receivedAt} timestamp is deterministic. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    /** Tenant id used by every test. */
    private static final String TENANT = "cortex-dev";

    /** Mocked repository. */
    private RawLogRepository repository;

    /** In-memory Micrometer registry; recreated per test for clean counter values. */
    private MeterRegistry registry;

    /** SUT - recreated per test for isolation. */
    private IngestServiceImpl service;

    /** Default constructor used by JUnit. */
    IngestServiceImplTest() {
        // no state; per-test setup in initService()
    }

    /** Resets mocks and rewires the SUT before each test. */
    @BeforeEach
    void initService() {
        this.repository = Mockito.mock(RawLogRepository.class);
        this.registry = new SimpleMeterRegistry();
        this.service = new IngestServiceImpl(FIXED_CLOCK, this.repository, new ObjectMapper(),
                Optional.empty(), this.registry);
    }

    /**
     * Bare {@link DuplicateKeyException} thrown from
     * {@link RawLogRepository#save(Object)} is absorbed silently;
     * the response still mirrors the inbound entry count.
     */
    @Test
    void bareDuplicateKeyExceptionIsAbsorbed() {
        when(this.repository.save(any(RawLog.class)))
                .thenThrow(new DuplicateKeyException("constraint vio"));

        final IngestBatchRequest request = singleEntryBatch("entry-1");
        final IngestAcceptedResponse response =
                this.service.acceptBatch(request, TENANT, null);

        assertThat(response.receivedCount()).isEqualTo(1);
        verify(this.repository, times(1)).save(any(RawLog.class));
    }

    /**
     * A {@link DbActionExecutionException} whose cause IS a
     * {@link DuplicateKeyException} is unwrapped and absorbed; the
     * response still mirrors the inbound entry count.
     */
    @Test
    void wrappedDuplicateKeyExceptionIsAbsorbed() {
        final DbActionExecutionException wrapped = new DbActionExecutionException(
                stubAction(), new DuplicateKeyException("uk vio"));
        when(this.repository.save(any(RawLog.class))).thenThrow(wrapped);

        final IngestBatchRequest request = singleEntryBatch("entry-wrapped");
        final IngestAcceptedResponse response =
                this.service.acceptBatch(request, TENANT, null);

        assertThat(response.receivedCount()).isEqualTo(1);
        verify(this.repository, times(1)).save(any(RawLog.class));
    }

    /**
     * A {@link DbActionExecutionException} whose cause is NOT a
     * {@link DuplicateKeyException} must propagate unchanged so the
     * {@link io.cortex.ingest.exception.GlobalExceptionHandler}
     * surfaces it as 500.
     */
    @Test
    void nonDuplicateDbActionExceptionIsRethrown() {
        final DbActionExecutionException ex = new DbActionExecutionException(
                stubAction(), new IllegalStateException("connection reset"));
        when(this.repository.save(any(RawLog.class))).thenThrow(ex);

        final IngestBatchRequest request = singleEntryBatch("entry-fail");

        assertThatThrownBy(() -> this.service.acceptBatch(request, TENANT, null))
                .isSameAs(ex);
    }

    /**
     * When the dedupe service is wired and reports a hot-path hit
     * (replay within TTL), {@link IngestServiceImpl#acceptBatch}
     * MUST short-circuit before touching the repository and still
     * return a {@code receivedCount} equal to
     * {@code entries.size()}.
     */
    @Test
    void hotPathHitShortCircuitsPersistence() {
        final IdempotencyDedupeService dedupe = Mockito.mock(IdempotencyDedupeService.class);
        when(dedupe.claim(TENANT, "idem-1")).thenReturn(false);
        this.service = new IngestServiceImpl(FIXED_CLOCK, this.repository, new ObjectMapper(),
                Optional.of(dedupe), this.registry);

        final IngestBatchRequest request = singleEntryBatch("entry-hot-path");
        final IngestAcceptedResponse response =
                this.service.acceptBatch(request, TENANT, "idem-1");

        assertThat(response.receivedCount()).isEqualTo(1);
        verify(this.repository, Mockito.never()).save(any(RawLog.class));
        verify(dedupe).claim(TENANT, "idem-1");
    }

    /**
     * When the dedupe service claims the key successfully (fresh
     * batch), persistence MUST run normally and the repository
     * sees one save per entry.
     */
    @Test
    void hotPathMissProceedsWithPersistence() {
        final IdempotencyDedupeService dedupe = Mockito.mock(IdempotencyDedupeService.class);
        when(dedupe.claim(TENANT, "idem-2")).thenReturn(true);
        this.service = new IngestServiceImpl(FIXED_CLOCK, this.repository, new ObjectMapper(),
                Optional.of(dedupe), this.registry);

        final IngestBatchRequest request = singleEntryBatch("entry-fresh");
        final IngestAcceptedResponse response =
                this.service.acceptBatch(request, TENANT, "idem-2");

        assertThat(response.receivedCount()).isEqualTo(1);
        verify(this.repository, times(1)).save(any(RawLog.class));
        verify(dedupe).claim(TENANT, "idem-2");
    }

    /**
     * The hot-path layer MUST be skipped entirely when the
     * {@code Idempotency-Key} header is absent; persistence falls
     * through to the cold-path UNIQUE backstop with no Redis call.
     */
    @Test
    void hotPathSkippedWhenIdempotencyKeyAbsent() {
        final IdempotencyDedupeService dedupe = Mockito.mock(IdempotencyDedupeService.class);
        this.service = new IngestServiceImpl(FIXED_CLOCK, this.repository, new ObjectMapper(),
                Optional.of(dedupe), this.registry);

        final IngestBatchRequest request = singleEntryBatch("entry-no-idem");
        this.service.acceptBatch(request, TENANT, null);

        verify(this.repository, times(1)).save(any(RawLog.class));
        Mockito.verifyNoInteractions(dedupe);
    }

    /**
     * The PII contained in {@code entry.message()} MUST be masked
     * by {@link io.cortex.agent.pii.PiiMasker} BEFORE the row is
     * handed to the repository (D4 / spec Sec 5.3 / LD4
     * second-layer mask). Captures the saved {@link RawLog} and
     * asserts the persisted message is the masked output, not the
     * original.
     */
    @Test
    void piiInMessageIsMaskedBeforePersistence() {
        final IngestBatchRequest request =
                singleEntryBatch("user alice@example.com just logged in");
        this.service.acceptBatch(request, TENANT, null);

        final ArgumentCaptor<RawLog> captor = ArgumentCaptor.forClass(RawLog.class);
        verify(this.repository).save(captor.capture());
        assertThat(captor.getValue().message())
                .isEqualTo("user <email> just logged in");
    }

    /**
     * Two entries with DIFFERENT PII that masks to the SAME token
     * (e.g. {@code alice@example.com} and {@code bob@example.com}
     * both become {@code <email>}) MUST still receive DISTINCT
     * {@code event_id}s, because the event-id hash is computed
     * against the ORIGINAL message; otherwise the cold-path UNIQUE
     * constraint would falsely dedupe the second entry.
     */
    @Test
    void eventIdComputedAgainstOriginalMessageNotMasked() {
        final LogEntry alice = new LogEntry(
                Instant.parse("2026-06-01T11:00:00Z"),
                LogLevel.INFO, "cortex-it", "user alice@example.com", Map.of());
        final LogEntry bob = new LogEntry(
                Instant.parse("2026-06-01T11:00:00Z"),
                LogLevel.INFO, "cortex-it", "user bob@example.com", Map.of());
        final IngestBatchRequest request = new IngestBatchRequest(List.of(alice, bob));

        this.service.acceptBatch(request, TENANT, null);

        final ArgumentCaptor<RawLog> captor = ArgumentCaptor.forClass(RawLog.class);
        verify(this.repository, times(2)).save(captor.capture());
        final List<RawLog> saved = captor.getAllValues();
        assertThat(saved.get(0).eventId()).isNotEqualTo(saved.get(1).eventId());
        assertThat(saved.get(0).message()).isEqualTo("user <email>");
        assertThat(saved.get(1).message()).isEqualTo("user <email>");
    }

    /**
     * On a hot-path hit, the
     * {@code cortex.ingest.dedupe.hits{path=hot}} counter MUST be
     * incremented by the FULL entry count of the absorbed batch
     * (every entry of the replay is conceptually deduped).
     */
    @Test
    void hotPathHitIncrementsHotDedupeCounterByEntryCount() {
        final IdempotencyDedupeService dedupe = Mockito.mock(IdempotencyDedupeService.class);
        when(dedupe.claim(TENANT, "idem-metric-hot")).thenReturn(false);
        this.service = new IngestServiceImpl(FIXED_CLOCK, this.repository, new ObjectMapper(),
                Optional.of(dedupe), this.registry);

        final LogEntry one = new LogEntry(
                Instant.parse("2026-06-01T11:00:00Z"),
                LogLevel.INFO, "cortex-it", "msg-1", Map.of());
        final LogEntry two = new LogEntry(
                Instant.parse("2026-06-01T11:00:01Z"),
                LogLevel.INFO, "cortex-it", "msg-2", Map.of());
        this.service.acceptBatch(new IngestBatchRequest(List.of(one, two)),
                TENANT, "idem-metric-hot");

        assertThat(this.registry.counter("cortex.ingest.dedupe.hits", "path", "hot")
                .count()).isEqualTo(2.0d);
        assertThat(this.registry.counter("cortex.ingest.dedupe.hits", "path", "cold")
                .count()).isEqualTo(0.0d);
    }

    /**
     * When the cold-path UNIQUE constraint absorbs a duplicate
     * (via {@link DuplicateKeyException} or wrapped in
     * {@link DbActionExecutionException}), the
     * {@code cortex.ingest.dedupe.hits{path=cold}} counter MUST
     * be incremented once per absorbed row.
     */
    @Test
    void coldPathDuplicateIncrementsColdDedupeCounter() {
        when(this.repository.save(any(RawLog.class)))
                .thenThrow(new DuplicateKeyException("uk vio"));

        this.service.acceptBatch(singleEntryBatch("entry-cold"), TENANT, null);

        assertThat(this.registry.counter("cortex.ingest.dedupe.hits", "path", "cold")
                .count()).isEqualTo(1.0d);
        assertThat(this.registry.counter("cortex.ingest.dedupe.hits", "path", "hot")
                .count()).isEqualTo(0.0d);
    }

    /**
     * The {@code cortex.ingest.mask.applied} counter MUST be
     * incremented by the TOTAL number of PII substitutions made by
     * {@link io.cortex.agent.pii.PiiMasker} across every entry in
     * the batch. One entry with two emails increments the counter
     * by 2.
     */
    @Test
    void maskAppliedCounterTracksTotalSubstitutions() {
        this.service.acceptBatch(
                singleEntryBatch("alice@example.com cc bob@example.com"),
                TENANT, null);

        assertThat(this.registry.counter("cortex.ingest.mask.applied").count())
                .isEqualTo(2.0d);
    }

    /**
     * Builds a single-entry batch request anchored to a fixed
     * timestamp so the SHA-256 event id is deterministic.
     *
     * @param message body of the single log entry
     * @return populated batch request
     */
    private static IngestBatchRequest singleEntryBatch(final String message) {
        final LogEntry entry = new LogEntry(
                Instant.parse("2026-06-01T11:00:00Z"),
                LogLevel.INFO,
                "cortex-it",
                message,
                Map.of("env", "test"));
        return new IngestBatchRequest(List.of(entry));
    }

    /**
     * Returns a benign stub for the {@link DbAction} that
     * {@link DbActionExecutionException} requires; the action itself
     * is never inspected by the service code.
     *
     * @return any non-null {@link DbAction}
     */
    private static DbAction<?> stubAction() {
        return Mockito.mock(DbAction.class);
    }
}
