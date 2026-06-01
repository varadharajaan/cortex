package io.cortex.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the full Spring application context for log-ingest-service
 * against a real Postgres provided by Testcontainers (P4.1 / RA1 /
 * ADR-0022).
 *
 * <p>Fails if any bean is misconfigured (rule A14.1 - smoke test on
 * context load). Exercises the full Flyway migration chain
 * (V1__baseline.sql + V2__raw_logs.sql) against
 * {@code postgres:16-alpine}; the JSONB column + JDBC custom
 * conversions wired by
 * {@link io.cortex.ingest.persistence.JdbcConvertersConfig} need a
 * real Postgres driver, so the prior H2 fallback no longer suffices
 * once P4.1 lands the {@code raw_logs} table.</p>
 *
 * <p>{@code @ServiceConnection} (Spring Boot 3.1+) auto-wires the
 * container's JDBC URL / credentials into the application
 * {@code DataSource}, overriding the test-classpath
 * {@code spring.datasource.url} declared in
 * {@code src/test/resources/application.yml}.</p>
 */
@SpringBootTest
@Testcontainers
class CortexIngestApplicationTests {

    /**
     * Shared Postgres 16 container; {@code static} so the JUnit
     * Testcontainers extension boots it once per test class.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Default constructor used by JUnit. */
    CortexIngestApplicationTests() {
        // no state
    }

    /**
     * No-op test body; the {@link SpringBootTest} bootstrap + Flyway
     * migrations are the assertion.
     */
    @Test
    void contextLoads() {
        // intentionally empty
    }
}
