package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit tests for {@link OutboxPoller} (P4.4b / ADR-0026).
 *
 * <p>Mocks the {@link OutboxRepository} and {@link KafkaTemplate}
 * so the test runs without Postgres or Kafka. Verifies the three
 * boundary cases of the per-row publish loop:</p>
 *
 * <ul>
 *   <li>Master switch OFF skips the fetch entirely.</li>
 *   <li>Successful send marks the row PUBLISHED.</li>
 *   <li>Broker exception (sync throw OR failed future) bumps
 *       attempts, computes exponential backoff, persists a
 *       truncated error string, and leaves the row PENDING.</li>
 * </ul>
 */
class OutboxPollerTest {

    /** Sample CloudEvents source URI. */
    private static final String SOURCE = "/cortex/log-ingest-service";

    /** Sample CloudEvents type identifier. */
    private static final String TYPE = "io.cortex.logs.event.v1";

    /** Fixed wall-clock so backoff math is deterministic. */
    private static final Instant NOW = Instant.parse("2026-06-02T12:00:00Z");

    /** Mocked outbox repository. */
    private OutboxRepository repository;

    /** Mocked Kafka producer template. */
    @SuppressWarnings("unchecked")
    private KafkaTemplate<byte[], byte[]> kafkaTemplate;

    /** Real envelope builder; the test verifies its output passes through. */
    private CloudEventEnvelopeBuilder envelopeBuilder;

    /** Bound configuration tree under test. */
    private OutboxPollerProperties properties;

    /** Real Micrometer registry; counters verified via {@code .counter(...).count()}. */
    private MeterRegistry meterRegistry;

    /** SUT - recreated per test. */
    private OutboxPoller poller;

    /** Default constructor used by JUnit. */
    OutboxPollerTest() {
        // no state
    }

    /** Resets mocks and rewires the SUT before each test. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void initPoller() {
        this.repository = Mockito.mock(OutboxRepository.class);
        this.kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        this.properties = new OutboxPollerProperties(
                new OutboxPollerProperties.PollerProps(true, 1_000L, 100, 250L, 60_000L),
                new OutboxPollerProperties.CloudEventProps(SOURCE, TYPE));
        this.envelopeBuilder = new CloudEventEnvelopeBuilder(
                this.properties, Clock.fixed(NOW, ZoneOffset.UTC));
        this.meterRegistry = new SimpleMeterRegistry();
        this.poller = new OutboxPoller(
                this.repository,
                this.envelopeBuilder,
                this.kafkaTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC),
                this.properties,
                this.meterRegistry);
    }

    /**
     * Disabled master switch short-circuits before any repository
     * or broker calls.
     */
    @Test
    void disabledSwitchSkipsFetchAndPublish() {
        this.properties = new OutboxPollerProperties(
                new OutboxPollerProperties.PollerProps(false, 1_000L, 100, 250L, 60_000L),
                new OutboxPollerProperties.CloudEventProps(SOURCE, TYPE));
        this.poller = new OutboxPoller(
                this.repository,
                this.envelopeBuilder,
                this.kafkaTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC),
                this.properties,
                this.meterRegistry);

        this.poller.tick();

