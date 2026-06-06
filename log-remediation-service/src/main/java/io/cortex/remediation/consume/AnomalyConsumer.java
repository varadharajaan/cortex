package io.cortex.remediation.consume;

import io.cortex.remediation.dispatch.DispatchResult;
import io.cortex.remediation.dispatch.RemediationDispatcher;
import io.cortex.remediation.metrics.RemediationMetrics;
import io.cortex.remediation.parse.AnomalyEnvelopeParser;
import io.cortex.remediation.parse.AnomalyEvent;
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
 * <p>P6.0 pipeline (per record):</p>
 * <ol>
 *   <li>{@link AnomalyEnvelopeParser#parse(byte[])} -- decode the
 *       CloudEvents 1.0 envelope + extract the typed
 *       {@link AnomalyEvent}. On {@link ParseException}, log the
 *       categorical {@link FailureReason} and ack (P6.4 will route
 *       to {@code cortex.anomalies.v1.dlq} with header
 *       {@code x-failure-reason=<reason>}).</li>
 *   <li>{@link RemediationDispatcher#dispatch(AnomalyEvent)} -- SPI
 *       dispatch (P6.1..P6.3 swap the impl behind
 *       {@code cortex.remediation.dispatcher.provider=slack|pagerduty|jira}).</li>
 *   <li>{@link RemediationMetrics#incDispatched(String, String, String)}
 *       -- tick {@code cortex.remediation.dispatched_total} with
 *       {@code channel} + {@code outcome} from the
 *       {@link DispatchResult} and {@code tenant_id} from the parsed
 *       event.</li>
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
    private final RemediationDispatcher dispatcher;
    private final RemediationMetrics metrics;

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
            ack.acknowledge();
            return null;
        }
    }

    private void dispatchAndAck(final ConsumerRecord<byte[], byte[]> record,
                                final AnomalyEvent event,
                                final Acknowledgment ack) {
        try {
            DispatchResult result = this.dispatcher.dispatch(event);
            if (result == null) {
                log.warn("Dispatcher returned null for eventId={} -- treating"
                        + " as skipped", event.eventId());
                result = DispatchResult.skipped("dispatcher returned null");
            }
            this.metrics.incDispatched(result.channel(), result.outcome(),
                    event.tenantId());
            log.info("Dispatched anomaly eventId={} tenantId={} channel={}"
                            + " outcome={} reason={}",
                    event.eventId(), event.tenantId(), result.channel(),
                    result.outcome(), result.reason());
            ack.acknowledge();
        } catch (RuntimeException ex) {
            log.error("Dispatcher threw on {} partition={} offset={} eventId={}",
                    record.topic(), record.partition(), record.offset(),
                    event.eventId(), ex);
            ack.acknowledge();
        }
    }
}
