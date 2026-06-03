package io.cortex.processor.consume;

import io.cortex.processor.classify.AnomalyClassifier;
import io.cortex.processor.classify.Classification;
import io.cortex.processor.metrics.ProcessorMetrics;
import io.cortex.processor.parse.FailureReason;
import io.cortex.processor.parse.LogEventParser;
import io.cortex.processor.parse.ParseException;
import io.cortex.processor.parse.RawLogEvent;
import io.cortex.processor.parse.SchemaValidator;
import io.cortex.processor.parse.SchemaViolationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the production topic
 * {@code cortex.logs.events.v1} (P5.0 / ADR-0028 D1, D3; P5.1
 * parse + validate + DLQ pipeline).
 *
 * <p>Bound via {@code @KafkaListener} (Spring for Apache Kafka
 * direct, per LD79 + ADR-0026 symmetry with the P4.4b
 * {@code KafkaTemplate} producer pivot). Manual acknowledgment per
 * {@code spring.kafka.listener.ack-mode=manual} in application.yml
 * controls the offset commit boundary.</p>
 *
 * <p>P5.1 pipeline (per record):</p>
 * <ol>
 *   <li>{@code metrics.incConsumed()} -- always, even on poison
 *       records (the record was polled off the broker).</li>
 *   <li>{@link LogEventParser#parse(byte[])} -- decode the
 *       CloudEvent envelope + extract the typed
 *       {@link RawLogEvent}. On {@link ParseException}, route the
 *       original bytes to {@code cortex.logs.events.v1.dlq} with
 *       header {@code x-failure-reason=parse_error} via
 *       {@link DlqPublisher}, then ack.</li>
 *   <li>{@link SchemaValidator#validate(RawLogEvent)} -- enforce
 *       the LogEntry contract (level enum allowlist, ts non-null,
 *       service non-blank, message non-null, tenantId non-blank).
 *       On {@link SchemaViolationException}, route to DLQ with
 *       header {@code x-failure-reason=schema_violation}, then
 *       ack.</li>
 *   <li>{@code metrics.incParsed()} -- only when both parse +
 *       validate succeed.</li>
 *   <li>{@link AnomalyClassifier#classify(RawLogEvent)} -- SPI
 *       dispatch (P5.2 swaps the impl behind
 *       {@code cortex.processor.classifier=spring-ai}).</li>
 *   <li>{@code ack.acknowledge()} -- commit the offset.</li>
 * </ol>
 *
 * <p>Any unexpected {@code RuntimeException} (e.g. classifier
 * throws) falls through to the catch-all log + ack path so the
 * consumer cannot loop on a single record; P5.x will refine this
 * with retry budgets + classifier-side DLQ semantics.</p>
 */
@Component
public class LogEventConsumer {

    private static final Logger LOG =
            LoggerFactory.getLogger(LogEventConsumer.class);

    private final LogEventParser parser;
    private final SchemaValidator validator;
    private final AnomalyClassifier classifier;
    private final ProcessorMetrics metrics;
    private final DlqPublisher dlqPublisher;

    /**
     * Spring constructor.
     *
     * @param parser       CloudEvent envelope + data decoder
     * @param validator    schema-conformance enforcer
     * @param classifier   the selected AnomalyClassifier impl
     *                     (NoopAnomalyClassifier by default; gated
     *                     by {@code cortex.processor.classifier})
     * @param metrics      the Micrometer counter holder
     * @param dlqPublisher DLQ publisher for parse + validate failures
     */
    public LogEventConsumer(final LogEventParser parser,
                            final SchemaValidator validator,
                            final AnomalyClassifier classifier,
                            final ProcessorMetrics metrics,
                            final DlqPublisher dlqPublisher) {
        this.parser = parser;
        this.validator = validator;
        this.classifier = classifier;
        this.metrics = metrics;
        this.dlqPublisher = dlqPublisher;
    }

    /**
     * Main consumer entry point.
     *
     * <p>{@code topics} + {@code groupId} reference the same
     * {@code application.yml} properties that drive the autoconfigured
     * {@code KafkaListenerContainerFactory} so a single source of
     * truth controls the topic + group across boot + listener.</p>
     *
     * @param record the raw Kafka record polled off the topic
     * @param ack    manual acknowledgment handle
     */
    @KafkaListener(
            topics = "${cortex.processor.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @SuppressWarnings("checkstyle:methodlength")
    public void onMessage(final ConsumerRecord<byte[], byte[]> record,
                          final Acknowledgment ack) {
        this.metrics.incConsumed();
        final byte[] payload = record.value();
        if (payload == null || payload.length == 0) {
            LOG.warn("Skipping null/empty CloudEvent on {} partition={} offset={}",
                    record.topic(), record.partition(), record.offset());
            ack.acknowledge();
            return;
        }
        final RawLogEvent event;
        try {
            event = this.parser.parse(payload);
        } catch (ParseException ex) {
            this.dlqPublisher.publish(record, FailureReason.PARSE_ERROR, ex);
            ack.acknowledge();
            return;
        }
        try {
            this.validator.validate(event);
        } catch (SchemaViolationException ex) {
            this.dlqPublisher.publish(record, FailureReason.SCHEMA_VIOLATION, ex);
            ack.acknowledge();
            return;
        }
        try {
            this.metrics.incParsed();
            Classification verdict;
            try {
                verdict = this.classifier.classify(event);
                if (verdict == null) {
                    verdict = Classification.none();
                }
            } catch (RuntimeException ex) {
                // Classifier upstream (e.g. LLM call) failed. Tick
                // the error outcome, fall back to no-anomaly, and
                // keep the offset moving so a transient model outage
                // does not stall the consumer (ADR-0029 D4).
                LOG.warn("Classifier failed eventId={} -> outcome=error: {}",
                        event.eventId(), ex.getMessage());
                this.metrics.incClassified(ProcessorMetrics.OUTCOME_ERROR);
                ack.acknowledge();
                return;
            }
            if (verdict.anomaly()) {
                this.metrics.incClassified(ProcessorMetrics.OUTCOME_ANOMALY);
                LOG.info("Anomaly classified eventId={} severity={} reason={}",
                        event.eventId(), verdict.severity(), verdict.reason());
                // P5.4 will publish to cortex.anomalies.v1 here.
            } else {
                this.metrics.incClassified(ProcessorMetrics.OUTCOME_NORMAL);
            }
            ack.acknowledge();
        } catch (RuntimeException ex) {
            // Catch-all defence in depth: classifier-side path
            // already handles its own RuntimeException above, so
            // this branch only catches downstream defects (P5.3
            // fan-out, future P5.4 publish path). Log + ack so the
            // consumer cannot loop.
            LOG.error("Post-validate failure on {} partition={} offset={} eventId={}",
                    record.topic(), record.partition(), record.offset(),
                    event.eventId(), ex);
            ack.acknowledge();
        }
    }
}

