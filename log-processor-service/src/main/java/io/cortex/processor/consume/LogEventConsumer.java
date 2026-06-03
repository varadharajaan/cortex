package io.cortex.processor.consume;

import io.cloudevents.CloudEvent;
import io.cloudevents.jackson.JsonFormat;
import io.cortex.processor.classify.AnomalyClassifier;
import io.cortex.processor.classify.Classification;
import io.cortex.processor.metrics.ProcessorMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the production topic
 * {@code cortex.logs.events.v1} (P5.0 / ADR-0028 D1, D3).
 *
 * <p>Bound via {@code @KafkaListener} (Spring for Apache Kafka
 * direct, per LD79 + ADR-0026 symmetry with the P4.4b
 * {@code KafkaTemplate} producer pivot). Manual acknowledgment per
 * {@code spring.kafka.listener.ack-mode=manual} in application.yml
 * controls the offset commit boundary: we only ack after the
 * CloudEvent envelope decodes and the AnomalyClassifier returns.
 * Decode failures count toward
 * {@code cortex.processor.events.consumed_total} (the record was
 * polled) but NOT toward
 * {@code cortex.processor.events.parsed_total}, and the listener
 * still acks so the consumer doesn't loop forever on a poison
 * record. The P5.x DLQ path (ADR-0027 outbox dead-letter replay)
 * will move poison records to {@code cortex.logs.events.v1.dlq}
 * before the ack.</p>
 *
 * <p>P5.0 SPI dispatch only -- no parser, no fan-out, no
 * cortex.anomalies.v1 publish. Real consumer logic arrives in
 * P5.1..P5.4 against the same listener method signature so the
 * Kafka wire contract is stable from P5.0 onwards.</p>
 */
@Component
public class LogEventConsumer {

    private static final Logger LOG =
            LoggerFactory.getLogger(LogEventConsumer.class);

    /** CloudEvents 1.0 structured-mode JSON decoder (ADR-0026). */
    private final JsonFormat jsonFormat = new JsonFormat();

    private final AnomalyClassifier classifier;
    private final ProcessorMetrics metrics;

    /**
     * Spring constructor.
     *
     * @param classifier the selected AnomalyClassifier impl
     *                   (NoopAnomalyClassifier by default; gated by
     *                   {@code cortex.processor.classifier})
     * @param metrics    the Micrometer counter holder
     */
    public LogEventConsumer(final AnomalyClassifier classifier,
                            final ProcessorMetrics metrics) {
        this.classifier = classifier;
        this.metrics = metrics;
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
    public void onMessage(final ConsumerRecord<byte[], byte[]> record,
                          final Acknowledgment ack) {
        this.metrics.incConsumed();
        try {
            final byte[] payload = record.value();
            if (payload == null || payload.length == 0) {
                LOG.warn("Skipping null/empty CloudEvent on {} partition={} offset={}",
                        record.topic(), record.partition(), record.offset());
                ack.acknowledge();
                return;
            }
            final CloudEvent event = this.jsonFormat.deserialize(payload);
            final Classification verdict = this.classifier.classify(event);
            this.metrics.incParsed();
            if (verdict.anomaly()) {
                LOG.info("Anomaly classified id={} severity={} reason={}",
                        event.getId(), verdict.severity(), verdict.reason());
                // P5.4 will publish to cortex.anomalies.v1 here.
            }
            ack.acknowledge();
        } catch (RuntimeException ex) {
            // Poison record: log + ack so the consumer doesn't loop.
            // P5.x DLQ replay (ADR-0027) will route to
            // cortex.logs.events.v1.dlq before the ack.
            LOG.error("Failed to process CloudEvent on {} partition={} offset={}",
                    record.topic(), record.partition(), record.offset(), ex);
            ack.acknowledge();
        }
    }
}
