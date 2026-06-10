package io.cortex.remediation.dlq;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cortex.remediation.parse.FailureReason;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka-backed malformed anomaly DLQ publisher.
 */
@Component
public class KafkaAnomalyDlqPublisher implements AnomalyDlqPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10L;
    private static final String HEADER_FAILURE_REASON = "x-failure-reason";
    private static final String HEADER_FAILURE_MESSAGE = "x-failure-message";
    private static final String HEADER_SOURCE_TOPIC = "x-source-topic";

    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
    private final String topic;

    /**
     * Spring constructor.
     *
     * @param kafkaTemplate byte-array Kafka template
     * @param topic DLQ topic
     */
    public KafkaAnomalyDlqPublisher(
            final KafkaTemplate<byte[], byte[]> kafkaTemplate,
            @Value("${cortex.remediation.dlq.topic:cortex.anomalies.v1.dlq}")
            final String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    @SuppressFBWarnings(
            value = "REC_CATCH_EXCEPTION",
            justification = "Wrap send failures for consumer-side logging.")
    public void publish(final ConsumerRecord<byte[], byte[]> record,
                        final FailureReason reason,
                        final String message) {
        final ProducerRecord<byte[], byte[]> out =
                new ProducerRecord<>(this.topic, null, record.key(), record.value());
        for (final Header header : record.headers()) {
            out.headers().add(header);
        }
        out.headers().add(new RecordHeader(HEADER_FAILURE_REASON,
                safe(reason == null ? null : reason.header())));
        out.headers().add(new RecordHeader(HEADER_FAILURE_MESSAGE, safe(message)));
        out.headers().add(new RecordHeader(HEADER_SOURCE_TOPIC, safe(record.topic())));
        try {
            this.kafkaTemplate.send(out).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anomaly DLQ publish interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Anomaly DLQ publish failed", ex);
        }
    }

    private static byte[] safe(final String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }
}
