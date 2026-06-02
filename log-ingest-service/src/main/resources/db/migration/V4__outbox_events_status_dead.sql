-- ---------------------------------------------------------------------------
-- CORTEX log-ingest-service - Flyway V4 (P4.4c / ADR-0027).
--
-- Relaxes the `outbox_events_status_check` CHECK constraint added in V3 to
-- allow the new `DEAD` terminal status introduced by the P4.4c DLQ branch.
-- DEAD rows are the ones the poller routed to `cortex.logs.events.v1.dlq`
-- after exhausting the per-row backoff schedule (see ADR-0027 D2).
--
-- The old (`PENDING`, `PUBLISHED`, `FAILED`) set stays valid:
--
--   - `PENDING`   pre-publish + during-retry
--   - `PUBLISHED` happy-path terminal
--   - `FAILED`    legacy enum value kept for backwards-compat with any
--                 rows persisted by hand or by a future operator script
--                 that needs to mark a row poisoned without invoking the
--                 poller. The application code never writes `FAILED`.
--   - `DEAD`      terminal post-DLQ state; the row stays in the table for
--                 operator inspection but the poller skips it forever
--                 (the partial index `outbox_events_pending_idx` already
--                 excludes non-PENDING rows so this is just a cosmetic
--                 declaration to the storage layer).
-- ---------------------------------------------------------------------------

ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS outbox_events_status_check;

ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_events_status_check
    CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD'));
