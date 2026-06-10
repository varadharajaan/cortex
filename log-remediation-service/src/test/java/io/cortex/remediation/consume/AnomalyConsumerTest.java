package io.cortex.remediation.consume;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.remediation.anomaly.AnomalyReadModelWriter;
import io.cortex.remediation.dlq.AnomalyDlqPublisher;
import io.cortex.remediation.engine.RemediationEngine;
import io.cortex.remediation.parse.AnomalyEnvelopeParser;
import io.cortex.remediation.parse.AnomalyEvent;
import io.cortex.remediation.parse.FailureReason;
import io.cortex.remediation.parse.ParseException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Unit tests for the remediation Kafka consumer boundary.
 */
class AnomalyConsumerTest {

    private static final byte[] KEY = "k".getBytes(StandardCharsets.UTF_8);

    /**
     * A null / empty record value must publish to DLQ, ack, and skip engine.
     */
    @Test
    void emptyRecordIsDlqedAndAckedWithoutEngine() {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        final AnomalyDlqPublisher dlq = Mockito.mock(AnomalyDlqPublisher.class);
        final AnomalyReadModelWriter writer = Mockito.mock(AnomalyReadModelWriter.class);
        final RemediationEngine engine = Mockito.mock(RemediationEngine.class);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dlq, writer, engine);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        final ConsumerRecord<byte[], byte[]> record = recordOf(new byte[0]);

        consumer.onMessage(record, ack);

        verify(dlq, times(1)).publish(eq(record),
                eq(FailureReason.INVALID_ENVELOPE), eq("empty payload"));
        verify(ack, times(1)).acknowledge();
        verify(writer, never()).persistFailOpen(any());
        verify(engine, never()).handle(any());
    }

    /**
     * Parse failures must publish the original record to the anomaly DLQ.
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void parseFailureIsDlqedAndAckedWithoutEngine() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        when(parser.parse(any(byte[].class)))
                .thenThrow(new ParseException(FailureReason.INVALID_ENVELOPE, "bad"));
        final AnomalyDlqPublisher dlq = Mockito.mock(AnomalyDlqPublisher.class);
        final AnomalyReadModelWriter writer = Mockito.mock(AnomalyReadModelWriter.class);
        final RemediationEngine engine = Mockito.mock(RemediationEngine.class);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dlq, writer, engine);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);
        final ConsumerRecord<byte[], byte[]> record =
                recordOf("any-payload".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(record, ack);

        verify(dlq, times(1)).publish(eq(record),
                eq(FailureReason.INVALID_ENVELOPE), eq("bad"));
        verify(ack, times(1)).acknowledge();
        verify(writer, never()).persistFailOpen(any());
        verify(engine, never()).handle(any());
    }

    /**
     * A valid anomaly is passed to the remediation engine and acked.
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void happyPathInvokesEngineAndAcks() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        final AnomalyEvent event = event("evt-1", "tenant-x");
        when(parser.parse(any(byte[].class))).thenReturn(event);
        final AnomalyDlqPublisher dlq = Mockito.mock(AnomalyDlqPublisher.class);
        final AnomalyReadModelWriter writer = Mockito.mock(AnomalyReadModelWriter.class);
        final RemediationEngine engine = Mockito.mock(RemediationEngine.class);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dlq, writer, engine);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(recordOf("payload".getBytes(StandardCharsets.UTF_8)), ack);

        verify(writer, times(1)).persistFailOpen(eq(event));
        verify(engine, times(1)).handle(eq(event));
        verify(dlq, never()).publish(any(), any(), any());
        verify(ack, times(1)).acknowledge();
    }

    /**
     * The read-model branch is fail-open: even if persistence throws,
     * the remediation engine still handles the anomaly.
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void readModelWriterThrowsStillInvokesEngineAndAcks() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        final AnomalyEvent event = event("evt-read-model-fail", "tenant-rm");
        when(parser.parse(any(byte[].class))).thenReturn(event);
        final AnomalyDlqPublisher dlq = Mockito.mock(AnomalyDlqPublisher.class);
        final AnomalyReadModelWriter writer = Mockito.mock(AnomalyReadModelWriter.class);
        Mockito.doThrow(new RuntimeException("db down"))
                .when(writer).persistFailOpen(any());
        final RemediationEngine engine = Mockito.mock(RemediationEngine.class);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dlq, writer, engine);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(recordOf("payload".getBytes(StandardCharsets.UTF_8)), ack);

        verify(writer, times(1)).persistFailOpen(eq(event));
        verify(engine, times(1)).handle(eq(event));
        verify(ack, times(1)).acknowledge();
    }

    /**
     * Engine exceptions are caught and acked so one bad record cannot stall the consumer.
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void engineThrowsIsCaughtAndAcked() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        final AnomalyEvent event = event("evt-2", "tenant-y");
        when(parser.parse(any(byte[].class))).thenReturn(event);
        final AnomalyDlqPublisher dlq = Mockito.mock(AnomalyDlqPublisher.class);
        final AnomalyReadModelWriter writer = Mockito.mock(AnomalyReadModelWriter.class);
        final RemediationEngine engine = Mockito.mock(RemediationEngine.class);
        Mockito.doThrow(new RuntimeException("boom")).when(engine).handle(any());
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dlq, writer, engine);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(recordOf("payload".getBytes(StandardCharsets.UTF_8)), ack);

        verify(writer, times(1)).persistFailOpen(eq(event));
        verify(engine, times(1)).handle(eq(event));
        verify(ack, times(1)).acknowledge();
    }

    /**
     * If the DLQ publisher itself fails, the poison input is still acked.
     *
     * @throws Exception on mock setup failure
     */
    @Test
    void dlqPublisherThrowsIsCaughtAndAcked() throws Exception {
        final AnomalyEnvelopeParser parser = Mockito.mock(AnomalyEnvelopeParser.class);
        when(parser.parse(any(byte[].class)))
                .thenThrow(new ParseException(FailureReason.WRONG_TYPE, "wrong"));
        final AnomalyDlqPublisher dlq = Mockito.mock(AnomalyDlqPublisher.class);
        Mockito.doThrow(new RuntimeException("dlq down"))
                .when(dlq).publish(any(), any(), any());
        final AnomalyReadModelWriter writer = Mockito.mock(AnomalyReadModelWriter.class);
        final RemediationEngine engine = Mockito.mock(RemediationEngine.class);
        final AnomalyConsumer consumer = new AnomalyConsumer(parser, dlq, writer, engine);
        final Acknowledgment ack = Mockito.mock(Acknowledgment.class);

        consumer.onMessage(recordOf("payload".getBytes(StandardCharsets.UTF_8)), ack);

        verify(ack, times(1)).acknowledge();
        verify(writer, never()).persistFailOpen(any());
        verify(engine, never()).handle(any());
    }

    private static ConsumerRecord<byte[], byte[]> recordOf(final byte[] value) {
        return new ConsumerRecord<>("cortex.anomalies.v1", 0, 0L, KEY, value);
    }

    private static AnomalyEvent event(final String eventId, final String tenantId) {
        return new AnomalyEvent(eventId, tenantId, "HIGH", "reason",
                java.time.Instant.now(), "ERROR", "svc", "msg");
    }
}
