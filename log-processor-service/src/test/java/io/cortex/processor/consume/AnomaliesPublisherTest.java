package io.cortex.processor.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.processor.classify.Classification;
import io.cortex.processor.parse.RawLogEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit test for {@link AnomaliesPublisher} (P5.4 / ADR-0031).
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>happy path: publish builds a CloudEvents 1.0 envelope with
 *       deterministic field shape + stamps the documented headers +
 *       sets the record key to the eventId for downstream dedupe</li>
 *   <li>failure path: a KafkaTemplate that completes the future
 *       exceptionally surfaces an {@code IllegalStateException} so
 *       the consumer leaves the source record un-acked (LD117)</li>
 * </ul>
 */
class AnomaliesPublisherTest {

    private static final String SOURCE_TOPIC = "cortex.logs.events.v1";
    private static final String ANOMALIES_TOPIC = "cortex.anomalies.v1";
    private static final Instant FIXED_NOW =
            Instant.parse("2026-06-04T10:00:00Z");

    /**
     * Happy path: publish builds a structured-mode CloudEvent
     * envelope, stamps the two documented headers, uses the eventId
     * as the record key, and ticks no extra side effects.
     *
     * @throws Exception if KafkaTemplate mocking fails
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishesCloudEventEnvelopeWithHeadersAndKey() throws Exception {
        final KafkaTemplate<byte[], byte[]> template = mock(KafkaTemplate.class);
        final CompletableFuture<SendResult<byte[], byte[]>> success =
                CompletableFuture.completedFuture(null);
        when(template.send(any(ProducerRecord.class))).thenReturn(success);

        final ObjectMapper mapper = new ObjectMapper();
        final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        final AnomaliesPublisher publisher = new AnomaliesPublisher(
                template, mapper, clock, ANOMALIES_TOPIC, SOURCE_TOPIC);

        final RawLogEvent event = new RawLogEvent(
                "cortex-dev", "evt-42",
                Instant.parse("2026-06-04T09:59:58Z"),
                "ERROR", "checkout", "NPE in cart",
                Map.of(), "idk-42",
                Instant.parse("2026-06-04T09:59:59Z"));
        final Classification verdict = new Classification(true, "HIGH", "stack-trace");

        publisher.publish(event, verdict);

        final ArgumentCaptor<ProducerRecord<byte[], byte[]>> sent =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        final ProducerRecord<byte[], byte[]> record = sent.getValue();
        assertThat(record.topic()).isEqualTo(ANOMALIES_TOPIC);
        assertThat(record.key())
                .as("record key is the eventId so downstream consumers can dedupe")
                .isEqualTo("evt-42".getBytes(StandardCharsets.UTF_8));
        assertThat(headerValue(record, "content-type"))
                .isEqualTo("application/cloudevents+json");
        assertThat(headerValue(record, "x-source-topic")).isEqualTo(SOURCE_TOPIC);

        final JsonNode envelope = mapper.readTree(record.value());
        assertThat(envelope.get("specversion").asText()).isEqualTo("1.0");
        assertThat(envelope.get("id").asText()).isEqualTo("evt-42");
        assertThat(envelope.get("source").asText())
                .isEqualTo("/cortex/log-processor-service");
        assertThat(envelope.get("type").asText()).isEqualTo("io.cortex.anomaly.v1");
        assertThat(envelope.get("subject").asText()).isEqualTo("cortex-dev");
        assertThat(envelope.get("datacontenttype").asText())
                .isEqualTo("application/json");
        assertThat(envelope.get("time").asText())
                .as("time attribute uses the injected clock")
                .startsWith("2026-06-04T10:00:00");

        final JsonNode data = envelope.get("data");
        assertThat(data.get("eventId").asText()).isEqualTo("evt-42");
        assertThat(data.get("tenantId").asText()).isEqualTo("cortex-dev");
        assertThat(data.get("severity").asText()).isEqualTo("HIGH");
        assertThat(data.get("reason").asText()).isEqualTo("stack-trace");
        assertThat(data.get("ts").asText()).isEqualTo("2026-06-04T09:59:58Z");
        assertThat(data.get("level").asText()).isEqualTo("ERROR");
        assertThat(data.get("service").asText()).isEqualTo("checkout");
        assertThat(data.get("message").asText()).isEqualTo("NPE in cart");
    }

    /** A broker-side NACK surfaces as IllegalStateException so the consumer rewinds (LD117). */
    @Test
    @SuppressWarnings("unchecked")
    void wrapsBrokerNackInRuntimeException() {
        final KafkaTemplate<byte[], byte[]> template = mock(KafkaTemplate.class);
        final CompletableFuture<SendResult<byte[], byte[]>> failed =
                CompletableFuture.failedFuture(new IllegalStateException("broker down"));
        when(template.send(any(ProducerRecord.class))).thenReturn(failed);

        final AnomaliesPublisher publisher = new AnomaliesPublisher(
                template, new ObjectMapper(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC),
                ANOMALIES_TOPIC, SOURCE_TOPIC);

        final RawLogEvent event = new RawLogEvent(
                "cortex-dev", "evt-fail",
                Instant.parse("2026-06-04T09:59:58Z"),
                "ERROR", "checkout", "boom",
                Map.of(), "idk-fail",
                Instant.parse("2026-06-04T09:59:59Z"));

        assertThatThrownBy(() -> publisher.publish(event,
                new Classification(true, "HIGH", "spike")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Anomaly publish failed");
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
