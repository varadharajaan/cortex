package io.cortex.ingest.outbox;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC repository for {@link OutboxEvent} aggregates
 * (P4.4a / ADR-0025).
 *
 * <p>PR-1 only needs {@link CrudRepository#save(Object)} +
 * {@link CrudRepository#count()} so derived query methods are
 * intentionally omitted. The {@code SELECT ... WHERE status =
 * 'PENDING'} hot-path poll query for the P4.4b poller will land
 * with the poller bean as a derived method
 * ({@code findTop100ByStatusAndNextAttemptAtBefore}) or a custom
 * {@code @Query} on this interface; that lives in PR-2 to keep
 * PR-1 broker-free.</p>
 */
@Repository
public interface OutboxRepository extends CrudRepository<OutboxEvent, Long> {
}
