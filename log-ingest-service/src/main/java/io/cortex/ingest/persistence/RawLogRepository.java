package io.cortex.ingest.persistence;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC repository for {@link RawLog} aggregates
 * (P4.1 / ADR-0022).
 *
 * <p>P4.1 only needs {@link CrudRepository#save(Object)} +
 * {@link CrudRepository#saveAll(Iterable)} so derived query methods
 * are intentionally omitted. Read paths (search by event_id,
 * received_at sweeps, tenant + service + ts range scans) arrive in
 * P5.x when the indexer + housekeeping services consume this table
 * as their system-of-record source.</p>
 */
@Repository
public interface RawLogRepository extends CrudRepository<RawLog, Long> {
}
