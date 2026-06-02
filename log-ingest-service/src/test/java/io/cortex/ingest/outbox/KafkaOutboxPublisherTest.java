package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit tests for {@link KafkaOutboxPublisher} (P4.4c / ADR-0027).
 *
 * <p>Exercises the producer-record assembly + send-and-await contract
 * the poller depends on:</p>
 *
 * <ul>
 *   <li>{@link KafkaOutboxPublisher#publish} targets the production
 *       topic, sets the tenant id as the partition key, and stamps
 *       the {@code content-type} header.</li>
 *   <li>{@link KafkaOutboxPublisher#publishDlq} targets the DLQ
 *       topic and adds the {@code x-orig-topic} +
 *       {@code x-failure-reason} headers on top.</li>
 *   <li>Both methods translate broker failures (failed future) into
 *       a {@link RuntimeException} so the poller can drive the
 *       retry / DLQ lifecycle.</li>
 * </ul>
 */
class KafkaOutboxPublisherTest {

    /** Mocked Kafka producer template. */
    @SuppressWarnings("unchecked")
    private KafkaTemplate<byte[], byte[]> kafkaTemplate;

    /** SUT. */
    private KafkaOutboxPublisher publisher;

    /** Default constructor used by JUnit. */
    KafkaOutboxPublisherTest() {
        // no state
    }

    /** Resets the mocks before each test. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void initPublisher() {
        this.kafkaTemplate = (KafkaTemplate<byte[], byte[]>) mock(KafkaTemplate.class);
        this.publisher = new KafkaOutboxPublisher(this.kafkaTemplate);
    }

    /**
     * Successful production publish targets the production topic,
     * sets the tenant id as the key, and stamps the content-type
     * header.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishWiresProductionTopicKeyAndContentTypeHeader() {
        final OutboxEvent row = pendingRow();
        final SendResult<byte[], byte[]> ack = mock(SendResult.class);
        when(this.kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(ack));

        this.publisher.publish(row, "payload-bytes".getBytes(StandardCharsets.UTF_8),
                "application/cloudevents+json");

        final ArgumentCaptor<ProducerRecord<byte[], byte[]>> cap =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(this.kafkaTemplate).send(cap.capture());
        final ProducerRecord<byte[], byte[]> sent = cap.getValue();
        assertThat(sent.topic()).isEqualTo(KafkaOutboxPublisher.PRODUCTION_TOPIC);
        assertThat(new String(sent.key(), StandardCharsets.UTF_8))
                .isEqualTo(row.tenantId());
        final Header ct = sent.headers()
                .lastHeader(KafkaOutboxPublisher.HEADER_CONTENT_TYPE);
        assertThat(ct).isNotNull();
        assertThat(new String(ct.value(), StandardCharsets.UTF_8))
                .isEqualTo("application/cloudevents+json");
    }

    /**
     * DLQ publish targets the DLQ topic and adds the two
     * failure-tracking headers documented by ADR-0027.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishDlqWiresDlqTopicAndFailureHeaders() {
        final OutboxEvent row = pendingRow();
        final SendResult<byte[], byte[]> ack = mock(SendResult.class);
        when(this.kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(ack));

        this.publisher.publishDlq(row,
                "payload-bytes".getBytes(StandardCharsets.UTF_8),
                "application/cloudevents+json",
                KafkaOutboxPublisher.PRODUCTION_TOPIC,
                FailureReason.KAFKA_TIMEOUT);

        final ArgumentCaptor<ProducerRecord<byte[], byte[]>> cap =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(this.kafkaTemplate).send(cap.capture());
        final ProducerRecord<byte[], byte[]> sent = cap.getValue();
        assertThat(sent.topic()).isEqualTo(KafkaOutboxPublisher.DLQ_TOPIC);
        final Header origTopic = sent.headers()
                .lastHeader(KafkaOutboxPublisher.HEADER_ORIG_TOPIC);
        final Header reason = sent.headers()
                .lastHeader(KafkaOutboxPublisher.HEADER_FAILURE_REASON);
        assertThat(origTopic).isNotNull();
        assertThat(reason).isNotNull();
        assertThat(new String(origTopic.value(), StandardCharsets.UTF_8))
                .isEqualTo(KafkaOutboxPublisher.PRODUCTION_TOPIC);
        assertThat(new String(reason.value(), StandardCharsets.UTF_8))
                .isEqualTo(FailureReason.KAFKA_TIMEOUT);
    }

    /**
     * A failed future (broker NACK / timeout / etc.) is translated
     * into an {@link IllegalStateException} so the poller's catch
     * block can drive the retry / DLQ lifecycle.
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishTranslatesFailedFutureToRuntimeException() {
        final OutboxEvent row = pendingRow();
        final CompletableFuture<SendResult<byte[], byte[]>> failed =
                CompletableFuture.failedFuture(new RuntimeException("broker-nack"));
        when(this.kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> this.publisher.publish(row,
                        "bytes".getBytes(StandardCharsets.UTF_8),
                        "application/cloudevents+json"))
                .withMessageContaining("kafka send failed");
    }

    /**
     * Builds a deterministic PENDING outbox row for the assertions.
     *
     * @return a synthetic PENDING OutboxEvent for the SUT
     */
    private static OutboxEvent pendingRow() {
        return new OutboxEvent(
                1L,
                "cortex-dev",
                "evt-1",
                "{\"hello\":\"world\"}",
                OutboxStatus.PENDING.name(),
                0,
                Instant.parse("2026-06-02T12:00:00Z"),
                null,
                Instant.parse("2026-06-02T12:00:00Z"),
                null);
    }
}
