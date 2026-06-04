package io.cortex.remediation.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.remediation.dispatch.DispatchResult;
import io.cortex.remediation.dispatch.RemediationDispatcher;
import io.cortex.remediation.metrics.RemediationMetrics;
import io.cortex.remediation.parse.AnomalyEnvelopeParser;
import io.cortex.remediation.parse.AnomalyEvent;
import io.cortex.remediation.parse.FailureReason;
import io.cortex.remediation.parse.ParseException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Unit tests for {@link AnomalyConsumer} happy + failure branches
 * (P6.0 / ADR-0032).
 *
 * <p>Drives the consumer directly with hand-built {@link ConsumerRecord}
 * instances + a {@code Mockito} dispatcher so the assertions stay
 * deterministic without needing a Kafka broker. The Testcontainers
 * round-trip lives in {@code AnomalyConsumerKafkaIT}.</p>
 */
class AnomalyConsumerTest {

    /**
     * Builds a deterministic {@link ConsumerRecord} for the production topic with the given value.
     *
     * @param value the record value to wrap
     * @return a fresh {@link ConsumerRecord} pinned to partition 0 offset 0
     */
    private static ConsumerRecord<byte[], byte[]> recordOf(final byte[] value) {
        return new ConsumerRecord<>("cortex.anomalies.v1", 0, 0L,
                "k".getBytes(StandardCharsets.UTF_8), value);
    }

    /** A null / empty record value must short-circuit straight to ack with no parse + no dispatch. */
    @Test
    void emptyRecordIsAckedWithoutDispatch() {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        final RemediationDispatcher dispatcher = Mockito.mock(RemediationDispatcher.class);
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dispatcher, metrics);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(recordOf(new byte[0]), ack);

        verify(ack, times(1)).acknowledge();
        verify(dispatcher, never()).dispatch(any());
    }

    /**
     * A {@link ParseException} on the parser must ack + skip dispatch
     * (poison-message guard, P6.0 placeholder for DLQ in P6.4).
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void parseFailureIsAckedWithoutDispatch() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        when(parser.parse(any(byte[].class)))
                .thenThrow(new ParseException(FailureReason.INVALID_ENVELOPE, "bad"));
        final RemediationDispatcher dispatcher = Mockito.mock(RemediationDispatcher.class);
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dispatcher, metrics);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(
                recordOf("any-payload".getBytes(StandardCharsets.UTF_8)),
                ack);

        verify(ack, times(1)).acknowledge();
        verify(dispatcher, never()).dispatch(any());
    }

    /**
     * Happy path: a parsed event reaches the dispatcher and the
     * dispatched counter ticks with the correct tags.
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void happyPathDispatchesAndTicksCounter() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        final AnomalyEvent event = new AnomalyEvent(
                "evt-1", "tenant-x", "HIGH", "reason",
                java.time.Instant.now(), "ERROR", "svc", "msg");
        when(parser.parse(any(byte[].class))).thenReturn(event);
        final RemediationDispatcher dispatcher = Mockito.mock(RemediationDispatcher.class);
        when(dispatcher.dispatch(eq(event))).thenReturn(
                new DispatchResult(false, DispatchResult.CHANNEL_NOOP,
                        DispatchResult.OUTCOME_SKIPPED, "no-op"));
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dispatcher, metrics);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(
                recordOf("any-payload".getBytes(StandardCharsets.UTF_8)),
                ack);

        verify(ack, times(1)).acknowledge();
        final Counter counter = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", "noop")
                .tag("outcome", "skipped")
                .tag("tenant_id", "tenant-x")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    /**
     * A null {@code DispatchResult} from a non-conforming dispatcher
     * must fall back to a synthetic skipped result.
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void nullDispatchResultDoesNotCrashConsumer() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        final AnomalyEvent event = new AnomalyEvent(
                "evt-2", "tenant-y", "HIGH", "reason",
                java.time.Instant.now(), "ERROR", "svc", "msg");
        when(parser.parse(any(byte[].class))).thenReturn(event);
        final RemediationDispatcher dispatcher = Mockito.mock(RemediationDispatcher.class);
        when(dispatcher.dispatch(any())).thenReturn(null);
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dispatcher, metrics);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(
                recordOf("any-payload".getBytes(StandardCharsets.UTF_8)),
                ack);

        verify(ack, times(1)).acknowledge();
        // Fallback to a synthetic skipped DispatchResult still ticks
        // the counter so dashboards never see a silent drop.
        final Counter counter = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", "noop")
                .tag("outcome", "skipped")
                .tag("tenant_id", "tenant-y")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    /**
     * A {@link RuntimeException} from the dispatcher must be caught + acked
     * so the consumer cannot stall.
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void dispatcherThrowsIsCaughtAndAcked() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        final AnomalyEvent event = new AnomalyEvent(
                "evt-3", "tenant-z", "HIGH", "reason",
                java.time.Instant.now(), "ERROR", "svc", "msg");
        when(parser.parse(any(byte[].class))).thenReturn(event);
        final RemediationDispatcher dispatcher = Mockito.mock(RemediationDispatcher.class);
        when(dispatcher.dispatch(any()))
                .thenThrow(new RuntimeException("boom"));
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dispatcher, metrics);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(
                recordOf("any-payload".getBytes(StandardCharsets.UTF_8)),
                ack);

        verify(ack, times(1)).acknowledge();
        verify(dispatcher, times(1)).dispatch(any());
        // Counter not ticked because the dispatcher threw before
        // returning a DispatchResult; the catch-all logs + acks but
        // does not invent tag values.
        final Counter counter = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", "noop")
                .tag("outcome", "skipped")
                .tag("tenant_id", "tenant-z")
                .counter();
        assertThat(counter).isNull();
    }

    /** The 3-arg {@link ParseException} ctor must surface both the {@link FailureReason} and the chained cause. */
    @Test
    void parseExceptionConstructorWithCauseExposesReason() {
        final ParseException ex = new ParseException(FailureReason.WRONG_TYPE,
                "wrong", new IllegalStateException("nested"));

        assertThat(ex.reason()).isEqualTo(FailureReason.WRONG_TYPE);
        assertThat(ex.getCause()).isInstanceOf(IllegalStateException.class);
    }

    /** Wire-contract guard: {@link FailureReason#header()} strings are the DLQ public surface and cannot drift. */
    @Test
    void failureReasonHeaderValuesAreStable() {
        // Wire-contract guard: changing these strings is a breaking
        // change for the future P6.4 DLQ consumer.
        assertThat(FailureReason.INVALID_ENVELOPE.header()).isEqualTo("invalid_envelope");
        assertThat(FailureReason.WRONG_TYPE.header()).isEqualTo("wrong_type");
        assertThat(FailureReason.MISSING_DATA.header()).isEqualTo("missing_data");
    }
}
