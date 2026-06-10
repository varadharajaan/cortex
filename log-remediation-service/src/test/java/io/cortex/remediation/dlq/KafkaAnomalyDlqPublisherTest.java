package io.cortex.remediation.dlq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.cortex.remediation.parse.FailureReason;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit tests for malformed anomaly DLQ publisher.
 */
class KafkaAnomalyDlqPublisherTest {

    private static final String TOPIC = "cortex.anomalies.v1.dlq";

    /** DLQ publish preserves the original record and adds failure metadata headers. */
    @Test
    void publishCopiesRecordAndAddsFailureHeaders() {
        final KafkaTemplate<byte[], byte[]> kafka = kafkaTemplate();
        when(kafka.send(Mockito.<ProducerRecord<byte[], byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        final KafkaAnomalyDlqPublisher publisher =
                new KafkaAnomalyDlqPublisher(kafka, TOPIC);
        final ConsumerRecord<byte[], byte[]> source = sourceRecord();

        publisher.publish(source, FailureReason.WRONG_TYPE, "wrong type");

        final ProducerRecord<byte[], byte[]> out = capturedRecord(kafka);
        assertThat(out.topic()).isEqualTo(TOPIC);
        assertThat(out.key()).isEqualTo(source.key());
        assertThat(out.value()).isEqualTo(source.value());
        assertThat(header(out, "original")).isEqualTo("kept");
        assertThat(header(out, "x-failure-reason")).isEqualTo("wrong_type");
        assertThat(header(out, "x-failure-message")).isEqualTo("wrong type");
        assertThat(header(out, "x-source-topic")).isEqualTo("cortex.anomalies.v1");
    }

    /** Null reason/message values are normalized to blank header values. */
    @Test
    void nullFailureMetadataBecomesBlankHeaders() {
        final KafkaTemplate<byte[], byte[]> kafka = kafkaTemplate();
        when(kafka.send(Mockito.<ProducerRecord<byte[], byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        final KafkaAnomalyDlqPublisher publisher =
                new KafkaAnomalyDlqPublisher(kafka, TOPIC);

        publisher.publish(sourceRecord(), null, null);

        final ProducerRecord<byte[], byte[]> out = capturedRecord(kafka);
        assertThat(header(out, "x-failure-reason")).isEmpty();
        assertThat(header(out, "x-failure-message")).isEmpty();
    }

    /** Kafka send failures surface as IllegalStateException for consumer logging. */
    @Test
    void sendFailureThrowsIllegalStateException() {
        final KafkaTemplate<byte[], byte[]> kafka = kafkaTemplate();
        final CompletableFuture<SendResult<byte[], byte[]>> failed =
                new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(kafka.send(Mockito.<ProducerRecord<byte[], byte[]>>any()))
                .thenReturn(failed);
        final KafkaAnomalyDlqPublisher publisher =
                new KafkaAnomalyDlqPublisher(kafka, TOPIC);

        assertThatThrownBy(() -> publisher.publish(sourceRecord(),
                FailureReason.INVALID_ENVELOPE, "bad"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Anomaly DLQ publish failed");
    }

    private static ConsumerRecord<byte[], byte[]> sourceRecord() {
        final ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "cortex.anomalies.v1", 0, 10L,
                "key".getBytes(StandardCharsets.UTF_8),
                "payload".getBytes(StandardCharsets.UTF_8));
        record.headers().add(new RecordHeader("original",
                "kept".getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private static String header(final ProducerRecord<byte[], byte[]> record,
                                 final String name) {
        return new String(record.headers().lastHeader(name).value(),
                StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<byte[], byte[]> kafkaTemplate() {
        return Mockito.mock(KafkaTemplate.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ProducerRecord<byte[], byte[]> capturedRecord(
            final KafkaTemplate<byte[], byte[]> kafka) {
        final ArgumentCaptor<ProducerRecord<byte[], byte[]>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka).send(captor.capture());
        return captor.getValue();
    }
}
