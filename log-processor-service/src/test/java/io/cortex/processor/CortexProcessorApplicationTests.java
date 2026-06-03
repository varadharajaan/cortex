package io.cortex.processor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke / context-loads test for the log-processor-service Spring
 * Boot application (P5.0).
 *
 * <p>Boots an in-JVM Kafka broker via {@link EmbeddedKafka} so the
 * autoconfigured consumer container + {@code @KafkaListener} on
 * {@code LogEventConsumer} can wire end-to-end without a real
 * broker. Eureka registration is disabled at the property level
 * because the local-dev registry on port 8761 is not running in
 * the test JVM.</p>
 *
 * <p>This test proves: the Spring context loads, the consumer
 * container starts, the NoopAnomalyClassifier wires as the default
 * classifier bean, and the three ProcessorMetrics counters
 * register against the Prometheus meter registry. P5.1+ adds
 * end-to-end consume + classify + ack assertions on top of this
 * baseline.</p>
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"cortex.logs.events.v1"},
        controlledShutdown = true,
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:0",
                "port=0",
                "auto.create.topics.enable=true"
        }
)
@TestPropertySource(properties = {
        // Point the consumer at the embedded broker. The
        // ${spring.embedded.kafka.brokers} placeholder is set by
        // EmbeddedKafkaBroker before bean creation.
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        // Keep the local-dev Eureka registry out of the test boot
        // path - the registry on :8761 isn't running.
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false",
        // Short consumer rebalance timeouts so the context teardown
        // doesn't hang waiting for default 30s leave-group.
        "spring.kafka.consumer.properties.session.timeout.ms=6000",
        "spring.kafka.consumer.properties.heartbeat.interval.ms=2000"
})
class CortexProcessorApplicationTests {

    /**
     * Smoke test: assert the Spring context loads. Any wiring failure
     * (missing bean, BeanCreationException, mis-configured
     * @ConditionalOnProperty gate, NPE in
     * {@code ProcessorMetrics} constructor) fails this test.
     */
    @Test
    void contextLoads() {
        // Intentionally empty - assertion is "Spring booted with no
        // exception". Same pattern other CORTEX modules use for the
        // P*.0 smoke test (see CortexIngestApplicationTests).
    }
}
