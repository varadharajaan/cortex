package io.cortex.processor.consume;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes consumer-side parse + validate failures to the DLQ
 * topic {@code cortex.logs.events.v1.dlq} (P5.1 / ADR-0027 contract
 * mirror).
 *
 * <p>The DLQ record value is byte-for-byte the original Kafka
 * record's value bytes so a replay tool can re-publish to the
 * production topic after a fix without re-decoding. Three Kafka
 * headers explain the dead-letter per ADR-0027 D4:</p>
 *
 * <ul>
 *   <li>{@code content-type=application/cloudevents+json}</li>
 *   <li>{@code x-orig-topic=${cortex.processor.topic}}</li>
 *   <li>{@code x-failure-reason=parse_error} or
 *       {@code schema_violation}</li>
 * </ul>
 *
 * <p>Synchronous send with a 5-second bounded wait so the consumer
 * cannot ack a record until the DLQ row is durably written; a DLQ
 * failure surfaces as a RuntimeException to the consumer which
 * keeps the record un-acked for a redelivery attempt on the next
 * poll.</p>
 */
@Component
public class DlqPublisher {

    private static final Logger LOG =
            LoggerFactory.getLogger(DlqPublisher.class);

    /** Bounded send-timeout so the consumer thread never hangs. */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** Wire header names per ADR-0027 D4. */
    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String HEADER_ORIG_TOPIC = "x-orig-topic";
    private static final String HEADER_FAILURE_REASON = "x-failure-reason";
    private static final String CONTENT_TYPE_VALUE = "application/cloudevents+json";

    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
    private final String dlqTopic;
    private final String origTopic;

    /**
     * Spring constructor.
     *
     * @param kafkaTemplate the byte[]/byte[] KafkaTemplate produced
     *                      by {@code ProcessorKafkaProducerConfig}
     * @param dlqTopic      DLQ topic name from
     *                      {@code cortex.processor.dlq.topic}
     * @param origTopic     production source topic from
     *                      {@code cortex.processor.topic} (used to
     *                      stamp the {@code x-orig-topic} header)
     */
    public DlqPublisher(
            final KafkaTemplate<byte[], byte[]> kafkaTemplate,
            @Value("${cortex.processor.dlq.topic}") final String dlqTopic,
            @Value("${cortex.processor.topic}") final String origTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic = dlqTopic;
        this.origTopic = origTopic;
    }

    /**
     * Publish the original failed record to the DLQ topic with the
     * supplied failure reason. The send is synchronous with a
     * bounded {@value #SEND_TIMEOUT_SECONDS}-second wait. A send
     * timeout or broker NACK surfaces as an unchecked
     * {@link IllegalStateException} so the consumer's catch block
     * leaves the record un-acked and a redelivery attempts the DLQ
     * publish again.
     *
     * @param original       the consumer record that failed parse
     *                       or validation; its value bytes are
     *                       forwarded byte-for-byte to the DLQ
     * @param failureReason  one of {@code parse_error} or
     *                       {@code schema_violation}
     * @param cause          the originating exception; logged at
     *                       WARN before the send
     * @throws IllegalStateException if the DLQ send is interrupted,
     *                               times out after the bounded wait,
     *                               or is rejected by the broker
     */
    @SuppressFBWarnings(
            value = "REC_CATCH_EXCEPTION",
            justification = "Wrap any send-time failure into a uniform RuntimeException"
                    + " so the consumer's catch block leaves the record un-acked.")
    public void publish(
            final ConsumerRecord<byte[], byte[]> original,
            final String failureReason,
            final Throwable cause) {
        LOG.warn("Routing record to DLQ topic={} partition={} offset={} reason={} cause={}",
                original.topic(), original.partition(), original.offset(),
                failureReason, cause.toString());
        final ProducerRecord<byte[], byte[]> dlqRecord = new ProducerRecord<>(
                this.dlqTopic, null, original.key(), original.value());
        dlqRecord.headers().add(new RecordHeader(HEADER_CONTENT_TYPE,
                CONTENT_TYPE_VALUE.getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(HEADER_ORIG_TOPIC,
                this.origTopic.getBytes(StandardCharsets.UTF_8)));
        dlqRecord.headers().add(new RecordHeader(HEADER_FAILURE_REASON,
                failureReason.getBytes(StandardCharsets.UTF_8)));
        try {
            this.kafkaTemplate.send(dlqRecord).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "DLQ publish interrupted for topic=" + this.dlqTopic, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException(
                    "DLQ publish failed for topic=" + this.dlqTopic, ex);
        }
    }
}
