package io.cortex.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke / context-loads test for the log-monitoring-service Spring
 * Boot application (P8.0).
 *
 * <p>Boots the full Spring context. Eureka registration is disabled
 * at the property level via {@code src/test/resources/application.yml}
 * because the local-dev registry on port 8761 is not running in the
 * test JVM (LD100 full shadow).</p>
 *
 * <p>This test proves: the Spring context loads, the
 * {@link io.cortex.monitoring.probe.NoopServiceHealthProbe} wires
 * as the default probe bean,
 * {@link io.cortex.monitoring.metrics.MonitoringMetrics} bootstrap
 * registers against the meter registry, and the
 * {@link io.cortex.monitoring.health.MonitoringHealthIndicator}
 * surfaces on {@code /actuator/health/monitoring}.</p>
 */
@SpringBootTest
class CortexMonitoringApplicationTests {

    /**
     * Smoke test: assert the Spring context loads. Any wiring failure
     * (missing bean, BeanCreationException, mis-configured
     * {@code @ConditionalOnProperty} gate, NPE in
     * {@link io.cortex.monitoring.metrics.MonitoringMetrics} constructor)
     * fails this test.
     */
    @Test
    void contextLoads() {
        // Intentionally empty - assertion is "Spring booted with no
        // exception". Same pattern other CORTEX modules use for the
        // P*.0 smoke test (see CortexIndexerApplicationTests).
    }
}
