package io.cortex.ingest.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end persistence test for {@link RawLogRepository} against a
 * real Postgres provided by Testcontainers (P4.1 / D3 / D7 /
 * ADR-0022).
 *
 * <p>Verifies the two contracts that H2 cannot exercise:</p>
 * <ul>
 *   <li>JSONB roundtrip through the
 *       {@link JdbcConvertersConfig} converter pair.</li>
 *   <li>{@code UNIQUE (tenant_id, event_id)} cold-path dedupe via
 *       {@link DuplicateKeyException}.</li>
 * </ul>
 *
 * <p>Named {@code *IT} so Failsafe runs it (Surefire ignores it),
 * matching the agent-strict-rules naming contract for integration
 * tests.</p>
 */
@SpringBootTest
@Testcontainers
class RawLogRepositoryIT {

    /** Shared Postgres 16 container; reused across both test methods. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Production repository under test. */
    @Autowired private RawLogRepository repository;

    /** Default constructor used by JUnit. */
    RawLogRepositoryIT() {
        // no state
    }

    /**
     * Persists a row with non-empty labels and verifies the JSONB
     * column roundtrips back to a flat string-string map.
     */
    @Test
    void labelsRoundtripAsJsonb() {
        final Map<String, String> labels = Map.of(
                "env", "test",
                "region", "westus2");
        final RawLog saved = this.repository.save(new RawLog(
                null,
                "cortex-dev",
                "evt-roundtrip-1",
                Instant.parse("2026-05-31T12:00:00Z"),
                "INFO",
                "cortex-it",
                "labels roundtrip",
                labels,
                null,
                Instant.parse("2026-05-31T12:00:00Z")));

        final RawLog reloaded = this.repository.findById(saved.id()).orElseThrow();
        assertThat(reloaded.labels())
                .containsEntry("env", "test")
                .containsEntry("region", "westus2");
        assertThat(reloaded.tenantId()).isEqualTo("cortex-dev");
        assertThat(reloaded.eventId()).isEqualTo("evt-roundtrip-1");
    }

    /**
     * Persists a row, then attempts to persist the same
     * {@code (tenant_id, event_id)} pair again and asserts that the
     * UNIQUE constraint surfaces as
     * {@link DuplicateKeyException}.
     */
    @Test
    void duplicateTenantEventIdRaisesDuplicateKey() {
        final RawLog seed = new RawLog(
                null,
                "cortex-dev",
                "evt-dup-1",
                Instant.parse("2026-05-31T12:00:01Z"),
                "INFO",
                "cortex-it",
                "first",
                Map.of(),
                null,
                Instant.parse("2026-05-31T12:00:01Z"));
        this.repository.save(seed);

        final RawLog dup = new RawLog(
                null,
                "cortex-dev",
                "evt-dup-1",
                Instant.parse("2026-05-31T12:00:02Z"),
                "INFO",
                "cortex-it",
                "second",
                Map.of(),
                null,
                Instant.parse("2026-05-31T12:00:02Z"));
        assertThatThrownBy(() -> this.repository.save(dup))
                .isInstanceOf(DbActionExecutionException.class)
                .hasCauseInstanceOf(DuplicateKeyException.class);
    }

    /**
     * Persists a row, then looks it up by its {@code (tenant_id,
     * event_id)} pair and asserts the projection round-trips
     * (P9.2a / ADR-0022 Amendment 1 read path). Uses the seeded
     * {@code cortex-dev} tenant because {@code raw_logs.tenant_id}
     * is FK-constrained to {@code tenants.tenant_id} (V1 baseline
     * seeds only {@code cortex-dev}).
     */
    @Test
    void findByTenantIdAndEventIdReturnsRowOnHit() {
        final Map<String, String> labels = Map.of("env", "prod");
        this.repository.save(new RawLog(
                null,
                "cortex-dev",
                "evt-find-1",
                Instant.parse("2026-06-09T10:00:00Z"),
                "ERROR",
                "payment-svc",
                "boom",
                labels,
                null,
                Instant.parse("2026-06-09T10:00:01Z")));

        final var found = this.repository.findByTenantIdAndEventId("cortex-dev", "evt-find-1");

        assertThat(found).isPresent();
        assertThat(found.get().tenantId()).isEqualTo("cortex-dev");
        assertThat(found.get().eventId()).isEqualTo("evt-find-1");
        assertThat(found.get().level()).isEqualTo("ERROR");
        assertThat(found.get().labels()).containsEntry("env", "prod");
    }

    /**
     * Persists a row owned by {@code cortex-dev} and asserts a lookup
     * with the same {@code event_id} but a DIFFERENT tenant returns
     * empty -- the tenant scoping ({@code WHERE tenant_id = ?}) that
     * stops cross-tenant reads (ADR-0022 Amendment 1 / ADR-0009). The
     * other tenant id is never inserted, so the FK is not involved on
     * the read path.
     */
    @Test
    void findByTenantIdAndEventIdIsTenantScoped() {
        this.repository.save(new RawLog(
                null,
                "cortex-dev",
                "evt-shared",
                Instant.parse("2026-06-09T10:00:00Z"),
                "INFO",
                "svc",
                "owned by cortex-dev",
                Map.of(),
                null,
                Instant.parse("2026-06-09T10:00:01Z")));

        assertThat(this.repository.findByTenantIdAndEventId("other-tenant", "evt-shared"))
                .as("a different tenant must not read cortex-dev's row")
                .isEmpty();
    }

    /**
     * Asserts a lookup for an {@code event_id} that was never
     * persisted returns empty (the 404 path).
     */
    @Test
    void findByTenantIdAndEventIdReturnsEmptyWhenAbsent() {
        assertThat(this.repository.findByTenantIdAndEventId("cortex-dev", "evt-does-not-exist"))
                .isEmpty();
    }
}