        verifyNoInteractions(this.repository);
        verifyNoInteractions(this.kafkaTemplate);
    }

    /**
     * Empty due-list does NOT call {@code KafkaTemplate.send} and
     * returns zero from {@link OutboxPoller#drainOnce()}.
     */
    @Test
    @SuppressWarnings("unchecked")
    void noDueRowsSkipsSend() {
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of());

        final int processed = this.poller.drainOnce();

        assertThat(processed).isZero();
        verify(this.kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    /**
     * Successful send marks the row PUBLISHED and bumps the
     * {@code cortex.ingest.outbox.published} counter.
     */
    @Test
    @SuppressWarnings("unchecked")
    void successfulSendMarksRowPublished() {
        final OutboxEvent row = pendingRow(1L);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(row));
        final SendResult<byte[], byte[]> ack = mock(SendResult.class);
        when(this.kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(ack));

        final int processed = this.poller.drainOnce();

        assertThat(processed).isEqualTo(1);
        verify(this.repository).markPublished(eq(1L), eq(NOW));
        verify(this.repository, never())
                .markFailureAndReschedule(anyLong(), anyInt(), any(), anyString());
        assertThat(this.meterRegistry.counter(OutboxPoller.METRIC_PUBLISHED).count())
                .isEqualTo(1.0d);
    }

    /**
     * A failed future (e.g. broker NACK) is treated as a publish
     * failure: the row is rescheduled, NOT marked PUBLISHED, and
     * the failed-counter is bumped once.
     */
    @Test
    @SuppressWarnings("unchecked")
    void failedFutureReschedulesRow() {
        final OutboxEvent row = pendingRow(2L);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(row));
        final CompletableFuture<SendResult<byte[], byte[]>> failed =
                CompletableFuture.failedFuture(new RuntimeException("broker-nack"));
        when(this.kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);

        this.poller.drainOnce();

        verify(this.repository, never()).markPublished(anyLong(), any());
        final ArgumentCaptor<Integer> attemptsCap = ArgumentCaptor.forClass(Integer.class);
        final ArgumentCaptor<Instant> nextCap = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        verify(this.repository).markFailureAndReschedule(
                eq(2L), attemptsCap.capture(), nextCap.capture(), errCap.capture());
        assertThat(attemptsCap.getValue()).isEqualTo(1);
        assertThat(nextCap.getValue()).isEqualTo(NOW.plusMillis(250L));
        assertThat(errCap.getValue()).contains("broker-nack");
        assertThat(this.meterRegistry.counter(OutboxPoller.METRIC_FAILED).count())
                .isEqualTo(1.0d);
    }

    /**
     * A synchronous broker exception is caught, formatted as
     * {@code <ClassName>: <message>}, persisted into
     * {@code last_error}, and the row is rescheduled with
     * exponential backoff based on its prior {@code attempts}
     * count.
     */
    @Test
    @SuppressWarnings("unchecked")
    void brokerExceptionReschedulesWithExponentialBackoff() {
        final OutboxEvent row = new OutboxEvent(
                3L,
                "cortex-dev",
                "evt-3",
                "{}",
                OutboxStatus.PENDING.name(),
                2,
                NOW.minusSeconds(5),
                "previous-failure",
                NOW.minusSeconds(10),
                null);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(row));
        when(this.kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new RuntimeException("broker-down"));

        this.poller.drainOnce();

        final ArgumentCaptor<Integer> attemptsCap = ArgumentCaptor.forClass(Integer.class);
        final ArgumentCaptor<Instant> nextCap = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        verify(this.repository).markFailureAndReschedule(
                eq(3L), attemptsCap.capture(), nextCap.capture(), errCap.capture());
        assertThat(attemptsCap.getValue()).isEqualTo(3);
        // attempts=3 -> shift=2 -> 250 * 4 = 1000ms
        assertThat(nextCap.getValue()).isEqualTo(NOW.plusMillis(1_000L));
        assertThat(errCap.getValue()).isEqualTo("RuntimeException: broker-down");
    }

    /**
     * Multiple due rows are processed in a single drain cycle and
     * each one's outcome is independent.
     */
    @Test
    @SuppressWarnings("unchecked")
    void mixedBatchProcessesEachRowIndependently() {
        final OutboxEvent ok = pendingRow(10L);
        final OutboxEvent bad = pendingRow(11L);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(ok, bad));
        final SendResult<byte[], byte[]> ack = mock(SendResult.class);
        when(this.kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(ack))
                .thenReturn(CompletableFuture.failedFuture(
                        new RuntimeException("bad-row")));

        final int processed = this.poller.drainOnce();

        assertThat(processed).isEqualTo(2);
        verify(this.repository).markPublished(eq(10L), eq(NOW));
        verify(this.repository).markFailureAndReschedule(
                eq(11L), eq(1), eq(NOW.plusMillis(250L)), anyString());
        assertThat(this.meterRegistry.counter(OutboxPoller.METRIC_PUBLISHED).count())
                .isEqualTo(1.0d);
        assertThat(this.meterRegistry.counter(OutboxPoller.METRIC_FAILED).count())
                .isEqualTo(1.0d);
    }

    /**
     * Builds a fresh PENDING outbox row with the supplied id for a happy-path test.
     *
     * @param id row identifier to embed in the synthetic outbox event
     * @return a fresh PENDING OutboxEvent
     */
    private static OutboxEvent pendingRow(final long id) {
        return new OutboxEvent(
                id,
                "cortex-dev",
                "evt-" + id,
                "{\"hello\":\"world\"}",
                OutboxStatus.PENDING.name(),
                0,
                NOW,
                null,
                NOW,
                null);
    }
}
