package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.cloudevents.jackson.JsonFormat;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for {@link OutboxPoller} (P4.4b / ADR-0026 +
 * P4.4c / ADR-0027).
 *
 * <p>Mocks {@link OutboxRepository} and {@link OutboxEventPublisher}
 * so the test runs without Postgres or Kafka. Covers the four
 * branches of the per-row publish loop:</p>
 *
 * <ul>
 *   <li>Master switch OFF skips the fetch entirely.</li>
 *   <li>Successful send marks the row PUBLISHED and bumps the
 *       {@value OutboxMetrics#METRIC_PUBLISHED} counter tagged with
 *       {@code (topic, tenant_id)}.</li>
 *   <li>Publish exception with a row that has NOT yet hit the
 *       backoff cap: row stays PENDING, attempts++,
 *       {@value OutboxMetrics#METRIC_FAILED} counter bumped with
 *       {@code (topic, tenant_id, reason)} tags.</li>
 *   <li>Publish exception with a row that DID hit the backoff cap
 *       (P4.4c retry-exhausted boundary per ADR-0027 D2): row
 *       routed to DLQ via {@code publishDlq}, flipped to DEAD,
 *       {@value OutboxMetrics#METRIC_DLQ} counter bumped.</li>
 * </ul>
 */
class OutboxPollerTest {

    /** Sample CloudEvents source URI. */
    private static final String SOURCE = "/cortex/log-ingest-service";

    /** Sample CloudEvents type identifier. */
    private static final String TYPE = "io.cortex.logs.event.v1";

    /** Fixed wall-clock so backoff math is deterministic. */
    private static final Instant NOW = Instant.parse("2026-06-02T12:00:00Z");

    /** Sample tenant id shared across rows + counter-tag lookups. */
    private static final String TENANT = "cortex-dev";

    /** Mocked outbox repository. */
    private OutboxRepository repository;

    /** Mocked publisher seam (Kafka impl is exercised separately in its own test). */
    private OutboxEventPublisher publisher;

    /** Real envelope builder; the test verifies its output passes through. */
    private CloudEventEnvelopeBuilder envelopeBuilder;

    /** Bound configuration tree under test. */
    private OutboxPollerProperties properties;

    /** Real Micrometer registry; counters verified via {@code .counter(...).count()}. */
    private MeterRegistry meterRegistry;

    /** Tagged-counter helper backed by {@link #meterRegistry}. */
    private OutboxMetrics metrics;

    /** SUT - recreated per test. */
    private OutboxPoller poller;

    /** Default constructor used by JUnit. */
    OutboxPollerTest() {
        // no state
    }

    /** Resets mocks and rewires the SUT before each test. */
    @BeforeEach
    void initPoller() {
        this.repository = Mockito.mock(OutboxRepository.class);
        this.publisher = Mockito.mock(OutboxEventPublisher.class);
        this.properties = new OutboxPollerProperties(
                new OutboxPollerProperties.PollerProps(true, 1_000L, 100, 250L, 60_000L),
                new OutboxPollerProperties.CloudEventProps(SOURCE, TYPE));
        this.envelopeBuilder = new CloudEventEnvelopeBuilder(
                this.properties, Clock.fixed(NOW, ZoneOffset.UTC));
        this.meterRegistry = new SimpleMeterRegistry();
        this.metrics = new OutboxMetrics(this.meterRegistry);
        this.poller = new OutboxPoller(
                this.repository,
                this.envelopeBuilder,
                this.publisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                this.properties,
                this.metrics);
    }

    /**
     * Disabled master switch short-circuits before any repository
     * or publisher calls.
     */
    @Test
    void disabledSwitchSkipsFetchAndPublish() {
        this.properties = new OutboxPollerProperties(
                new OutboxPollerProperties.PollerProps(false, 1_000L, 100, 250L, 60_000L),
                new OutboxPollerProperties.CloudEventProps(SOURCE, TYPE));
        this.poller = new OutboxPoller(
                this.repository,
                this.envelopeBuilder,
                this.publisher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                this.properties,
                this.metrics);

        this.poller.tick();

        verifyNoInteractions(this.repository);
        verifyNoInteractions(this.publisher);
    }

    /**
     * Empty due-list does NOT call {@code publisher.publish} and
     * returns zero from {@link OutboxPoller#drainOnce()}.
     */
    @Test
    void noDueRowsSkipsSend() {
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of());

        final int processed = this.poller.drainOnce();

        assertThat(processed).isZero();
        verifyNoInteractions(this.publisher);
    }

    /**
     * Successful send marks the row PUBLISHED and bumps the
     * {@value OutboxMetrics#METRIC_PUBLISHED} counter tagged with
     * {@code (topic, tenant_id)}.
     */
    @Test
    void successfulSendMarksRowPublished() {
        final OutboxEvent row = pendingRow(1L);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(row));
        doNothing().when(this.publisher).publish(eq(row), any(byte[].class), anyString());

        final int processed = this.poller.drainOnce();

        assertThat(processed).isEqualTo(1);
        verify(this.repository).markPublished(eq(1L), eq(NOW));
        verify(this.repository, never())
                .markFailureAndReschedule(anyLong(), anyInt(), any(), anyString());
        verify(this.repository, never()).markDead(anyLong(), anyInt(), anyString());
        assertThat(publishedCount()).isEqualTo(1.0d);
    }

    /**
     * A non-exhausted publish failure bumps attempts, reschedules
     * the row, and increments the
     * {@value OutboxMetrics#METRIC_FAILED} counter with the
     * mapped reason. Row stays PENDING.
     */
    @Test
    void publishFailureNotYetExhaustedReschedulesRow() {
        final OutboxEvent row = pendingRow(2L);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(row));
        doThrow(new IllegalStateException("kafka send failed: TimeoutException",
                new java.util.concurrent.TimeoutException("ack-timeout")))
                .when(this.publisher).publish(eq(row), any(byte[].class), anyString());

        this.poller.drainOnce();

        verify(this.repository, never()).markPublished(anyLong(), any());
        verify(this.repository, never()).markDead(anyLong(), anyInt(), anyString());
        final ArgumentCaptor<Integer> attemptsCap = ArgumentCaptor.forClass(Integer.class);
        final ArgumentCaptor<Instant> nextCap = ArgumentCaptor.forClass(Instant.class);
        final ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        verify(this.repository).markFailureAndReschedule(
                eq(2L), attemptsCap.capture(), nextCap.capture(), errCap.capture());
        assertThat(attemptsCap.getValue()).isEqualTo(1);
        assertThat(nextCap.getValue()).isEqualTo(NOW.plusMillis(250L));
        assertThat(errCap.getValue()).contains("kafka send failed");
        assertThat(failedCount(FailureReason.KAFKA_TIMEOUT)).isEqualTo(1.0d);
        assertThat(dlqCount(FailureReason.KAFKA_TIMEOUT)).isZero();
    }

    /**
     * A publish failure on a row whose next backoff would hit the
     * cap routes the row to the DLQ, flips status to DEAD, and
     * bumps BOTH the failed counter (per-attempt) and the dlq
     * counter (terminal). The row's prior attempt count puts the
     * uncapped backoff at or above {@code backoff-max-ms}.
     */
    @Test
    void publishFailureRetryExhaustedRoutesRowToDlqAndMarksDead() {
        // attempts=9 -> shift=8 -> 250 * 256 = 64_000ms > 60_000ms cap, so
        // after this failure (newAttempts=9) the backoff is exhausted.
        // Wait -- isRetryExhausted runs on newAttempts (which is row.attempts()+1).
        // We want isRetryExhausted(newAttempts) to fire on this row's failure.
        // shift = newAttempts - 1; uncapped = 250 << shift.
        // For newAttempts=9 -> shift=8 -> uncapped=64_000 >= 60_000 cap -> true.
        // So pass row.attempts()=8 (newAttempts becomes 9).
        final OutboxEvent row = pendingRowWithAttempts(42L, 8);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(row));
        doThrow(new IllegalStateException("kafka send failed: ExecutionException",
                new java.util.concurrent.ExecutionException(
                        new RuntimeException("broker-nack"))))
                .when(this.publisher).publish(eq(row), any(byte[].class), anyString());
        doNothing().when(this.publisher).publishDlq(
                eq(row), any(byte[].class), anyString(),
                eq(OutboxPoller.DEFAULT_TOPIC), eq(FailureReason.KAFKA_EXECUTE));

        this.poller.drainOnce();

        verify(this.repository, never()).markPublished(anyLong(), any());
        verify(this.repository, never())
                .markFailureAndReschedule(anyLong(), anyInt(), any(), anyString());
        verify(this.repository).markDead(eq(42L), eq(9), anyString());
        assertThat(failedCount(FailureReason.KAFKA_EXECUTE)).isEqualTo(1.0d);
        assertThat(dlqCount(FailureReason.KAFKA_EXECUTE)).isEqualTo(1.0d);
    }

    /**
     * If the DLQ publish itself fails the row is kept PENDING with
     * bumped attempts so the next tick retries the full path; the
     * DLQ counter MUST NOT increment (no record landed on DLQ).
     */
    @Test
    void publishFailureWithFailingDlqLeavesRowPendingAndSkipsDlqCounter() {
        final OutboxEvent row = pendingRowWithAttempts(99L, 8);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(row));
        doThrow(new IllegalStateException("kafka send failed: TimeoutException",
                new java.util.concurrent.TimeoutException("ack-timeout")))
                .when(this.publisher).publish(eq(row), any(byte[].class), anyString());
        doThrow(new IllegalStateException("dlq broker down"))
                .when(this.publisher).publishDlq(
                        eq(row), any(byte[].class), anyString(),
                        eq(OutboxPoller.DEFAULT_TOPIC),
                        eq(FailureReason.KAFKA_TIMEOUT));

        this.poller.drainOnce();

        verify(this.repository, never()).markDead(anyLong(), anyInt(), anyString());
        final ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        verify(this.repository).markFailureAndReschedule(
                eq(99L), eq(9), any(), errCap.capture());
        assertThat(errCap.getValue()).startsWith("dlq-failed:");
        assertThat(failedCount(FailureReason.KAFKA_TIMEOUT)).isEqualTo(1.0d);
        assertThat(dlqCount(FailureReason.KAFKA_TIMEOUT)).isZero();
    }

    /**
     * Multiple due rows are processed in a single drain cycle and
     * each one's outcome is independent.
     */
    @Test
    void mixedBatchProcessesEachRowIndependently() {
        final OutboxEvent ok = pendingRow(10L);
        final OutboxEvent bad = pendingRow(11L);
        when(this.repository.findPendingDueForPublish(eq(NOW), anyInt()))
                .thenReturn(List.of(ok, bad));
        doNothing().when(this.publisher).publish(eq(ok), any(byte[].class), anyString());
        doThrow(new IllegalStateException("kafka send failed: ExecutionException",
                new java.util.concurrent.ExecutionException(
                        new RuntimeException("bad-row"))))
                .when(this.publisher).publish(eq(bad), any(byte[].class), anyString());

        final int processed = this.poller.drainOnce();

        assertThat(processed).isEqualTo(2);
        verify(this.repository).markPublished(eq(10L), eq(NOW));
        verify(this.repository).markFailureAndReschedule(
                eq(11L), eq(1), eq(NOW.plusMillis(250L)), anyString());
        assertThat(publishedCount()).isEqualTo(1.0d);
        assertThat(failedCount(FailureReason.KAFKA_EXECUTE)).isEqualTo(1.0d);
    }

    /**
     * Reads the {@value OutboxMetrics#METRIC_PUBLISHED} counter
     * tagged {@code (topic = DEFAULT_TOPIC, tenant_id = TENANT)}.
     *
     * @return total count for the tagged published counter
     */
    private double publishedCount() {
        return this.meterRegistry.counter(
                OutboxMetrics.METRIC_PUBLISHED,
                OutboxMetrics.TAG_TOPIC, OutboxPoller.DEFAULT_TOPIC,
                OutboxMetrics.TAG_TENANT, TENANT).count();
    }

    /**
     * Reads the {@value OutboxMetrics#METRIC_FAILED} counter tagged
     * {@code (topic = DEFAULT_TOPIC, tenant_id = TENANT, reason)}.
     *
     * @param reason failure-reason tag value
     * @return total count for the tagged failed counter
     */
    private double failedCount(final String reason) {
        return this.meterRegistry.counter(
                OutboxMetrics.METRIC_FAILED,
                OutboxMetrics.TAG_TOPIC, OutboxPoller.DEFAULT_TOPIC,
                OutboxMetrics.TAG_TENANT, TENANT,
                OutboxMetrics.TAG_REASON, reason).count();
    }

    /**
     * Reads the {@value OutboxMetrics#METRIC_DLQ} counter tagged
     * {@code (topic = DLQ_TOPIC, tenant_id = TENANT, reason)}.
     *
     * @param reason failure-reason tag value
     * @return total count for the tagged dlq counter
     */
    private double dlqCount(final String reason) {
        return this.meterRegistry.counter(
                OutboxMetrics.METRIC_DLQ,
                OutboxMetrics.TAG_TOPIC, KafkaOutboxPublisher.DLQ_TOPIC,
                OutboxMetrics.TAG_TENANT, TENANT,
                OutboxMetrics.TAG_REASON, reason).count();
    }

    /**
     * Builds a fresh PENDING outbox row with the supplied id for a happy-path test.
     *
     * @param id row identifier to embed in the synthetic outbox event
     * @return a fresh PENDING OutboxEvent (attempts=0)
     */
    private static OutboxEvent pendingRow(final long id) {
        return pendingRowWithAttempts(id, 0);
    }

    /**
     * Builds a PENDING outbox row with the supplied attempts count
     * so the retry-exhausted branch can be exercised on the FIRST
     * call to {@link OutboxPoller#drainOnce} without a multi-tick
     * setup. CloudEvent header content-type is hard-coded to
     * {@code application/cloudevents+json} via {@link JsonFormat}.
     *
     * @param id       row identifier
     * @param attempts prior attempts count (newAttempts will be {@code attempts + 1})
     * @return a PENDING OutboxEvent matching the supplied attempts
     */
    private static OutboxEvent pendingRowWithAttempts(final long id, final int attempts) {
        return new OutboxEvent(
                id,
                TENANT,
                "evt-" + id,
                "{\"hello\":\"world\",\"contentType\":\"" + JsonFormat.CONTENT_TYPE + "\"}",
                OutboxStatus.PENDING.name(),
                attempts,
                NOW,
                null,
                NOW,
                null);
    }
}
