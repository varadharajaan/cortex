package io.cortex.ingest.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxEventPublisher} backed by Spring Kafka's
 * {@link KafkaTemplate} (P4.4c / ADR-0027 D5).
 *
 * <p>Replaces the inline {@code kafkaTemplate.send(...).get(timeout)}
 * call that lived in {@link OutboxPoller#publishOne} at P4.4b. Same
 * delivery semantics (synchronous send, broker acknowledgment under
 * {@code acks=all}, {@link RuntimeException} on any non-ack outcome),
 * just routed through the {@link OutboxEventPublisher} seam so the
 * Service Bus binder can swap in at P10 without touching the poller.</p>
 *
 * <p>Gated behind {@code cortex.outbox.publisher=kafka} (default per
 * {@code matchIfMissing=true}). When the property is set to
 * {@code servicebus}, this bean is NOT registered and
 * {@link ServiceBusOutboxPublisher} takes its place.</p>
 */
@Component
@ConditionalOnProperty(name = "cortex.outbox.publisher",
        havingValue = "kafka", matchIfMissing = true)
public class KafkaOutboxPublisher implements OutboxEventPublisher {

    /** Production outbox topic; pinned by ADR-0026. */
    public static final String PRODUCTION_TOPIC = "cortex.logs.events.v1";

    /** DLQ outbox topic; pinned by ADR-0027 D1. */
    public static final String DLQ_TOPIC = "cortex.logs.events.v1.dlq";

    /** Kafka record header for the CloudEvents content-type (P4.4b). */
    public static final String HEADER_CONTENT_TYPE = "content-type";

    /** DLQ record header naming the topic the publish failed against. */
    public static final String HEADER_ORIG_TOPIC = "x-orig-topic";

    /** DLQ record header naming the {@link FailureReason} allowlist value. */
    public static final String HEADER_FAILURE_REASON = "x-failure-reason";

    /** Max seconds to await broker ack before timing out an attempt. */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** Spring Kafka producer template (byte[] key + byte[] value). */
    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;

    /**
     * Constructs the publisher.
     *
     * @param kafkaTemplate Spring Kafka producer template wired by
     *                      {@code KafkaConfig}
     */
    public KafkaOutboxPublisher(final KafkaTemplate<byte[], byte[]> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(final OutboxEvent row,
                        final byte[] value,
                        final String contentType) {
        final ProducerRecord<byte[], byte[]> record = buildRecord(
                PRODUCTION_TOPIC, row.tenantId(), value, contentType);
        sendAndAwait(record);
    }

    @Override
    public void publishDlq(final OutboxEvent row,
                           final byte[] value,
                           final String contentType,
                           final String origTopic,
                           final String reason) {
        final ProducerRecord<byte[], byte[]> record = buildRecord(
                DLQ_TOPIC, row.tenantId(), value, contentType);
        record.headers().add(new RecordHeader(HEADER_ORIG_TOPIC,
                origTopic.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(HEADER_FAILURE_REASON,
                reason.getBytes(StandardCharsets.UTF_8)));
        sendAndAwait(record);
    }

    /**
     * Builds a Kafka producer record with the tenant id as the partition
     * key and the supplied envelope bytes as the value.
     *
     * @param topic       destination topic name
     * @param tenantId    tenant id supplying the partition key bytes
     * @param value       serialized envelope bytes
     * @param contentType content-type header value
     * @return ready-to-send producer record
     */
    private static ProducerRecord<byte[], byte[]> buildRecord(
            final String topic,
            final String tenantId,
            final byte[] value,
            final String contentType) {
        final byte[] key = tenantId.getBytes(StandardCharsets.UTF_8);
        final ProducerRecord<byte[], byte[]> record =
                new ProducerRecord<>(topic, null, key, value);
        record.headers().add(new RecordHeader(HEADER_CONTENT_TYPE,
                contentType.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    /**
     * Sends the record and blocks until the broker acknowledges. Any
     * non-ack outcome is re-thrown as a {@link RuntimeException} so the
     * caller (poller or DLQ branch) can translate to the row's
     * {@code attempts} / {@code DEAD} lifecycle.
     *
     * @param record producer record to send
     * @throws IllegalStateException if the producer thread is
     *         interrupted while awaiting the ack, or the broker
     *         rejects / times out the send
     */
    private void sendAndAwait(final ProducerRecord<byte[], byte[]> record) {
        try {
            this.kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kafka send interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException(
                    "kafka send failed: " + ex.getClass().getSimpleName(), ex);
        }
    }
}
