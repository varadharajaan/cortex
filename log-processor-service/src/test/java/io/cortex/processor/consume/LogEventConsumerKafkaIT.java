package io.cortex.processor.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.cortex.processor.classify.AnomalyClassifier;
import io.cortex.processor.classify.Classification;
import io.cortex.processor.parse.RawLogEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * End-to-end integration test for the P5.1 parse + validate + DLQ
 * pipeline + P5.4 anomaly handoff against a real Kafka broker
 * (Testcontainers 3.8.0).
 *
 * <p>Scenarios:</p>
 * <ol>
 *   <li>Valid CloudEvent on {@code cortex.logs.events.v1} ->
 *       {@code cortex.processor.events.parsed_total} ticks +
 *       nothing on the DLQ topic.</li>
 *   <li>Malformed envelope -> one record on
 *       {@code cortex.logs.events.v1.dlq} with header
 *       {@code x-failure-reason=parse_error}.</li>
 *   <li>Schema-violating event -> one record on the DLQ topic with
 *       header {@code x-failure-reason=schema_violation}.</li>
 *   <li>ERROR-level event classified as anomaly by the test stub
 *       classifier -> one CloudEvent on
 *       {@code cortex.anomalies.v1} with the documented headers +
 *       {@code cortex.processor.anomalies.published_total} ticks
 *       (P5.4 / ADR-0031).</li>
 * </ol>
 *
 * <p>Same shape as the P4.4b {@code OutboxPollerKafkaIT} in
 * log-ingest-service: {@code @DynamicPropertySource} wires
 * {@code spring.kafka.bootstrap-servers} because the Spring Boot
 * 3.3.6 ServiceConnection auto-config does not yet cover the
 * {@code org.testcontainers.kafka.KafkaContainer} apache-image
 * variant (LD78).</p>
 *
 * <p>The {@link StubAnomalyClassifierConfig} test config overrides
 * the default {@code NoopAnomalyClassifier} with one that flags any
 * event whose {@code level} is {@code "ERROR"} as an anomaly so
 * scenario 4 exercises the publish path without requiring a live
 * Spring AI model.</p>
 *
 * <p>Eureka is disabled at the property level; the test JVM has
 * no registry on :8761.</p>
 */
@SpringBootTest
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(LogEventConsumerKafkaIT.StubAnomalyClassifierConfig.class)
class LogEventConsumerKafkaIT {

    /** Production topic; pinned by ADR-0026. */
    private static final String TOPIC = "cortex.logs.events.v1";

    /** DLQ topic; pinned by ADR-0027 contract mirror. */
    private static final String DLQ_TOPIC = "cortex.logs.events.v1.dlq";

    /** Anomalies handoff topic; pinned by ADR-0031 (P5.4). */
    private static final String ANOMALIES_TOPIC = "cortex.anomalies.v1";

    /** CloudEvent type identifier asserted on every consumed envelope. */
    private static final String EVENT_TYPE = "io.cortex.logs.event.v1";

    /** CloudEvent type identifier asserted on every anomaly envelope. */
    private static final String ANOMALY_TYPE = "io.cortex.anomaly.v1";

    /** CloudEvent source URI of the upstream ingest producer. */
    private static final String EVENT_SOURCE = "/cortex/log-ingest-service";

    /** CloudEvent source URI of the anomaly publisher (P5.4 / ADR-0031). */
    private static final String ANOMALY_SOURCE = "/cortex/log-processor-service";

    /** Shared Apache Kafka 3.8.0 KRaft container. */
    @Container
    static KafkaContainer kafka =
            new KafkaContainer("apache/kafka:3.8.0");

    /**
     * Wires Spring Boot's {@code spring.kafka.bootstrap-servers}
     * to the Testcontainers broker. Eureka is disabled in the same
     * source so the registration thread doesn't block context boot.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void kafkaProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("eureka.client.register-with-eureka", () -> "false");
        registry.add("eureka.client.fetch-registry", () -> "false");
        // Use a unique consumer group per test class run so we always
        // start at offset 0 and don't share state with the local-dev
        // group.
        registry.add("spring.kafka.consumer.group-id",
                () -> "cortex.processor.it-" + System.nanoTime());
    }

    /**
     * Pre-create both the production and DLQ topics on the
     * Testcontainers broker before the Spring context boots. Without
     * this, the idempotent DLQ producer can deadlock on first send
     * when KRaft auto-create is slow to materialise the new topic,
     * which would block the consumer thread and trip the awaitility
     * timeout in the schema_violation + parse_error scenarios.
     *
     * @throws ExecutionException   if the create call surfaces a
     *                              non-recoverable broker error
     * @throws InterruptedException if the test thread is interrupted
     */
    @BeforeAll
    static void preCreateTopics() throws ExecutionException, InterruptedException {
        final Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            for (final NewTopic topic : List.of(
                    new NewTopic(TOPIC, 1, (short) 1),
                    new NewTopic(DLQ_TOPIC, 1, (short) 1),
                    new NewTopic(ANOMALIES_TOPIC, 1, (short) 1))) {
                try {
                    admin.createTopics(List.of(topic)).all().get();
                } catch (ExecutionException ex) {
                    if (!(ex.getCause() instanceof TopicExistsException)) {
                        throw ex;
                    }
                }
            }
        }
        dlqConsumer = newDrainConsumer("dlq-it-");
        final TopicPartition dlqPartition = new TopicPartition(DLQ_TOPIC, 0);
        dlqConsumer.assign(List.of(dlqPartition));
        dlqConsumer.seekToBeginning(List.of(dlqPartition));
        dlqConsumer.position(dlqPartition);

