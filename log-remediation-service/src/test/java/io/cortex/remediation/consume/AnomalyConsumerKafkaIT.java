package io.cortex.remediation.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.cortex.remediation.anomaly.AnomaliesRepository;
import io.cortex.remediation.metrics.RemediationMetrics;
import io.cortex.remediation.parse.AnomalyEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * End-to-end integration test for the P6.0 consume + dispatch
 * pipeline against a real Kafka broker (Testcontainers 3.8.0).
 *
 * <p>Scenario: a valid CloudEvent on {@code cortex.anomalies.v1} ->
 * {@code AnomalyConsumer} parses the envelope -> dispatches to the
 * default {@link io.cortex.remediation.dispatch.NoopRemediationDispatcher}
 * -> ticks the {@code cortex.remediation.dispatched_total} counter
 * with {@code channel=noop, outcome=skipped, tenant_id=<input>}.</p>
 *
 * <p>Same shape as the P5 {@code LogEventConsumerKafkaIT}:
 * {@code @DynamicPropertySource} wires
 * {@code spring.kafka.bootstrap-servers} because the Spring Boot
 * 3.3.6 ServiceConnection auto-config does not yet cover the
 * {@code org.testcontainers.kafka.KafkaContainer} apache-image
 * variant (LD78). Eureka is disabled at the property level.</p>
 */
