package io.cortex.processor.consume;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.cortex.processor.sink.ParsedEventSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Unit test for {@link LogEventConsumer} that exercises every
 * pipeline branch without a real broker (P5.1 + P5.2 classifier
 * outcome counter assertions, ADR-0029 D5; P5.4 anomaly publish
 * paths, ADR-0031).
 *
 * <p>Branches covered:</p>
 * <ol>
 *   <li>happy path normal: parse + validate + classify (none) ->
 *       outcome=normal counter ticks, AnomaliesPublisher NOT invoked</li>
 *   <li>happy path anomaly: parse + validate + classify (anomaly)
 *       -> outcome=anomaly counter ticks + INFO log +
 *       AnomaliesPublisher.publish invoked + anomalies_published
 *       counter ticks + ack (P5.4)</li>
 *   <li>anomaly publish failure: AnomaliesPublisher throws
 *       IllegalStateException -> consumer logs ERROR, does NOT
 *       ack, does NOT fan-out to sinks; anomalies_published
 *       counter stays at 0 (P5.4 / LD117)</li>
 *   <li>classifier throws -> outcome=error counter ticks + ack
 *       (consumer never loops on an LLM outage; ADR-0029 D4)</li>
 *   <li>classifier returns null -> verdict treated as none ->
 *       outcome=normal counter ticks</li>
 *   <li>parse failure: parser throws -> DLQ + ack + validator never
 *       invoked + zero classified ticks</li>
 *   <li>schema violation: validator throws -> DLQ + ack +
 *       classifier never invoked + zero classified ticks</li>
 *   <li>null payload: skip + ack</li>
 * </ol>
 */
class LogEventConsumerTest {

    private LogEventParser parser;
    private SchemaValidator validator;
    private AnomalyClassifier classifier;
    private ProcessorMetrics metrics;
    private MeterRegistry registry;
    private DlqPublisher dlqPublisher;
    private AnomaliesPublisher anomaliesPublisher;
    private ParsedEventSink lokiSink;
    private ParsedEventSink quickwitSink;
    private LogEventConsumer consumer;

    /** Resets all mocks + builds a fresh consumer under test. */
    @BeforeEach
    void setUp() {
        this.parser = mock(LogEventParser.class);
        this.validator = mock(SchemaValidator.class);
        this.classifier = mock(AnomalyClassifier.class);
        this.registry = new SimpleMeterRegistry();
        this.metrics = new ProcessorMetrics(this.registry);
        this.dlqPublisher = mock(DlqPublisher.class);
        this.anomaliesPublisher = mock(AnomaliesPublisher.class);
        this.lokiSink = mock(ParsedEventSink.class);
        when(this.lokiSink.name()).thenReturn("loki");
        this.quickwitSink = mock(ParsedEventSink.class);
        when(this.quickwitSink.name()).thenReturn("quickwit");
        this.consumer = new LogEventConsumer(this.parser, this.validator,
                this.classifier, this.metrics, this.dlqPublisher,
                this.anomaliesPublisher,
                List.of(this.lokiSink, this.quickwitSink));
    }

