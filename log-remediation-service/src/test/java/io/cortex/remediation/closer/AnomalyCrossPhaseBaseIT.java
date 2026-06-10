package io.cortex.remediation.closer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * P6.1a cross-phase closer base class for per-channel
 * {@code @SpringBootTest} subclasses (slack / pagerduty / jira).
 *
 * <p>Owns the singleton Testcontainers Kafka container + the
 * singleton in-process WireMock server that every subclass shares
 * for the duration of the {@code failsafe} run -- if each subclass
 * spun up its own pair the test phase would balloon by 3x in
 * cold-start cost (~30 s per subclass). Manual {@code static}
 * lifecycle (no {@code @Testcontainers}) is the standard
 * Testcontainers singleton-container pattern; reuse relies on Ryuk
 * for reaping.</p>
 *
 * <p>{@link DynamicPropertySource} wires the shared
 * {@code spring.kafka.bootstrap-servers} + disables Eureka for the
 * embedded contexts. Each subclass MUST add its own
 * {@code @DynamicPropertySource} static method that registers the
 * provider-specific properties
 * ({@code cortex.remediation.dispatcher.provider},
 * {@code cortex.remediation.topic}, the channel webhook /
 * routing-key / api-token, and a unique
 * {@code spring.kafka.consumer.group-id} so the consumer always
 * starts at offset 0).</p>
 *
 * <p>The WireMock journal + stubs are reset between every test via
 * {@link #resetWireMock()} so happy + transient cases in the same
 * subclass do not contaminate each other.</p>
 *
 * <p>Per LD123 every credential / api-token value embedded in
 * subclass property sources is intentionally NEUTRAL
 * ({@code test@example.com}, all-zeros routing keys, the literal
 * string {@code placeholder-token-not-real}) so the GitGuardian
 * pre-commit hook never trips on the closer fixtures.</p>
 */
abstract class AnomalyCrossPhaseBaseIT {

    /** Shared Apache Kafka 3.8.0 KRaft container (singleton). */
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    /** Shared Postgres 16 container for the P9.3 anomaly read model. */
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Shared in-process WireMock server on a dynamic port (singleton). */
    protected static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    /** Production DLT topic name pinned by ADR-0032 D4 (always empty in P6.1a). */
    protected static final String DLT_TOPIC = "cortex.anomalies.v1.dlq";

    /** CloudEvent type identifier asserted on every anomaly envelope. */
    protected static final String EVENT_TYPE = "io.cortex.anomaly.v1";

    /** CloudEvent source URI matching the upstream P5.4 producer. */
    protected static final String EVENT_SOURCE = "/cortex/log-processor-service";

    /** Awaitility upper bound -- cold-start consumer group join can exceed 5 s on CI. */
    protected static final Duration AWAIT = Duration.ofSeconds(20);

    /** Awaitility poll interval. */
    protected static final Duration POLL = Duration.ofMillis(200);

    static {
        POSTGRES.start();
        KAFKA.start();
        WIRE_MOCK.start();
    }

    /**
     * Wires the shared kafka bootstrap-servers + disables Eureka
     * for every subclass context.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void baseProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("eureka.client.register-with-eureka", () -> "false");
        registry.add("eureka.client.fetch-registry", () -> "false");
    }

    /** Resets WireMock stubs + request journal before every test method. */
    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    /**
     * Pre-creates a topic on the shared Testcontainers broker. Safe
     * to call multiple times -- a {@link TopicExistsException} cause
     * is treated as a no-op.
     *
     * @param topic topic name to create
     * @throws ExecutionException   if the broker reports a non-recoverable error
     * @throws InterruptedException if the test thread is interrupted
     */
    protected static void preCreateTopic(final String topic)
            throws ExecutionException, InterruptedException {
        final Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            try {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                        .all().get();
            } catch (ExecutionException ex) {
                if (!(ex.getCause() instanceof TopicExistsException)) {
                    throw ex;
                }
            }
        }
    }

    /**
     * Builds a contract-shaped CloudEvent envelope matching what the
     * upstream P5.4 producer writes to {@code cortex.anomalies.v1}
     * per ADR-0031.
     *
     * @param eventId  CloudEvent {@code id} and {@code data.eventId}
     * @param tenantId CloudEvent {@code subject} and {@code data.tenantId}
     * @return the serialized CloudEvent bytes
     * @throws Exception on Jackson or CloudEvents serialization failure
     */
    protected static byte[] buildEnvelope(final String eventId,
                                          final String tenantId) throws Exception {
        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        final AnomalyEvent payload = new AnomalyEvent(eventId, tenantId,
                "HIGH", "cross-phase closer burst", Instant.now(),
                "ERROR", "checkout", "503 from /pay endpoint");
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
     * Publishes a single byte-array value to the supplied topic on
     * the shared broker through a short-lived {@link KafkaProducer}.
     *
     * @param topic destination topic
     * @param value record value bytes
     * @throws Exception on producer send or close failure
     */
    protected static void publish(final String topic, final byte[] value) throws Exception {
        final Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
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
     * Reads all currently-available records from the DLT topic with
     * a short poll budget. Used to assert no DLT growth in P6.1a
     * (the P6.0 consumer logs + acks parse failures; the
     * {@code DeadLetterPublishingRecoverer} ships in P6.4 / ADR-0032
     * D4).
     *
     * @return list of DLT records polled in the 1 s window
     */
    protected static List<ConsumerRecord<byte[], byte[]>> readDltRecords() throws Exception {
        preCreateTopic(DLT_TOPIC);
        final Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "cross-phase-dlt-reader-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(DLT_TOPIC));
            final ConsumerRecords<byte[], byte[]> polled = consumer.poll(Duration.ofSeconds(1));
            final java.util.ArrayList<ConsumerRecord<byte[], byte[]>> out = new java.util.ArrayList<>();
            polled.forEach(out::add);
            return out;
        }
    }

    /**
     * Reads the current value of the
     * {@code cortex.remediation.dispatched_total} counter scoped to
     * a single (channel, outcome, tenant_id) tag triple. Returns 0
     * when the timeseries has not yet registered.
     *
     * @param registry the autoconfigured registry to query
     * @param channel  channel tag value (slack / pagerduty / jira)
     * @param outcome  outcome tag value (dispatched / transient_failure / permanent_failure)
     * @param tenantId tenant_id tag value
     * @return counter value or 0 when absent
     */
    protected static double counterValue(final MeterRegistry registry,
                                         final String channel,
                                         final String outcome,
                                         final String tenantId) {
        final Counter c = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", channel)
                .tag("outcome", outcome)
                .tag("tenant_id", tenantId)
                .counter();
        return c == null ? 0.0d : c.count();
    }
}
