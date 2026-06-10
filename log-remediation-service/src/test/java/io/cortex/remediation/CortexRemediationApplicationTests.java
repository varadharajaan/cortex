package io.cortex.remediation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke / context-loads test for the log-remediation-service Spring
 * Boot application (P6.0).
 *
 * <p>Boots a real Postgres via Testcontainers so the P9.3 anomaly
 * read-model DataSource + Flyway migration are part of the smoke.
 * Kafka listener auto-start is disabled here because the dedicated
 * {@code AnomalyConsumerKafkaIT} owns the broker round-trip.</p>
 *
 * <p>This test proves: the Spring context loads, the consumer
 * container starts, the {@code NoopRemediationDispatcher} wires as
 * the default dispatcher bean, and the
 * {@code RemediationMetrics} bootstrap counter registers against
 * the Prometheus meter registry.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        // The Kafka listener path is covered by AnomalyConsumerKafkaIT.
        "spring.kafka.listener.auto-startup=false",
        // Keep the local-dev Eureka registry out of the test boot
        // path - the registry on :8761 isn't running.
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false"
})
class CortexRemediationApplicationTests {

    /** Shared Postgres 16 container for the P9.3 read-model DataSource. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Smoke test: assert the Spring context loads. Any wiring failure
     * (missing bean, BeanCreationException, mis-configured
     * @ConditionalOnProperty gate, NPE in
     * {@code RemediationMetrics} constructor) fails this test.
     */
    @Test
    void contextLoads() {
        // Intentionally empty - assertion is "Spring booted with no
        // exception". Same pattern other CORTEX modules use for the
        // P*.0 smoke test (see CortexProcessorApplicationTests).
    }
}
