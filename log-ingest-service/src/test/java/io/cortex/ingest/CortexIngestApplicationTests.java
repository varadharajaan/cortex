package io.cortex.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full Spring application context for log-ingest-service.
 *
 * <p>Fails if any bean is misconfigured (rule A14.1 - smoke test on
 * context load). Also exercises the Flyway V1__baseline.sql
 * migration against H2 in PostgreSQL mode (see
 * {@code src/test/resources/application.yml}).</p>
 */
@SpringBootTest
class CortexIngestApplicationTests {

    /**
     * No-op test body; the {@link SpringBootTest} bootstrap itself is
     * the assertion.
     */
    @Test
    void contextLoads() {
        // intentionally empty
    }
}
