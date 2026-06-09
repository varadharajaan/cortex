package io.cortex.ingest.persistence;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC repository for {@link RawLog} aggregates
 * (P4.1 / ADR-0022).
 *
 * <p>P4.1 only needed {@link CrudRepository#save(Object)} +
 * {@link CrudRepository#saveAll(Iterable)} (the write path). P9.2a
 * adds the first read accessor,
 * {@link #findByTenantIdAndEventId(String, String)}, which backs the
 * tenant-scoped {@code GET /api/v1/logs/{eventId}} surface
 * (ADR-0022 Amendment 1). The {@code UNIQUE (tenant_id, event_id)}
 * constraint on {@code raw_logs} guarantees the lookup returns at
 * most one row.</p>
 */
@Repository
public interface RawLogRepository extends CrudRepository<RawLog, Long> {

    /**
     * Looks up a single persisted log row by its owning tenant and
     * server-computed {@code event_id} (P9.2a / ADR-0022 Amendment 1).
     *
     * <p>Spring Data JDBC derives the query
     * {@code WHERE tenant_id = ? AND event_id = ?}. The composite
     * UNIQUE constraint means the result is at most one row, so an
     * {@link Optional} is the correct return shape.</p>
     *
     * @param tenantId owning tenant id (the request {@code X-Tenant-Id});
     *                 scopes the lookup so one tenant cannot read
     *                 another tenant's row
     * @param eventId  server-computed dedupe key from the path variable
     * @return the matching row, or {@link Optional#empty()} when no
     *         row exists for that {@code (tenant_id, event_id)} pair
     */
    Optional<RawLog> findByTenantIdAndEventId(String tenantId, String eventId);
}
