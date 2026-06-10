package io.cortex.remediation.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cortex.remediation.parse.AnomalyEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit tests for the remediation outcome Kafka publisher.
 */
class KafkaRemediationOutcomePublisherTest {

    private static final String TOPIC = "cortex.remediation.outcomes.v1";

    /** Publisher writes structured CloudEvents JSON with event id as Kafka key. */
    @Test
    void publishWritesCloudEventEnvelope() throws Exception {
        final KafkaTemplate<byte[], byte[]> kafka = kafkaTemplate();
        when(kafka.send(Mockito.<ProducerRecord<byte[], byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        final KafkaRemediationOutcomePublisher publisher =
                new KafkaRemediationOutcomePublisher(kafka, mapper, TOPIC);

        publisher.publish(outcome());

        final ProducerRecord<byte[], byte[]> record = capturedRecord(kafka);
        assertThat(record.topic()).isEqualTo(TOPIC);
        assertThat(new String(record.key(), StandardCharsets.UTF_8)).isEqualTo("evt-1");
        assertThat(record.headers().lastHeader("content-type").value())
                .asString(StandardCharsets.UTF_8)
                .isEqualTo("application/cloudevents+json");

        final JsonNode envelope = mapper.readTree(record.value());
        assertThat(envelope.path("id").asText())
                .isEqualTo("evt-1:remediation:fixed");
        assertThat(envelope.path("source").asText())
                .isEqualTo("/cortex/log-remediation-service");
        assertThat(envelope.path("type").asText())
                .isEqualTo("io.cortex.remediation.outcome.v1");
        assertThat(envelope.path("subject").asText()).isEqualTo("tenant-a");
        assertThat(envelope.path("data").path("outcome").asText()).isEqualTo("fixed");
        assertThat(envelope.path("data").path("playbookKey").asText())
                .isEqualTo("restart-service");
    }

    /** Send failures are surfaced to the engine as IllegalStateException. */
    @Test
    void sendFailureThrowsIllegalStateException() {
        final KafkaTemplate<byte[], byte[]> kafka = kafkaTemplate();
        final CompletableFuture<SendResult<byte[], byte[]>> failed =
                new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker down"));
        when(kafka.send(Mockito.<ProducerRecord<byte[], byte[]>>any()))
                .thenReturn(failed);
        final KafkaRemediationOutcomePublisher publisher =
                new KafkaRemediationOutcomePublisher(kafka,
                        new ObjectMapper().registerModule(new JavaTimeModule()), TOPIC);

        assertThatThrownBy(() -> publisher.publish(outcome()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Outcome publish failed");
    }

    private static RemediationOutcome outcome() {
        final Instant now = Instant.parse("2026-06-09T00:00:00Z");
        final AnomalyEvent event = new AnomalyEvent("evt-1", "tenant-a",
                "HIGH", "reason", now, "ERROR", "checkout", "boom",
                0.9d, "BURST", "restart-service");
        return RemediationOutcome.fixed(event, "applied", "restart-service", now);
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
