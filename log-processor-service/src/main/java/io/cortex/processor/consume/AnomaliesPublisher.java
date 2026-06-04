package io.cortex.processor.consume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.cortex.processor.classify.Classification;
import io.cortex.processor.parse.RawLogEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes classifier anomaly verdicts to the production handoff
 * topic {@code cortex.anomalies.v1} (P5.4 / ADR-0031) so the
 * downstream P6 {@code log-remediation-service} can dispatch
 * playbooks (Slack / PagerDuty / Jira) without coupling to the
 * upstream classification pipeline.
 *
 * <p>Mirrors the {@link DlqPublisher} shape exactly: synchronous
 * send with a 10-second bounded wait, on failure throws an
 * {@link IllegalStateException} so the consumer's caller leaves
 * the source record un-acked and Kafka rebalance redelivers on the
 * next poll. <strong>No outbox table is required (see ADR-0031 +
 * LD117): the source of the verdict is itself a Kafka record
 * consumed with {@code enable-auto-commit=false} + manual ack mode,
 * so the Kafka offset is the durability mechanism.</strong></p>
 *
 * <p>Envelope contract (CloudEvents 1.0 structured-mode JSON):</p>
 * <ul>
 *   <li>{@code specversion} -- {@code "1.0"} (set by the builder)</li>
 *   <li>{@code id} -- mirrors {@link RawLogEvent#eventId()} so the
 *       downstream consumer can dedupe end-to-end on the same key
 *       the producer used</li>
 *   <li>{@code source} -- {@code /cortex/log-processor-service}</li>
 *   <li>{@code type} -- {@code io.cortex.anomaly.v1}</li>
 *   <li>{@code time} -- now (publish-time)</li>
 *   <li>{@code datacontenttype} -- {@code application/json}</li>
 *   <li>{@code subject} -- {@link RawLogEvent#tenantId()}</li>
 *   <li>{@code data} -- JSON object carrying the deterministic
 *       fields required by the P6 remediation contract: {@code
 *       eventId, tenantId, severity, reason, ts, level, service,
 *       message}</li>
 * </ul>
 *
 * <p>Kafka headers ({@code content-type=application/cloudevents+json},
 * {@code x-source-topic=${cortex.processor.topic}}) match the
 * P4.4b producer contract so downstream consumers can dispatch on
 * header without inspecting the payload.</p>
 */
@Component
public class AnomaliesPublisher {

    private static final Logger LOG =
            LoggerFactory.getLogger(AnomaliesPublisher.class);

    /** Bounded send-timeout so the consumer thread never hangs. */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** Wire header names per ADR-0031 D4 (mirror of ADR-0026 / ADR-0027). */
    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String HEADER_SOURCE_TOPIC = "x-source-topic";
    private static final String CONTENT_TYPE_VALUE = "application/cloudevents+json";

    /** Stable CloudEvent source attribute identifying this producer. */
    private static final String EVENT_SOURCE = "/cortex/log-processor-service";

    /** Stable CloudEvent type attribute identifying the anomaly schema. */
    private static final String EVENT_TYPE = "io.cortex.anomaly.v1";

    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String anomaliesTopic;
    private final String sourceTopic;

    /**
     * Spring constructor.
     *
     * @param kafkaTemplate  the byte[]/byte[] KafkaTemplate produced
     *                       by {@code ProcessorKafkaProducerConfig};
     *                       shared with {@link DlqPublisher}
     * @param objectMapper   autoconfigured Jackson mapper used to
     *                       serialise the CloudEvent {@code data}
     *                       payload
     * @param anomaliesTopic anomalies topic name from
     *                       {@code cortex.processor.anomalies.topic}
     * @param sourceTopic    source topic from
     *                       {@code cortex.processor.topic} (stamped
     *                       on the {@code x-source-topic} header)
     */
    @Autowired public AnomaliesPublisher(
            final KafkaTemplate<byte[], byte[]> kafkaTemplate,
            final ObjectMapper objectMapper,
            @Value("${cortex.processor.anomalies.topic}") final String anomaliesTopic,
            @Value("${cortex.processor.topic}") final String sourceTopic) {
        this(kafkaTemplate, objectMapper, Clock.systemUTC(), anomaliesTopic, sourceTopic);
    }

    /**
     * Test-seam constructor accepting an explicit clock so unit tests
     * can assert a deterministic CloudEvent {@code time} attribute.
     *
     * @param kafkaTemplate  the byte[]/byte[] KafkaTemplate
     * @param objectMapper   Jackson mapper
     * @param clock          UTC clock (must not be {@code null})
     * @param anomaliesTopic anomalies topic name
     * @param sourceTopic    source topic name
     */
    AnomaliesPublisher(final KafkaTemplate<byte[], byte[]> kafkaTemplate,
                       final ObjectMapper objectMapper,
                       final Clock clock,
                       final String anomaliesTopic,
                       final String sourceTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.anomaliesTopic = anomaliesTopic;
        this.sourceTopic = sourceTopic;
    }

    /**
     * Publish the supplied event + verdict as a CloudEvents 1.0
     * envelope to the configured anomalies topic. The send is
     * synchronous with a bounded {@value #SEND_TIMEOUT_SECONDS}-second
     * wait. A send timeout or broker NACK surfaces as an unchecked
     * {@link IllegalStateException} so the consumer's catch block
     * leaves the source record un-acked and Kafka redelivery
     * re-attempts the publish on the next poll.
     *
     * @param event   the parsed log event whose verdict is being
     *                published; must not be {@code null} and must
     *                carry a non-blank {@code eventId} + non-blank
     *                {@code tenantId}
     * @param verdict the classifier verdict; must not be {@code null}
     *                and must satisfy {@code anomaly()==true}
     *                (callers gate this method on the anomaly branch)
     * @throws IllegalStateException if the publish is interrupted,
     *                               times out after the bounded wait,
     *                               or is rejected by the broker
     */
    @SuppressFBWarnings(
            value = "REC_CATCH_EXCEPTION",
            justification = "Wrap any send-time failure into a uniform RuntimeException"
                    + " so the consumer's catch block leaves the source record un-acked.")
    public void publish(final RawLogEvent event, final Classification verdict) {
        final byte[] payload;
        try {
            payload = buildEnvelopeBytes(event, verdict);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to serialise anomaly CloudEvent for eventId="
                            + event.eventId(), ex);
        }
        final ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(
                this.anomaliesTopic, null,
                event.eventId().getBytes(StandardCharsets.UTF_8), payload);
        record.headers().add(new RecordHeader(HEADER_CONTENT_TYPE,
                CONTENT_TYPE_VALUE.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(HEADER_SOURCE_TOPIC,
                this.sourceTopic.getBytes(StandardCharsets.UTF_8)));
        try {
            this.kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOG.info("Published anomaly to {} eventId={} severity={}",
                    this.anomaliesTopic, event.eventId(), verdict.severity());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Anomaly publish interrupted for topic=" + this.anomaliesTopic, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException(
                    "Anomaly publish failed for topic=" + this.anomaliesTopic, ex);
        }
    }

    /**
     * Build the CloudEvents 1.0 structured-mode JSON envelope bytes
     * for the supplied event + verdict. Field order in the
     * {@code data} object is deterministic so byte-for-byte equality
     * tests are reproducible.
     *
     * @param event   parsed log event
     * @param verdict classifier verdict
     * @return serialised CloudEvent bytes
     * @throws JsonProcessingException if the {@code data} payload fails to serialise
     */
    private byte[] buildEnvelopeBytes(final RawLogEvent event,
                                      final Classification verdict)
            throws JsonProcessingException {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", event.eventId());
        data.put("tenantId", event.tenantId());
        data.put("severity", verdict.severity());
        data.put("reason", verdict.reason());
        data.put("ts", event.ts() == null ? null : event.ts().toString());
        data.put("level", event.level());
        data.put("service", event.service());
        data.put("message", event.message());
        final byte[] dataBytes = this.objectMapper.writeValueAsBytes(data);
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId(event.eventId())
                .withSource(URI.create(EVENT_SOURCE))
                .withType(EVENT_TYPE)
                .withTime(OffsetDateTime.now(this.clock).withOffsetSameInstant(ZoneOffset.UTC))
                .withSubject(event.tenantId())
                .withDataContentType("application/json")
                .withData(dataBytes)
                .build();
        return new JsonFormat().serialize(envelope);
    }
}
