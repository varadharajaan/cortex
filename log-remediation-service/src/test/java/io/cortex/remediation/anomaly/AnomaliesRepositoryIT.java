package io.cortex.remediation.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end repository test for the anomaly read model against
 * real Postgres and the real Flyway migration (P9.3).
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "eureka.client.enabled=false",
        "eureka.client.register-with-eureka=false",
        "eureka.client.fetch-registry=false"
})
@Testcontainers
class AnomaliesRepositoryIT {

    /** Shared Postgres 16 container; auto-wired via @ServiceConnection. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Repository under test. */
    @Autowired private AnomaliesRepository repository;

    /**
     * Inserts a row and reads it back through the tenant query path.
     */
    @Test
    void insertAndFindByTenantReturnsNewestFirst() {
        final String tenantId = "tenant-repo-" + System.nanoTime();
        final AnomalyRecord older = record(tenantId, "evt-old",
                Instant.parse("2026-06-09T10:00:00Z"));
        final AnomalyRecord newer = record(tenantId, "evt-new",
                Instant.parse("2026-06-09T10:05:00Z"));

        assertThat(this.repository.insertIfAbsent(older)).isTrue();
        assertThat(this.repository.insertIfAbsent(newer)).isTrue();

        final List<AnomalyRecord> rows =
                this.repository.findByTenant(tenantId, null, null, 10);

        assertThat(rows).extracting(AnomalyRecord::eventId)
                .containsExactly("evt-new", "evt-old");
        assertThat(rows.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(rows.get(0).severity()).isEqualTo("HIGH");
    }

    /**
     * Duplicate Kafka deliveries are absorbed by the unique key.
     */
    @Test
    void duplicateTenantEventIsIgnored() {
        final String tenantId = "tenant-dup-" + System.nanoTime();
        final AnomalyRecord row = record(tenantId, "evt-dup",
                Instant.parse("2026-06-09T10:00:00Z"));

        assertThat(this.repository.insertIfAbsent(row)).isTrue();
        assertThat(this.repository.insertIfAbsent(row)).isFalse();

        assertThat(this.repository.findByTenant(tenantId, null, null, 10))
                .hasSize(1);
    }

    /**
     * The query is tenant-scoped and respects the inclusive time window.
     */
    @Test
    void findByTenantAppliesTenantAndTimeFilters() {
        final String tenantId = "tenant-window-" + System.nanoTime();
        this.repository.insertIfAbsent(record(tenantId, "evt-before",
                Instant.parse("2026-06-09T09:59:59Z")));
        this.repository.insertIfAbsent(record(tenantId, "evt-inside",
                Instant.parse("2026-06-09T10:10:00Z")));
        this.repository.insertIfAbsent(record("other-" + tenantId, "evt-other",
                Instant.parse("2026-06-09T10:10:00Z")));

        final List<AnomalyRecord> rows = this.repository.findByTenant(
                tenantId,
                Instant.parse("2026-06-09T10:00:00Z"),
                Instant.parse("2026-06-09T10:30:00Z"),
                10);

        assertThat(rows).extracting(AnomalyRecord::eventId)
                .containsExactly("evt-inside");
    }

    /**
     * Query limit is passed through to SQL.
     */
    @Test
    void findByTenantHonorsLimit() {
        final String tenantId = "tenant-limit-" + System.nanoTime();
        this.repository.insertIfAbsent(record(tenantId, "evt-1",
                Instant.parse("2026-06-09T10:00:00Z")));
        this.repository.insertIfAbsent(record(tenantId, "evt-2",
                Instant.parse("2026-06-09T10:01:00Z")));

        assertThat(this.repository.findByTenant(tenantId, null, null, 1))
                .hasSize(1)
                .extracting(AnomalyRecord::eventId)
                .containsExactly("evt-2");
    }

    private static AnomalyRecord record(final String tenantId,
                                        final String eventId,
                                        final Instant ts) {
        return new AnomalyRecord(
                null,
                tenantId,
                eventId,
                "HIGH",
                "burst",
                ts,
                "ERROR",
                "checkout",
                "503",
                0.87d,
                "LATENCY",
                "restart-checkout",
                ts.plusSeconds(1));
    }
}
