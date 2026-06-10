package io.cortex.remediation.consume;

import io.cortex.remediation.anomaly.AnomalyReadModelWriter;
import io.cortex.remediation.dlq.AnomalyDlqPublisher;
import io.cortex.remediation.engine.RemediationEngine;
import io.cortex.remediation.parse.AnomalyEnvelopeParser;
import io.cortex.remediation.parse.AnomalyEvent;
import io.cortex.remediation.parse.FailureReason;
import io.cortex.remediation.parse.ParseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for the production handoff topic
 * {@code cortex.anomalies.v1} (P6.0 / ADR-0032 D1+D2).
 *
 * <p>Bound via {@code @KafkaListener} (Spring for Apache Kafka
 * direct, per LD79 + ADR-0028 symmetry with the P5 LogEventConsumer
 * + the P4.4b KafkaTemplate producer pivot). Manual acknowledgment
 * per {@code spring.kafka.listener.ack-mode=manual} in
 * application.yml controls the offset commit boundary.</p>
 *
 * <p>P21 pipeline (per record):</p>
 * <ol>
 *   <li>{@link AnomalyEnvelopeParser#parse(byte[])} -- decode the
 *       CloudEvents 1.0 envelope + extract the typed
 *       {@link AnomalyEvent}. On {@link ParseException}, publish the
 *       original bytes to {@code cortex.anomalies.v1.dlq} and ack.</li>
 *   <li>{@link AnomalyReadModelWriter#persistFailOpen(AnomalyEvent)} --
 *       record the query/read copy for P9.3a without blocking
 *       remediation when Postgres is unavailable.</li>
 *   <li>{@link RemediationEngine#handle(AnomalyEvent)} -- dedupe,
 *       policy, playbook, outcome audit, then human fallback.</li>
 *   <li>{@code ack.acknowledge()} -- commit the offset.</li>
 * </ol>
 *
 * <p>Any unexpected {@code RuntimeException} (e.g. dispatcher
 * throws despite the contract) falls through to the catch-all log +
 * ack path so the consumer cannot loop on a single record. P6.4
 * will refine this with retry budgets + dispatcher-side DLQ
 * semantics.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AnomalyConsumer {

    private final AnomalyEnvelopeParser parser;
    private final AnomalyDlqPublisher dlqPublisher;
    private final AnomalyReadModelWriter readModelWriter;
    private final RemediationEngine remediationEngine;

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
            topics = "${cortex.remediation.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(final ConsumerRecord<byte[], byte[]> record,
                          final Acknowledgment ack) {
        final byte[] payload = record.value();
        if (payload == null || payload.length == 0) {
            log.warn("Skipping null/empty CloudEvent on {} partition={} offset={}",
                    record.topic(), record.partition(), record.offset());
            publishDlq(record, FailureReason.INVALID_ENVELOPE, "empty payload");
            ack.acknowledge();
            return;
        }
        final AnomalyEvent event = tryParse(record, payload, ack);
        if (event == null) {
            return;
        }
        dispatchAndAck(record, event, ack);
    }

    private AnomalyEvent tryParse(final ConsumerRecord<byte[], byte[]> record,
                                  final byte[] payload,
                                  final Acknowledgment ack) {
        try {
            return this.parser.parse(payload);
        } catch (ParseException ex) {
            log.warn("Failed to parse anomaly envelope on {} partition={} offset={}"
                            + " reason={} message={}",
                    record.topic(), record.partition(), record.offset(),
                    ex.reason().header(), ex.getMessage());
            publishDlq(record, ex.reason(), ex.getMessage());
            ack.acknowledge();
            return null;
        }
    }

    private void dispatchAndAck(final ConsumerRecord<byte[], byte[]> record,
                                final AnomalyEvent event,
                                final Acknowledgment ack) {
        try {
            persistReadModel(event);
            this.remediationEngine.handle(event);
            ack.acknowledge();
        } catch (RuntimeException ex) {
            log.error("Remediation engine threw on {} partition={} offset={} eventId={}",
                    record.topic(), record.partition(), record.offset(),
                    event.eventId(), ex);
            ack.acknowledge();
        }
    }

    private void persistReadModel(final AnomalyEvent event) {
        try {
            this.readModelWriter.persistFailOpen(event);
        } catch (RuntimeException ex) {
            log.error("Read-model writer threw eventId={} tenantId={}",
                    event.eventId(), event.tenantId(), ex);
        }
    }

    private void publishDlq(final ConsumerRecord<byte[], byte[]> record,
                            final FailureReason reason,
                            final String message) {
        try {
            this.dlqPublisher.publish(record, reason, message);
        } catch (RuntimeException ex) {
            log.error("Failed to publish anomaly DLQ record topic={} offset={}",
                    record.topic(), record.offset(), ex);
        }
    }
}