    /** Happy path normal verdict: parse + validate + classify, tick outcome=normal, ack. */
    @Test
    void happyPathNormalTicksNormalOutcomeAndAcks() {
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(Classification.none());

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1, 2, 3}), ack);

        verify(this.validator).validate(event);
        verify(this.classifier).classify(event);
        verify(ack).acknowledge();
        verify(this.dlqPublisher, never()).publish(any(), any(), any());
        verify(this.anomaliesPublisher, never()).publish(any(), any());
        verify(this.lokiSink).send(eq(event), any(Classification.class));
        verify(this.quickwitSink).send(eq(event), any(Classification.class));
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_NORMAL)).isEqualTo(1.0d);
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_ANOMALY)).isZero();
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_ERROR)).isZero();
        assertThat(anomaliesPublishedCount()).isZero();
    }

    /** Happy path anomaly verdict: tick outcome=anomaly + publish to cortex.anomalies.v1 + ack (P5.4). */
    @Test
    void anomalyVerdictPublishesAndAcks() {
        final RawLogEvent event = sample();
        final Classification anomaly = new Classification(true, "HIGH", "spike");
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(anomaly);

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1}), ack);

        verify(this.anomaliesPublisher).publish(event, anomaly);
        verify(ack).acknowledge();
        verify(this.dlqPublisher, never()).publish(any(), any(), any());
        verify(this.lokiSink).send(event, anomaly);
        verify(this.quickwitSink).send(event, anomaly);
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_ANOMALY)).isEqualTo(1.0d);
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_NORMAL)).isZero();
        assertThat(anomaliesPublishedCount()).isEqualTo(1.0d);
    }

    /**
     * P5.4 / LD117: AnomaliesPublisher throws -> consumer leaves
     * record un-acked so Kafka redelivery re-attempts the publish.
     * Sinks must NOT fan out (we don't want duplicate Loki writes on
     * the retry).
     */
    @Test
    void anomalyPublishFailureLeavesRecordUnacked() {
        final RawLogEvent event = sample();
        final Classification anomaly = new Classification(true, "HIGH", "spike");
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(anomaly);
        org.mockito.Mockito.doThrow(new IllegalStateException("kafka down"))
                .when(this.anomaliesPublisher).publish(event, anomaly);

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1}), ack);

        verify(this.anomaliesPublisher).publish(event, anomaly);
        // Source record stays un-acked so Kafka rebalance redelivers.
        verify(ack, never()).acknowledge();
        // Sinks must NOT run -- the retry will publish + fan-out together.
        verify(this.lokiSink, never()).send(any(), any());
        verify(this.quickwitSink, never()).send(any(), any());
        // The anomaly tick already happened pre-publish (outcome counter
        // is per-attempt, not per-commit; same semantics as parsed_total).
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_ANOMALY)).isEqualTo(1.0d);
        // Published counter only ticks on successful send.
        assertThat(anomaliesPublishedCount()).isZero();
    }

    /** Classifier throws: tick outcome=error, fall back, ack, do NOT loop. */
    @Test
    void classifierExceptionTicksErrorOutcomeAndAcks() {
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event))
                .thenThrow(new RuntimeException("simulated llm outage"));

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1}), ack);

        verify(ack).acknowledge();
        verify(this.dlqPublisher, never()).publish(any(), any(), any());
        verify(this.lokiSink, never()).send(any(), any());
        verify(this.quickwitSink, never()).send(any(), any());
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_ERROR)).isEqualTo(1.0d);
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_ANOMALY)).isZero();
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_NORMAL)).isZero();
    }

    /** Classifier returns null: defensive coerce to none() + tick outcome=normal + ack. */
    @Test
    void classifierNullVerdictTreatedAsNormal() {
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(null);

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1}), ack);

        verify(ack).acknowledge();
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_NORMAL)).isEqualTo(1.0d);
    }

    /** Parser throws -> DLQ publish + ack + downstream skipped + zero classified ticks. */
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
        verify(this.lokiSink, never()).send(any(), any());
        verify(this.quickwitSink, never()).send(any(), any());
        verify(ack).acknowledge();
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_NORMAL)).isZero();
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_ANOMALY)).isZero();
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_ERROR)).isZero();
    }

    /** Validator throws -> DLQ publish + ack + classifier skipped + zero classified ticks. */
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
        verify(this.lokiSink, never()).send(any(), any());
        verify(this.quickwitSink, never()).send(any(), any());
        verify(ack).acknowledge();
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_NORMAL)).isZero();
    }

    /** Null payload short-circuits to ack without touching parser. */
    @Test
    void nullPayloadIsSkippedAndAcked() {
        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(null), ack);

        verify(this.parser, never()).parse(any(byte[].class));
        verify(this.dlqPublisher, never()).publish(any(), any(), any());
        verify(ack).acknowledge();
        assertThat(classifiedCount(ProcessorMetrics.OUTCOME_NORMAL)).isZero();
    }

    /** Anomaly verdict is logged AND published to cortex.anomalies.v1 (P5.4 / ADR-0031). */
    @Test
    void anomalyVerdictIsLoggedAndPublished() {
        final RawLogEvent event = sample();
        final Classification anomaly = new Classification(true, "HIGH", "spike");
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(anomaly);

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1}), ack);

        verify(this.anomaliesPublisher).publish(event, anomaly);
        verify(ack).acknowledge();
        verify(this.dlqPublisher, never()).publish(eq(null), any(), any());
    }

    /** P5.3: sink throwing must NOT block the consumer or rewind offsets. */
    @Test
    void sinkExceptionDoesNotBlockAckOrFanoutToPeer() {
        final RawLogEvent event = sample();
        final Classification anomaly = new Classification(true, "HIGH", "spike");
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(anomaly);
        org.mockito.Mockito.doThrow(new RuntimeException("loki down"))
                .when(this.lokiSink).send(any(), any());

        final Acknowledgment ack = mock(Acknowledgment.class);
        this.consumer.onMessage(record(new byte[]{1}), ack);

        verify(this.lokiSink).send(event, anomaly);
        verify(this.quickwitSink).send(event, anomaly);
        verify(ack).acknowledge();
    }

    /** P5.3: an empty sink list must not break the consumer (default boot). */
    @Test
    void emptySinkListIsTolerated() {
        final LogEventConsumer noSinkConsumer = new LogEventConsumer(this.parser,
                this.validator, this.classifier, this.metrics, this.dlqPublisher,
                this.anomaliesPublisher,
                java.util.List.of());
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(Classification.none());

        final Acknowledgment ack = mock(Acknowledgment.class);
        noSinkConsumer.onMessage(record(new byte[]{1}), ack);

        verify(ack).acknowledge();
    }

    /** P5.3: null sink list (defensive) must not break the consumer. */
    @Test
    void nullSinkListIsTolerated() {
        final LogEventConsumer noSinkConsumer = new LogEventConsumer(this.parser,
                this.validator, this.classifier, this.metrics, this.dlqPublisher,
                this.anomaliesPublisher,
                null);
        final RawLogEvent event = sample();
        when(this.parser.parse(any(byte[].class))).thenReturn(event);
        when(this.classifier.classify(event)).thenReturn(Classification.none());

        final Acknowledgment ack = mock(Acknowledgment.class);
        noSinkConsumer.onMessage(record(new byte[]{1}), ack);

        verify(ack).acknowledge();
    }

    /**
     * Reads the classified counter for the supplied outcome tag from
     * the test meter registry; returns {@code 0.0} when the counter
     * is absent.
     *
     * @param outcome one of the ProcessorMetrics outcome constants
     * @return current counter value
     */
    private double classifiedCount(final String outcome) {
        try {
            return this.registry.get(ProcessorMetrics.METRIC_CLASSIFIED_TOTAL)
                    .tag("outcome", outcome)
                    .counter().count();
        } catch (RuntimeException ex) {
            return 0.0d;
        }
    }

    /**
     * Reads the P5.4 anomalies-published counter from the test meter
     * registry; returns {@code 0.0} when the counter is absent.
     *
     * @return current counter value
     */
    private double anomaliesPublishedCount() {
        try {
            return this.registry.get(
                            ProcessorMetrics.METRIC_ANOMALIES_PUBLISHED_TOTAL)
                    .counter().count();
        } catch (RuntimeException ex) {
            return 0.0d;
        }
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