@SpringBootTest
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AnomalyConsumerKafkaIT {

    /** Production handoff topic; pinned by ADR-0031. */
    private static final String TOPIC = "cortex.anomalies.v1";

    /** Remediation audit topic introduced by P21. */
    private static final String OUTCOME_TOPIC = "cortex.remediation.outcomes.v1";

    /** CloudEvent type identifier asserted on every anomaly envelope. */
    private static final String EVENT_TYPE = "io.cortex.anomaly.v1";

    /** CloudEvent source URI matching the upstream P5.4 producer. */
    private static final String EVENT_SOURCE = "/cortex/log-processor-service";

    /** Shared Apache Kafka 3.8.0 KRaft container. */
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    /** Shared Postgres 16 container for the anomaly read-model DataSource. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Wires Spring Boot's {@code spring.kafka.bootstrap-servers} to
     * the Testcontainers broker. Eureka is disabled in the same
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
                () -> "cortex.remediation.it-" + System.nanoTime());
    }

    /**
     * Pre-create the production topic on the Testcontainers broker
     * before the Spring context boots. Without this the consumer
     * can miss the first publish under KRaft auto-create latency.
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
            try {
                admin.createTopics(List.of(
                                new NewTopic(TOPIC, 1, (short) 1),
                                new NewTopic(OUTCOME_TOPIC, 1, (short) 1)))
                        .all().get();
            } catch (ExecutionException ex) {
                if (!(ex.getCause() instanceof TopicExistsException)) {
                    throw ex;
                }
            }
        }
    }

    /** Bound on Awaitility's polling window in seconds. */
    private static final int AWAIT_SECONDS = 30;

    /** Awaitility poll interval in milliseconds. */
    private static final long POLL_MILLIS = 500L;

    private final MeterRegistry meterRegistry;
    private final AnomaliesRepository anomaliesRepository;

    /**
     * Spring constructor injection.
     *
     * @param meterRegistry the autoconfigured registry hosting
     *                      {@code cortex.remediation.dispatched_total}
     * @param anomaliesRepository anomaly read-model repository
     */
    AnomalyConsumerKafkaIT(final MeterRegistry meterRegistry,
                           final AnomaliesRepository anomaliesRepository) {
        this.meterRegistry = meterRegistry;
        this.anomaliesRepository = anomaliesRepository;
    }

    /**
     * Publishes a single valid CloudEvent anomaly envelope and
     * awaits both the fallback dispatch counter and the P21 outcome
     * audit topic. Proves end-to-end parse + engine + outcome +
     * dispatch wiring against a real broker.
     *
     * @throws Exception on broker or serialization failure
     */
    @Test
    void validAnomalyEnvelopeTicksDispatchedCounterAndPublishesOutcome() throws Exception {
        final String tenantId = "tenant-it-" + System.nanoTime();
        final String eventId = "evt-it-1";
        final byte[] envelope = buildEnvelope(eventId, tenantId);
        publish(TOPIC, envelope);

        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(POLL_MILLIS))
                .untilAsserted(() -> assertThat(dispatchedCount(tenantId))
                        .isEqualTo(1.0d));
        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(POLL_MILLIS))
                .untilAsserted(() -> assertOutcome(eventId, tenantId));
        await().atMost(AWAIT_SECONDS, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(POLL_MILLIS))
                .untilAsserted(() -> assertThat(this.anomaliesRepository
                        .findByTenant(tenantId, null, null, 10))
                        .anySatisfy(row -> {
                            assertThat(row.eventId()).isEqualTo(eventId);
                            assertThat(row.service()).isEqualTo("checkout");
                            assertThat(row.severity()).isEqualTo("HIGH");
                        }));
    }

    /**
     * Reads the current dispatched-counter value scoped to a single
     * tenant; returns 0 when the timeseries has not yet registered.
     *
     * @param tenantId tag value identifying the tenant the counter is scoped to
     * @return the current count or 0 when the timeseries is absent
     */
    private double dispatchedCount(final String tenantId) {
        final Counter counter = this.meterRegistry
                .find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", "noop")
                .tag("outcome", "skipped")
                .tag("tenant_id", tenantId)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static void assertOutcome(final String eventId,
                                      final String tenantId) throws Exception {
        assertThat(readOutcomePayloads()).anySatisfy(payload -> {
            assertThat(payload.path("id").asText())
                    .isEqualTo(eventId + ":remediation:skipped");
            assertThat(payload.path("type").asText())
                    .isEqualTo("io.cortex.remediation.outcome.v1");
            assertThat(payload.path("subject").asText()).isEqualTo(tenantId);
            final JsonNode data = payload.path("data");
            assertThat(data.path("eventId").asText()).isEqualTo(eventId);
            assertThat(data.path("tenantId").asText()).isEqualTo(tenantId);
            assertThat(data.path("outcome").asText()).isEqualTo("skipped");
            assertThat(data.path("reason").asText()).isEqualTo("playbook:not-found");
        });
    }

    /**
     * Assembles a synthetic but contract-shaped CloudEvent envelope
     * matching what the P5 producer writes to
     * {@code cortex.anomalies.v1}. Holds the optional descriptive
     * fields constant so the helper stays under the 6-parameter cap.
     *
     * @param eventId  CloudEvent {@code id} and {@code data.eventId}
     * @param tenantId CloudEvent {@code subject} and {@code data.tenantId}
     * @return the serialized CloudEvent bytes
     * @throws Exception on Jackson or CloudEvents serialization failure
     */
    private static byte[] buildEnvelope(final String eventId,
                                        final String tenantId) throws Exception {
        final ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        final AnomalyEvent payload = new AnomalyEvent(eventId, tenantId,
                "HIGH", "burst", Instant.now(), "ERROR", "checkout", "503");
        final byte[] data = mapper.writeValueAsBytes(payload);
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId(eventId)
                .withSource(URI.create(EVENT_SOURCE))
                .withType(EVENT_TYPE)
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withSubject(tenantId)
                .withDataContentType("application/json")
                .withData(data)
                .build();
        return new JsonFormat().serialize(envelope);
    }

    /**
     * Publishes a single byte-array value to the supplied topic
     * through a short-lived {@link KafkaProducer}.
     *
     * @param topic destination topic
     * @param value record value bytes
     * @throws Exception on producer send or close failure
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

    private static List<JsonNode> readOutcomePayloads() throws Exception {
        final Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG,
                "outcome-reader-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(OUTCOME_TOPIC));
            final ConsumerRecords<byte[], byte[]> records =
                    consumer.poll(Duration.ofSeconds(2));
            final java.util.ArrayList<JsonNode> out = new java.util.ArrayList<>();
            for (final ConsumerRecord<byte[], byte[]> record : records) {
                out.add(mapper.readTree(record.value()));
            }
            return out;
        }
    }
}
