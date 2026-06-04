package io.cortex.remediation.consume;

import io.cortex.remediation.dispatch.DispatchResult;
import io.cortex.remediation.dispatch.RemediationDispatcher;
import io.cortex.remediation.metrics.RemediationMetrics;
import io.cortex.remediation.parse.AnomalyEnvelopeParser;
import io.cortex.remediation.parse.AnomalyEvent;
import io.cortex.remediation.parse.ParseException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class AnomalyConsumer {

    private static final Logger LOG =
            LoggerFactory.getLogger(AnomalyConsumer.class);

    private final AnomalyEnvelopeParser parser;
    private final RemediationDispatcher dispatcher;
    private final RemediationMetrics metrics;

    /**
     * Spring constructor.
     *
     * @param parser     CloudEvent envelope + data decoder
     * @param dispatcher the selected RemediationDispatcher impl
     *                   (NoopRemediationDispatcher by default; gated
     *                   by {@code cortex.remediation.dispatcher.provider})
     * @param metrics    the Micrometer counter holder
     */
    public AnomalyConsumer(final AnomalyEnvelopeParser parser,
                           final RemediationDispatcher dispatcher,
                           final RemediationMetrics metrics) {
        this.parser = parser;
        this.dispatcher = dispatcher;
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
            topics = "${cortex.remediation.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(final ConsumerRecord<byte[], byte[]> record,
                          final Acknowledgment ack) {
        final byte[] payload = record.value();
        if (payload == null || payload.length == 0) {
            LOG.warn("Skipping null/empty CloudEvent on {} partition={} offset={}",
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

    /**
     * Parses the CloudEvent payload; acks + returns {@code null} on
     * failure so the caller short-circuits without looping the poison
     * record. P6.4 will replace this fallthrough with a DLQ producer
     * keyed on {@code x-failure-reason=<reason.header()>}.
     *
     * @param record  the Kafka record under processing (used only for log context)
     * @param payload the non-null, non-empty CloudEvent envelope bytes
     * @param ack     manual acknowledgment handle
     * @return the parsed {@link AnomalyEvent} or {@code null} if parsing failed
     */
    private AnomalyEvent tryParse(final ConsumerRecord<byte[], byte[]> record,
                                  final byte[] payload,
                                  final Acknowledgment ack) {
        try {
            return this.parser.parse(payload);
        } catch (ParseException ex) {
            LOG.warn("Failed to parse anomaly envelope on {} partition={} offset={}"
                            + " reason={} message={}",
                    record.topic(), record.partition(), record.offset(),
                    ex.reason().header(), ex.getMessage());
            ack.acknowledge();
            return null;
        }
    }

    /**
     * Hands the parsed event to the dispatcher SPI, ticks the
     * outcome counter, and always acks. A dispatcher contract
     * violation (null result or thrown {@link RuntimeException}) is
     * caught here so a defective adapter cannot stall the consumer
     * pipeline. P6.4 will tighten this with bounded retry semantics.
     *
     * @param record the Kafka record under processing (used only for log context)
     * @param event  the parsed anomaly event to dispatch
     * @param ack    manual acknowledgment handle
     */
    private void dispatchAndAck(final ConsumerRecord<byte[], byte[]> record,
                                final AnomalyEvent event,
                                final Acknowledgment ack) {
        try {
            DispatchResult result = this.dispatcher.dispatch(event);
            if (result == null) {
                LOG.warn("Dispatcher returned null for eventId={} -- treating"
                        + " as skipped", event.eventId());
                result = DispatchResult.skipped("dispatcher returned null");
            }
            this.metrics.incDispatched(result.channel(), result.outcome(),
                    event.tenantId());
            LOG.info("Dispatched anomaly eventId={} tenantId={} channel={}"
                            + " outcome={} reason={}",
                    event.eventId(), event.tenantId(), result.channel(),
                    result.outcome(), result.reason());
            ack.acknowledge();
        } catch (RuntimeException ex) {
            LOG.error("Dispatcher threw on {} partition={} offset={} eventId={}",
                    record.topic(), record.partition(), record.offset(),
                    event.eventId(), ex);
            ack.acknowledge();
        }
    }
}