        anomaliesConsumer = newDrainConsumer("anomalies-it-");
        final TopicPartition anomaliesPartition = new TopicPartition(ANOMALIES_TOPIC, 0);
        anomaliesConsumer.assign(List.of(anomaliesPartition));
        anomaliesConsumer.seekToBeginning(List.of(anomaliesPartition));
        anomaliesConsumer.position(anomaliesPartition);
    }

    /**
     * Close the shared DLQ + anomalies consumers once the class is
     * finished so the test JVM does not leak network clients.
     */
    @AfterAll
    static void closeDrainConsumers() {
        if (dlqConsumer != null) {
            dlqConsumer.close(Duration.ofSeconds(2));
            dlqConsumer = null;
        }
        if (anomaliesConsumer != null) {
            anomaliesConsumer.close(Duration.ofSeconds(2));
            anomaliesConsumer = null;
        }
    }

    /**
     * Shared DLQ consumer used by every {@link #drainDlq(Duration)}
     * call. Uses explicit {@code assign} + {@code seekToBeginning}
     * so each drain starts at offset 0 without paying the cost of a
     * fresh consumer group rebalance on every poll.
     */
    private static KafkaConsumer<byte[], byte[]> dlqConsumer;

    /**
     * Shared anomalies-topic consumer used by every
     * {@link #drainAnomalies(Duration)} call (P5.4 / ADR-0031).
     */
    private static KafkaConsumer<byte[], byte[]> anomaliesConsumer;

    private final MeterRegistry meterRegistry;

    /**
     * Spring constructor injection; the test extension resolves the
     * {@link MeterRegistry} from the application context.
     *
     * @param meterRegistry the autoconfigured registry hosting
     *                      {@code cortex.processor.events.parsed_total}
     */
    LogEventConsumerKafkaIT(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Valid CloudEvent path: the consumer parses + validates +
     * classifies the event, the parsed counter ticks, nothing lands
     * on the DLQ.
     *
     * @throws Exception if the producer thread fails to publish the
     *                   envelope
     */
    @Test
    @Order(1)
    void validCloudEventIncrementsParsedCounter() throws Exception {
        final double parsedBefore = parsedCount();
        final byte[] envelope = buildEnvelopeBytes("evt-it-valid", "cortex-dev",
                "INFO", "checkout", "hello");
        publish(TOPIC, envelope);

        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        assertThat(parsedCount()).isGreaterThan(parsedBefore));
        // Drain the DLQ briefly to confirm no record landed; if the
        // DLQ has the topic we'd see at least one record within the
        // 3s budget.
        assertThat(drainDlq(Duration.ofSeconds(3)))
                .as("no DLQ records expected for a valid event")
                .isEmpty();
    }

    /**
     * Parse-failure path: garbage bytes on the production topic
     * route to the DLQ with {@code x-failure-reason=parse_error}.
     *
     * @throws Exception if the producer thread fails to publish the
     *                   garbage payload
     */
    @Test
    @Order(2)
    void malformedEnvelopeRoutesToDlqWithParseErrorReason() throws Exception {
        publish(TOPIC, "not-a-cloud-event".getBytes(StandardCharsets.UTF_8));

        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    final List<ConsumerRecord<byte[], byte[]>> records =
                            drainDlq(Duration.ofSeconds(2));
                    assertThat(records).isNotEmpty();
                    final ConsumerRecord<byte[], byte[]> first = records.get(0);
                    assertThat(headerValue(first, "x-failure-reason"))
                            .isEqualTo("parse_error");
                    assertThat(headerValue(first, "x-orig-topic"))
                            .isEqualTo(TOPIC);
                    assertThat(headerValue(first, "content-type"))
                            .isEqualTo("application/cloudevents+json");
                });
    }

    /**
     * Schema-violation path: a well-formed CloudEvent whose
     * {@code level} field is not in the allowlist routes to the
     * DLQ with {@code x-failure-reason=schema_violation}.
     *
     * @throws Exception if the producer thread fails to publish the
     *                   envelope
     */
    @Test
    @Order(3)
    void schemaViolatingEventRoutesToDlqWithSchemaReason() throws Exception {
        // FATAL is not on the LogLevel allowlist.
        final byte[] envelope = buildEnvelopeBytes("evt-it-bad-level", "cortex-dev",
                "FATAL", "checkout", "hello");
        publish(TOPIC, envelope);

        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    final List<ConsumerRecord<byte[], byte[]>> records =
                            drainDlq(Duration.ofSeconds(2));
                    assertThat(records).isNotEmpty();
                    final boolean sawSchemaViolation = records.stream()
                            .anyMatch(r -> "schema_violation".equals(
                                    headerValue(r, "x-failure-reason")));
                    assertThat(sawSchemaViolation).isTrue();
                });
    }

    /**
     * P5.4 / ADR-0031: an ERROR-level event classified as anomaly
     * by the stub classifier publishes a CloudEvent envelope to
     * {@code cortex.anomalies.v1} with the documented headers + the
     * {@code cortex.processor.anomalies.published_total} counter
     * ticks. End-to-end proof of the synchronous-publish handoff
     * for the future P6 remediation service.
     *
     * @throws Exception if the producer thread fails to publish the
     *                   envelope
     */
    @Test
    @Order(4)
    @SuppressWarnings("checkstyle:MethodLength")
    void errorEnvelopeIsClassifiedAndPublishedToAnomaliesTopic() throws Exception {
        final double publishedBefore = anomaliesPublishedCount();
        final String eventId = "evt-it-anomaly";
        final byte[] envelope = buildEnvelopeBytes(eventId, "cortex-dev",
                "ERROR", "checkout", "NPE in pricing engine");
        publish(TOPIC, envelope);

        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    final List<ConsumerRecord<byte[], byte[]>> records =
                            drainAnomalies(Duration.ofSeconds(2));
                    assertThat(records).isNotEmpty();
                    final ConsumerRecord<byte[], byte[]> last = records.get(records.size() - 1);
                    assertThat(headerValue(last, "content-type"))
                            .isEqualTo("application/cloudevents+json");
                    assertThat(headerValue(last, "x-source-topic"))
                            .isEqualTo(TOPIC);
                    assertThat(last.key())
                            .as("anomaly record key is the eventId for downstream dedup")
                            .isEqualTo(eventId.getBytes(StandardCharsets.UTF_8));
                    final ObjectMapper mapper = new ObjectMapper();
                    final JsonNode envelopeNode = mapper.readTree(last.value());
                    assertThat(envelopeNode.get("specversion").asText()).isEqualTo("1.0");
                    assertThat(envelopeNode.get("id").asText()).isEqualTo(eventId);
                    assertThat(envelopeNode.get("source").asText()).isEqualTo(ANOMALY_SOURCE);
                    assertThat(envelopeNode.get("type").asText()).isEqualTo(ANOMALY_TYPE);
                    assertThat(envelopeNode.get("subject").asText()).isEqualTo("cortex-dev");
                    assertThat(envelopeNode.get("datacontenttype").asText())
                            .isEqualTo("application/json");
                    final JsonNode data = envelopeNode.get("data");
                    assertThat(data.get("eventId").asText()).isEqualTo(eventId);
                    assertThat(data.get("tenantId").asText()).isEqualTo("cortex-dev");
                    assertThat(data.get("severity").asText()).isEqualTo("HIGH");
                    assertThat(data.get("reason").asText()).isEqualTo("error-level-stub");
                    assertThat(data.get("level").asText()).isEqualTo("ERROR");
                    assertThat(data.get("service").asText()).isEqualTo("checkout");
                    assertThat(data.get("message").asText()).isEqualTo("NPE in pricing engine");
                    assertThat(anomaliesPublishedCount())
                            .isGreaterThan(publishedBefore);
                });
    }

    /**
     * Read the parsed-total counter; returns {@code 0.0} when the
     * counter has not yet been registered.
     *
     * @return current value of the parsed counter
     */
    private double parsedCount() {
        try {
            return this.meterRegistry.get("cortex.processor.events.parsed_total")
                    .counter().count();
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    /**
     * Read the P5.4 anomalies-published counter; returns {@code 0.0}
     * when the counter has not yet been registered.
     *
     * @return current value of the anomalies-published counter
     */
    private double anomaliesPublishedCount() {
        try {
            return this.meterRegistry.get("cortex.processor.anomalies.published_total")
                    .counter().count();
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    /**
     * Build a CloudEvent envelope byte payload with the supplied
     * log-event fields.
     *
     * @param id        eventId
     * @param tenantId  tenant id (also used as CloudEvent subject)
     * @param level     log level
     * @param service   originating service name
     * @param message   log message body
     * @return CloudEvents 1.0 structured-mode JSON bytes
     * @throws Exception if the envelope serialisation fails
     */
    private static byte[] buildEnvelopeBytes(
            final String id, final String tenantId, final String level,
            final String service, final String message) throws Exception {
        final String dataJson = "{"
                + "\"tenantId\":\"" + tenantId + "\","
                + "\"eventId\":\"" + id + "\","
                + "\"ts\":\"2026-06-03T12:00:00Z\","
                + "\"level\":\"" + level + "\","
                + "\"service\":\"" + service + "\","
                + "\"message\":\"" + message + "\","
                + "\"labels\":{},"
                + "\"idempotencyKey\":\"idk-" + id + "\","
                + "\"receivedAt\":\"2026-06-03T12:00:01Z\""
                + "}";
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId(id)
                .withSource(URI.create(EVENT_SOURCE))
                .withType(EVENT_TYPE)
                .withSubject(tenantId)
                .withDataContentType("application/json")
                .withData(dataJson.getBytes(StandardCharsets.UTF_8))
                .build();
        return new JsonFormat().serialize(envelope);
    }

    /**
     * Synchronously publish a single record to the supplied topic.
     *
     * @param topic destination topic
     * @param value record value bytes
     * @throws Exception if the producer fails to deliver
     */
    private static void publish(final String topic, final byte[] value) throws Exception {
        final Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class.getName());
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, null, value)).get();
            producer.flush();
        }
    }

    /**
     * Drain the DLQ topic using the shared consumer with an explicit
     * {@code seekToBeginning} on every call so the caller always sees
     * the full DLQ contents from offset 0.
     *
     * @param timeout poll timeout
     * @return records currently visible on the DLQ
     */
    private static List<ConsumerRecord<byte[], byte[]>> drainDlq(final Duration timeout) {
        final TopicPartition partition = new TopicPartition(DLQ_TOPIC, 0);
        dlqConsumer.seekToBeginning(List.of(partition));
        final ConsumerRecords<byte[], byte[]> records = dlqConsumer.poll(timeout);
        final java.util.ArrayList<ConsumerRecord<byte[], byte[]>> out =
                new java.util.ArrayList<>();
        for (ConsumerRecord<byte[], byte[]> r : records) {
            out.add(r);
        }
        return out;
    }

    /**
     * Drain the {@code cortex.anomalies.v1} topic using the shared
     * consumer with an explicit {@code seekToBeginning} on every
     * call so the caller always sees the full anomalies stream from
     * offset 0 (P5.4 / ADR-0031).
     *
     * @param timeout poll timeout
     * @return records currently visible on the anomalies topic
     */
    private static List<ConsumerRecord<byte[], byte[]>> drainAnomalies(
            final Duration timeout) {
        final TopicPartition partition = new TopicPartition(ANOMALIES_TOPIC, 0);
        anomaliesConsumer.seekToBeginning(List.of(partition));
        final ConsumerRecords<byte[], byte[]> records = anomaliesConsumer.poll(timeout);
        final java.util.ArrayList<ConsumerRecord<byte[], byte[]>> out =
                new java.util.ArrayList<>();
        for (ConsumerRecord<byte[], byte[]> r : records) {
            out.add(r);
        }
        return out;
    }

    /**
     * Build a fresh drain consumer with a unique group id (never
     * actually used for group coordination because {@code assign} is
     * used in place of {@code subscribe}).
     *
     * @param groupIdPrefix prefix for the unique group id
     * @return ready-to-assign consumer
     */
    private static KafkaConsumer<byte[], byte[]> newDrainConsumer(
            final String groupIdPrefix) {
        final Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupIdPrefix + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    /**
     * Read the last value of the named header as UTF-8.
     *
     * @param record consumer record
     * @param name   header name
     * @return header value or {@code null} when absent
     */
    private static String headerValue(
            final ConsumerRecord<byte[], byte[]> record, final String name) {
        final Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /**
     * Test-only Spring config that swaps the default
     * {@code NoopAnomalyClassifier} for a deterministic stub that
     * flags any event whose {@code level} equals {@code "ERROR"} as
     * an anomaly. Lets the IT exercise the P5.4 publish path without
     * requiring a live Spring AI model.
     */
    @TestConfiguration
    static class StubAnomalyClassifierConfig {

        /**
         * Replaces the default NoopAnomalyClassifier with a stub
         * that flags ERROR-level events as HIGH-severity anomalies.
         *
         * @return primary AnomalyClassifier bean
         */
        @Bean
        @Primary
        AnomalyClassifier stubAnomalyClassifier() {
            return new AnomalyClassifier() {
                @Override
                public Classification classify(final RawLogEvent event) {
                    if ("ERROR".equals(event.level())) {
                        return new Classification(true, "HIGH", "error-level-stub");
                    }
                    return Classification.none();
                }
            };
        }
    }
}
