package io.cortex.remediation.outcome;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka publisher for {@code cortex.remediation.outcomes.v1}.
 */
@Component
public class KafkaRemediationOutcomePublisher implements RemediationOutcomePublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10L;
    private static final String EVENT_SOURCE = "/cortex/log-remediation-service";
    private static final String EVENT_TYPE = "io.cortex.remediation.outcome.v1";
    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String CONTENT_TYPE_VALUE = "application/cloudevents+json";

    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();
    private final String topic;

    /**
     * Spring constructor.
     *
     * @param kafkaTemplate byte-array Kafka template
     * @param objectMapper JSON mapper
     * @param topic outcome topic
     */
    public KafkaRemediationOutcomePublisher(
            final KafkaTemplate<byte[], byte[]> kafkaTemplate,
            final ObjectMapper objectMapper,
            @Value("${cortex.remediation.outcomes.topic:cortex.remediation.outcomes.v1}")
            final String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    @SuppressFBWarnings(
            value = "REC_CATCH_EXCEPTION",
            justification = "Wrap send failures for engine-side logging.")
    public void publish(final RemediationOutcome outcome) {
        final byte[] payload;
        try {
            payload = envelopeBytes(outcome);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Outcome serialization failed", ex);
        }
        final byte[] key = outcome.eventId().getBytes(StandardCharsets.UTF_8);
        final ProducerRecord<byte[], byte[]> record =
                new ProducerRecord<>(this.topic, null, key, payload);
        record.headers().add(new RecordHeader(HEADER_CONTENT_TYPE,
                CONTENT_TYPE_VALUE.getBytes(StandardCharsets.UTF_8)));
        try {
            this.kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Outcome publish interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("Outcome publish failed", ex);
        }
    }

    private byte[] envelopeBytes(final RemediationOutcome outcome)
            throws JsonProcessingException {
        final byte[] data = this.objectMapper.writeValueAsBytes(outcome);
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId(outcome.eventId() + ":remediation:" + outcome.outcome())
                .withSource(URI.create(EVENT_SOURCE))
                .withType(EVENT_TYPE)
                .withTime(OffsetDateTime.now(this.clock).withOffsetSameInstant(ZoneOffset.UTC))
                .withSubject(outcome.tenantId())
                .withDataContentType("application/json")
                .withData(data)
                .build();
        return new JsonFormat().serialize(envelope);
    }
}
