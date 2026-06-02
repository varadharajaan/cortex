package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cloudevents.CloudEvent;
import io.cloudevents.kafka.CloudEventDeserializer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * End-to-end integration test for the P4.4b Kafka + CloudEvents
 * publish path (P4.4b / ADR-0026 / B9.2 override O8).
 *
 * <p>Stands up TWO Testcontainers: Postgres 16 (wired via
 * {@code @ServiceConnection}) and Apache Kafka 3.8.0 KRaft (wired
 * via {@code @DynamicPropertySource} -- Spring Boot 3.3.x's
 * Testcontainers connection-details auto-config does not yet
 * recognise the {@code org.testcontainers.kafka.KafkaContainer}
 * apache-image variant, so we wire {@code spring.kafka.bootstrap-
 * servers} by hand).</p>
 *
 * <p>Test recipe:</p>
 * <ol>
 *   <li>Persist a PENDING {@link OutboxEvent} row via the real
 *       {@link OutboxRepository} (real Postgres).</li>
 *   <li>Manually invoke {@link OutboxPoller#drainOnce()} (the
 *       scheduled tick stays disabled via
 *       {@code cortex.ingest.outbox.poller.enabled=false} from
 *       the shared test {@code application.yml}; {@code drainOnce}
 *       runs regardless of the master switch).</li>
 *   <li>Consume from {@code cortex.logs.events.v1} with a raw
 *       {@link KafkaConsumer} backed by the
 *       {@link CloudEventDeserializer}.</li>
 *   <li>Assert the consumed envelope carries the documented
 *       CloudEvents 1.0 attributes and the persisted payload, and
 *       the source outbox row transitions to
 *       {@link OutboxStatus#PUBLISHED}.</li>
 * </ol>
 *
 * <p>Named {@code *IT} so Failsafe runs it; Surefire ignores it.</p>
 */
@SpringBootTest
@Testcontainers
class OutboxPollerKafkaIT {

    /** Output topic name; pinned by ADR-0026. */
    private static final String TOPIC = "cortex.logs.events.v1";

    /** CloudEvent type identifier asserted on every consumed envelope. */
    private static final String EVENT_TYPE = "io.cortex.logs.event.v1";

    /** CloudEvent source URI asserted on every consumed envelope. */
    private static final String EVENT_SOURCE = "/cortex/log-ingest-service";

    /** Shared Postgres 16 container; auto-wired via @ServiceConnection. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Shared Apache Kafka 3.8.0 KRaft container. */
    @Container
    static KafkaContainer kafka =
            new KafkaContainer("apache/kafka:3.8.0");

    /**
     * Wires Spring Boot's {@code spring.kafka.bootstrap-servers}
     * to the Testcontainers Kafka broker so the
     * {@code KafkaTemplate} bean in {@code KafkaConfig} connects
     * to the test broker (LD78: Spring Boot 3.3.6 ServiceConnection
     * does not cover {@code org.testcontainers.kafka.KafkaContainer}).
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void kafkaProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    /** Outbox repository under test (real Postgres). */
    @Autowired private OutboxRepository repository;

    /** Poller bean exercised end-to-end. */
    @Autowired private OutboxPoller poller;

    /** Default constructor used by JUnit. */
    OutboxPollerKafkaIT() {
        // no state
    }

    /**
     * Inserts a PENDING outbox row, drives the poller once, then
     * asserts the row is PUBLISHED in Postgres AND the matching
     * CloudEvent envelope landed on the Kafka topic with the
     * documented attributes.
     */
    @Test
    void drainsPendingRowAndPublishesCloudEventEnvelope() {
        // Anchor against wall-clock 'now' so the row's next_attempt_at
        // is in the past relative to the poller's Clock; a hard-coded
        // future Instant would make findPendingDueForPublish skip it.
        final Instant createdAt = Instant.now().minus(Duration.ofMinutes(1));
        final String eventId = "evt-it-kafka-1";
        final String tenantId = "cortex-dev";
        final String payload = "{\"eventId\":\"" + eventId + "\",\"hello\":\"world\"}";

        try (KafkaConsumer<String, CloudEvent> consumer = newCloudEventConsumer()) {
            // Subscribe + warm the assignment BEFORE the producer side
            // sends. With auto-create-topics on the broker, the very
            // first ProduceRequest creates the topic; doing the
            // subscribe-and-poll dance up-front guarantees the
            // consumer is already on the assignment before any record
            // lands so we do not race the 10s poll budget.
            consumer.subscribe(List.of(TOPIC));
            consumer.poll(Duration.ofSeconds(2));

            final OutboxEvent saved = this.repository.save(OutboxEvent.pending(
                    tenantId, eventId, payload, createdAt));

            await().atMost(30, TimeUnit.SECONDS)
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        this.poller.drainOnce();
                        final OutboxEvent reloaded =
                                this.repository.findById(saved.id()).orElseThrow();
                        assertThat(reloaded.status())
                                .isEqualTo(OutboxStatus.PUBLISHED.name());
                        assertThat(reloaded.publishedAt()).isNotNull();
                    });

            final List<ConsumerRecord<String, CloudEvent>> received =
                    drainTopic(consumer, Duration.ofSeconds(30));
            assertThat(received)
                    .as("expected at least one CloudEvent on %s", TOPIC)
                    .isNotEmpty();
            final CloudEvent ce = received.get(0).value();
            assertThat(ce.getSpecVersion().toString()).isEqualTo("1.0");
            assertThat(ce.getId()).isEqualTo(eventId);
            assertThat(ce.getType()).isEqualTo(EVENT_TYPE);
            assertThat(ce.getSource().toString()).isEqualTo(EVENT_SOURCE);
            assertThat(ce.getSubject()).isEqualTo(tenantId);
            assertThat(ce.getDataContentType()).isEqualTo("application/json");
            assertThat(ce.getData()).isNotNull();
            assertThat(new String(ce.getData().toBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(payload);
        }
    }

    /**
     * Builds a one-shot KafkaConsumer wired with the CloudEvents deserializer.
     *
     * @return a Kafka consumer bound to the broker on offset-earliest
     */
    private static KafkaConsumer<String, CloudEvent> newCloudEventConsumer() {
        final Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                CloudEventDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    /**
     * Polls the consumer until {@code timeout} elapses, accumulating
     * all consumed records. Returns whatever is in the topic at that
     * point; the caller asserts on the cumulative result.
     *
     * @param consumer subscribed Kafka consumer
     * @param timeout  hard cap on the cumulative poll budget
     * @return records pulled from the topic before the deadline
     */
    private static List<ConsumerRecord<String, CloudEvent>> drainTopic(
            final KafkaConsumer<String, CloudEvent> consumer,
            final Duration timeout) {
        final java.util.ArrayList<ConsumerRecord<String, CloudEvent>> acc =
                new java.util.ArrayList<>();
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            final ConsumerRecords<String, CloudEvent> batch =
                    consumer.poll(Duration.ofMillis(500));
            for (final ConsumerRecord<String, CloudEvent> r : batch) {
                acc.add(r);
            }
            if (!acc.isEmpty()) {
                return acc;
            }
        }
        return acc;
    }

    /**
     * Returns the live consumer-group map for diagnostic completeness.
     *
     * @return a map of topic + broker bootstrap-servers
     */
    @SuppressWarnings("unused")
    private static Map<String, ?> dummyDiagnostic() {
        return Map.of("topic", TOPIC, "broker", kafka.getBootstrapServers());
    }
}
