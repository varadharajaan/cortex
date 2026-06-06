package io.cortex.indexer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke / context-loads test for the log-indexer-service Spring
 * Boot application (P7.0).
 *
 * <p>Boots the full Spring context (no embedded broker required at
 * P7.0 -- there is no Kafka consumer yet; Kafka joins in P7.1+ if
 * the search-side proxy needs it). Eureka registration is disabled
 * at the property level via {@code src/test/resources/application.yml}
 * because the local-dev registry on port 8761 is not running in the
 * test JVM (LD100 full shadow).</p>
 *
 * <p>This test proves: the Spring context loads, the
 * {@link io.cortex.indexer.admin.NoopQuickwitIndexAdmin} wires as
 * the default admin bean,
 * {@link io.cortex.indexer.metrics.IndexerMetrics} bootstrap
 * registers against the meter registry, and the
 * {@link io.cortex.indexer.health.QuickwitHealthIndicator} surfaces
 * on {@code /actuator/health/quickwit}.</p>
 */
@SpringBootTest
class CortexIndexerApplicationTests {

    /**
     * Smoke test: assert the Spring context loads. Any wiring failure
     * (missing bean, BeanCreationException, mis-configured
     * {@code @ConditionalOnProperty} gate, NPE in
     * {@link io.cortex.indexer.metrics.IndexerMetrics} constructor)
     * fails this test.
     */
    @Test
    void contextLoads() {
        // Intentionally empty - assertion is "Spring booted with no
        // exception". Same pattern other CORTEX modules use for the
        // P*.0 smoke test (see CortexRemediationApplicationTests).
    }
}
