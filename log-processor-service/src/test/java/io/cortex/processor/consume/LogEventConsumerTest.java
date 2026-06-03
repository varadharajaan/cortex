package io.cortex.processor.consume;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.processor.classify.AnomalyClassifier;
import io.cortex.processor.classify.Classification;
import io.cortex.processor.metrics.ProcessorMetrics;
import io.cortex.processor.parse.FailureReason;
import io.cortex.processor.parse.LogEventParser;
import io.cortex.processor.parse.ParseException;
import io.cortex.processor.parse.RawLogEvent;
import io.cortex.processor.parse.SchemaValidator;
import io.cortex.processor.parse.SchemaViolationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Unit test for {@link LogEventConsumer} that exercises the three
 * pipeline branches without a real broker (P5.1).
 *
 * <p>Branches covered:</p>
 * <ol>
 *   <li>happy path: parse + validate + classify succeed; metrics
 *       tick + ack</li>
 *   <li>parse failure: parser throws -> DLQ + ack +
 *       validator never invoked</li>
 *   <li>schema violation: validator throws -> DLQ + ack +
 *       classifier never invoked</li>
 *   <li>null payload: skip + ack</li>
 * </ol>
 */
class LogEventConsumerTest {

    private LogEventParser parser;
    private SchemaValidator validator;
    private AnomalyClassifier classifier;
    private ProcessorMetrics metrics;
    private DlqPublisher dlqPublisher;
    private LogEventConsumer consumer;

    /** Resets all mocks + builds a fresh consumer under test. */
    @BeforeEach
    void setUp() {
        this.parser = mock(LogEventParser.class);
        this.validator = mock(SchemaValidator.class);
        this.classifier = mock(AnomalyClassifier.class);
        this.metrics = new ProcessorMetrics(new SimpleMeterRegistry());
        this.dlqPublisher = mock(DlqPublisher.class);
        this.consumer = new LogEventConsumer(this.parser, this.validator,
                this.classifier, this.metrics, this.dlqPublisher);
    }

    /** Happy path: parse + validate + classify, then ack. */
    @Test
    void happyPathParsesValidatesClassifiesAndAcks() {
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(Classification.none());

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1, 2, 3}), ack);

        verify(this.validator).validate(event);
        verify(this.classifier).classify(event);
        verify(ack).acknowledge();
        verify(this.dlqPublisher, never()).publish(any(), any(), any());
    }

    /** Parser throws -> DLQ publish + ack + downstream skipped. */
    @Test
    void parseFailureRoutesToDlqAndAcks() {
        final ParseException ex = new ParseException("bad envelope",
                new RuntimeException());
        when(this.parser.parse(any(byte[].class))).thenThrow(ex);

        final Acknowledgment ack = mock(Acknowledgment.class);
        final ConsumerRecord<byte[], byte[]> record = record(new byte[]{1});
        this.consumer.onMessage(record, ack);

        verify(this.dlqPublisher).publish(record, FailureReason.PARSE_ERROR, ex);
        verify(this.validator, never()).validate(any());
        verify(this.classifier, never()).classify(any());
        verify(ack).acknowledge();
    }

    /** Validator throws -> DLQ publish + ack + classifier skipped. */
    @Test
    void schemaViolationRoutesToDlqAndAcks() {
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        final SchemaViolationException ex =
                new SchemaViolationException("level", "not allowed");
        org.mockito.Mockito.doThrow(ex).when(this.validator).validate(event);

        final Acknowledgment ack = mock(Acknowledgment.class);
        final ConsumerRecord<byte[], byte[]> record = record(new byte[]{1});
        this.consumer.onMessage(record, ack);

        verify(this.dlqPublisher).publish(record, FailureReason.SCHEMA_VIOLATION, ex);
        verify(this.classifier, never()).classify(any());
        verify(ack).acknowledge();
    }

    /** Null payload short-circuits to ack without touching parser. */
    @Test
    void nullPayloadIsSkippedAndAcked() {
        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(null), ack);

        verify(this.parser, never()).parse(any(byte[].class));
        verify(this.dlqPublisher, never()).publish(any(), any(), any());
        verify(ack).acknowledge();
    }

    /** Anomaly verdict still acks; publish path is P5.4 follow-up. */
    @Test
    void anomalyVerdictIsLoggedButNotPublishedYet() {
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event))
                .thenReturn(new Classification(true, "HIGH", "spike"));

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1}), ack);

        // P5.4 will add the cortex.anomalies.v1 publish path; for
        // P5.1 we just ack to keep the consumer moving.
        verify(ack).acknowledge();
        verify(this.dlqPublisher, never()).publish(eq(null), any(), any());
    }

    /** Catch-all RuntimeException after validate is logged + acked. */
    @Test
    void postValidateRuntimeExceptionIsSwallowedAndAcked() {
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event))
                .thenThrow(new RuntimeException("classifier exploded"));

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1}), ack);

        // Catch-all: log + ack so the consumer cannot loop on a
        // classifier defect; verify by reaching ack despite the
        // exception.
        verify(ack).acknowledge();
        verify(this.dlqPublisher, never()).publish(any(), any(), any());
    }

    /**
     * Builds a stock {@link RawLogEvent} for the consumer paths.
     *
     * @return well-formed event
     */
    private static RawLogEvent sample() {
        return new RawLogEvent("cortex-dev", "evt-1", Instant.now(),
                "INFO", "checkout", "hello", Map.of(), "idk-1", Instant.now());
    }

    /**
     * Wraps the supplied value in a synthetic Kafka consumer record.
     *
     * @param value record value bytes (may be null)
     * @return synthetic record on the production topic
     */
    private static ConsumerRecord<byte[], byte[]> record(final byte[] value) {
        return new ConsumerRecord<>("cortex.logs.events.v1", 0, 0L, null, value);
    }
}
