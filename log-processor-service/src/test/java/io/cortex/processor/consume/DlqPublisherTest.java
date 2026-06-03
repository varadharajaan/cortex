package io.cortex.processor.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.processor.parse.FailureReason;
import io.cortex.processor.parse.ParseException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit test for {@link DlqPublisher} (P5.1).
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>happy path: publish forwards original bytes byte-for-byte
 *       to the configured DLQ topic with the documented headers</li>
 *   <li>failure path: a KafkaTemplate that completes the future
 *       exceptionally surfaces an {@code IllegalStateException} so
 *       the consumer leaves the record un-acked</li>
 * </ul>
 */
class DlqPublisherTest {

    private static final String ORIG_TOPIC = "cortex.logs.events.v1";
    private static final String DLQ_TOPIC = "cortex.logs.events.v1.dlq";

    /**
     * Happy path: publish forwards original key + value byte-for-byte
     * and stamps the three documented headers on the DLQ record.
     *
     * @throws Exception if KafkaTemplate mocking fails
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishesOriginalRecordValueWithHeaders() throws Exception {
        final KafkaTemplate<byte[], byte[]> template = mock(KafkaTemplate.class);
        final CompletableFuture<SendResult<byte[], byte[]>> success =
                CompletableFuture.completedFuture(null);
        when(template.send(any(ProducerRecord.class))).thenReturn(success);

        final DlqPublisher publisher = new DlqPublisher(template, DLQ_TOPIC, ORIG_TOPIC);
        final byte[] originalValue = "raw-cloud-event".getBytes(StandardCharsets.UTF_8);
        final byte[] originalKey = "key-1".getBytes(StandardCharsets.UTF_8);
        final ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                ORIG_TOPIC, 0, 42L, originalKey, originalValue);

        publisher.publish(record, FailureReason.PARSE_ERROR,
                new ParseException("boom", new RuntimeException()));

        final ArgumentCaptor<ProducerRecord<byte[], byte[]>> sent =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        final ProducerRecord<byte[], byte[]> dlqRecord = sent.getValue();
        assertThat(dlqRecord.topic()).isEqualTo(DLQ_TOPIC);
        assertThat(dlqRecord.value()).isSameAs(originalValue);
        assertThat(dlqRecord.key()).isSameAs(originalKey);
        assertThat(headerValue(dlqRecord, "content-type"))
                .isEqualTo("application/cloudevents+json");
        assertThat(headerValue(dlqRecord, "x-orig-topic")).isEqualTo(ORIG_TOPIC);
        assertThat(headerValue(dlqRecord, "x-failure-reason"))
                .isEqualTo(FailureReason.PARSE_ERROR);
    }

    /** A broker-side NACK surfaces as IllegalStateException. */
    @Test
    @SuppressWarnings("unchecked")
    void wrapsBrokerNackInRuntimeException() {
        final KafkaTemplate<byte[], byte[]> template = mock(KafkaTemplate.class);
        final CompletableFuture<SendResult<byte[], byte[]>> failed =
                CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        when(template.send(any(ProducerRecord.class))).thenReturn(failed);

        final DlqPublisher publisher = new DlqPublisher(template, DLQ_TOPIC, ORIG_TOPIC);
        final ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                ORIG_TOPIC, 0, 1L, null, new byte[]{1, 2, 3});

        assertThatThrownBy(() -> publisher.publish(record,
                FailureReason.SCHEMA_VIOLATION, new RuntimeException("bad")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DLQ publish failed");
    }

    /**
     * Read the last value of the named header as UTF-8.
     *
     * @param record producer record
     * @param name   header name
     * @return header value or the JVM default if absent
     */
    private static String headerValue(
            final ProducerRecord<byte[], byte[]> record, final String name) {
        return new String(record.headers().lastHeader(name).value(),
                StandardCharsets.UTF_8);
    }
}
