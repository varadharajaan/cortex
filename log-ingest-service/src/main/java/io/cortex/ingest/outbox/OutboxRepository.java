package io.cortex.ingest.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC repository for {@link OutboxEvent} aggregates
 * (P4.4a / ADR-0025 + P4.4b / ADR-0026).
 *
 * <p>P4.4a needed only {@link CrudRepository#save(Object)} +
 * {@link CrudRepository#count()}. P4.4b adds the
 * {@link #findPendingDueForPublish(Instant, int)} hot-path query
 * driven by the partial index {@code outbox_events_pending_idx ON
 * (status, next_attempt_at) WHERE status='PENDING'} and the
 * {@link #markPublished(Long, Instant)} /
 * {@link #markFailureAndReschedule(Long, int, Instant, String)}
 * lifecycle mutators used by {@link OutboxPoller}.</p>
 */
@Repository
public interface OutboxRepository extends CrudRepository<OutboxEvent, Long> {

    /**
     * Fetches up to {@code limit} PENDING rows whose
     * {@code next_attempt_at} is at or before {@code now}. Ordered
     * by {@code next_attempt_at} ascending so older work drains
     * first. Matches the partial index
     * {@code outbox_events_pending_idx} for index-only scans.
     *
     * @param now   poller wall-clock at the start of the sweep
     * @param limit row cap; the poller passes its
     *              {@code batch-size} property
     * @return PENDING rows due for publish; may be empty, never
     *         {@code null}
     */
    @Query("SELECT * FROM outbox_events "
            + "WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "ORDER BY next_attempt_at ASC "
            + "LIMIT :limit")
    List<OutboxEvent> findPendingDueForPublish(
            @Param("now") Instant now,
            @Param("limit") int limit);

    /**
     * Marks the row PUBLISHED and stamps {@code published_at}.
     *
     * @param id          surrogate primary key of the row
     * @param publishedAt UTC timestamp captured by the poller
     *                    immediately after the successful
     *                    {@code StreamBridge.send(...)} returned
     * @return rows affected; expected to be {@code 1}
     */
    @Modifying
    @Query("UPDATE outbox_events "
            + "SET status = 'PUBLISHED', published_at = :publishedAt, "
            + "    last_error = NULL "
            + "WHERE id = :id")
    int markPublished(@Param("id") Long id,
                      @Param("publishedAt") Instant publishedAt);

    /**
     * Records a publish failure: bumps {@code attempts}, pushes
     * {@code next_attempt_at} out by the supplied backoff, and
     * stores a truncated error message. The row remains
     * {@link OutboxStatus#PENDING} so the poller will retry on
     * its next sweep after the rescheduled time.
     *
     * @param id            surrogate primary key of the row
     * @param attempts      new attempts count (old value + 1)
     * @param nextAttemptAt reschedule target; the poller skips
     *                      this row until {@code now()} crosses
     *                      this value
     * @param lastError     short error description truncated to
     *                      the column ceiling by the caller
     * @return rows affected; expected to be {@code 1}
     */
    @Modifying
    @Query("UPDATE outbox_events "
            + "SET attempts = :attempts, "
            + "    next_attempt_at = :nextAttemptAt, "
            + "    last_error = :lastError "
            + "WHERE id = :id")
    int markFailureAndReschedule(@Param("id") Long id,
                                 @Param("attempts") int attempts,
                                 @Param("nextAttemptAt") Instant nextAttemptAt,
                                 @Param("lastError") String lastError);
}
