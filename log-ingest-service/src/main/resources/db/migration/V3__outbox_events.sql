-- ---------------------------------------------------------------------------
-- CORTEX log-ingest-service - Flyway V3 (P4.4a / ADR-0025).
--
-- Adds the transactional outbox table `outbox_events`. Every accepted log
-- entry that lands in `raw_logs` ALSO writes one row here, INSIDE THE SAME
-- per-row transaction (REQUIRES_NEW), so the outbox cannot drift from the
-- system of record (strict rule B10.1).
--
-- A scheduled poller (P4.4b) sweeps `status = 'PENDING'` rows ordered by
-- `next_attempt_at`, wraps each row in a CloudEvents 1.0 envelope, and
-- publishes via Spring Cloud Stream to `cortex.logs.events.v1`. On publish
-- success the row is marked `PUBLISHED` and `published_at` is stamped.
-- On publish failure `attempts` is incremented, `next_attempt_at` is
-- pushed out by an exponential backoff, and `last_error` records the
-- exception class + message. After N failed attempts (configurable, P4.4c)
-- the row transitions to `FAILED` and is routed to the DLQ binding.
--
-- DESIGN CHOICES (rationale in ADR-0025):
--
--   - `payload TEXT` not `JSONB`. The poller treats the payload as opaque
--     bytes to publish; no `payload->>'field' = ?` read paths are planned
--     for the outbox table. If a future read path needs JSONB query, the
--     migration is `ALTER TABLE outbox_events ALTER COLUMN payload TYPE
--     JSONB USING payload::jsonb`. TEXT avoids the custom Spring Data JDBC
--     converter that JSONB would require for arbitrary JSON (the existing
--     `labels` converter is typed for `Map<String,String>` and cannot
--     handle arbitrary nested JSON).
--
--   - NO foreign key from `outbox_events.tenant_id` to `tenants.tenant_id`.
--     The FK is already enforced upstream by `raw_logs.tenant_id` and the
--     per-row REQUIRES_NEW transaction ensures the outbox row is only
--     written when the raw_logs row INSERT succeeded; an outbox row can
--     never reference a tenant that the raw_logs INSERT did not validate.
--     Skipping the FK avoids a duplicate constraint check on the hot path.
--
--   - `(tenant_id, event_id)` is INDEXED but NOT UNIQUE. Cold-path dedupe
--     on `raw_logs.UNIQUE (tenant_id, event_id)` raises before the
--     outbox INSERT in the per-row tx, so a duplicate event_id can never
--     reach the outbox. The index supports observability joins back to
--     `raw_logs` (`SELECT * FROM outbox_events o JOIN raw_logs r ON
--     o.tenant_id = r.tenant_id AND o.event_id = r.event_id`).
--
--   - `outbox_events_pending_idx` is a PARTIAL INDEX on `(status,
--     next_attempt_at) WHERE status = 'PENDING'`. The P4.4b poller's
--     hot-path query is `SELECT * FROM outbox_events WHERE status =
--     'PENDING' AND next_attempt_at <= now() ORDER BY next_attempt_at
--     LIMIT N`. A partial index keeps the index small (only pending rows)
--     and the planner picks it without a hint.
--
--   - `payload` size: the largest realistic batch entry is ~2 KB of
--     labels + a 4 KB masked message + envelope overhead ~7 KB. Postgres
--     TOAST handles values up to ~1 GB; no explicit size cap needed.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    event_id        VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT outbox_events_status_check
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Hot-path poll query: SELECT ... WHERE status = 'PENDING'
--                       AND next_attempt_at <= now()
--                       ORDER BY next_attempt_at LIMIT N
CREATE INDEX IF NOT EXISTS outbox_events_pending_idx
    ON outbox_events (status, next_attempt_at)
    WHERE status = 'PENDING';

-- Observability + dedupe correlation back to raw_logs.
CREATE INDEX IF NOT EXISTS outbox_events_tenant_event_idx
    ON outbox_events (tenant_id, event_id);
