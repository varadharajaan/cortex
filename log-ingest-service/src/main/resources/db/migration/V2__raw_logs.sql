-- ---------------------------------------------------------------------------
-- CORTEX log-ingest-service - Flyway V2 (P4.1 / D3 / D7).
--
-- Adds the durable system-of-record table `raw_logs`. Each accepted
-- batch entry persists here BEFORE the queue publish step (P4.4) so the
-- ingest path stays crash-safe even when the binder is unavailable
-- (transactional outbox pattern lands as `outbox` in P4.4).
--
-- Dedupe contract is split across two layers (D3):
--   - hot path (P4.2)  : Redis SETNX against a server-computed event_id
--                        keyed by tenant_id + event_id with a short TTL.
--   - cold path (P4.1) : the UNIQUE (tenant_id, event_id) constraint
--                        below; catches Redis-misses and replays at the
--                        DB layer with a SQLState 23505 that
--                        IngestServiceImpl.persistRaw collapses into
--                        an idempotent "already received" outcome.
--
-- event_id is server-computed by IngestServiceImpl as the SHA-256 hex of
-- (tenant_id | service | ts.epochMicros | message | sortedLabelsJson) so
-- clients cannot bypass dedupe by omitting / mutating an id field.
-- See ADR-0022 (amended 2026-06-01) for the rationale.
--
-- Postgres-native types (TIMESTAMPTZ, JSONB). Tests run against
-- Testcontainers Postgres 16-alpine via @ServiceConnection (RA1).
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS raw_logs (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(tenant_id),
    event_id        VARCHAR(64)  NOT NULL,
    ts              TIMESTAMPTZ  NOT NULL,
    level           VARCHAR(16)  NOT NULL,
    service         VARCHAR(128) NOT NULL,
    message         TEXT         NOT NULL,
    labels          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key VARCHAR(255),
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT raw_logs_tenant_event_uk UNIQUE (tenant_id, event_id)
);

-- Cold-path lookups by received_at for TTL eviction (P5 housekeeping).
CREATE INDEX IF NOT EXISTS raw_logs_received_at_idx
    ON raw_logs (received_at);

-- Tenant + service + timestamp lookups for query / debug surfaces.
CREATE INDEX IF NOT EXISTS raw_logs_tenant_service_ts_idx
    ON raw_logs (tenant_id, service, ts);
