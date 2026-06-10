-- ---------------------------------------------------------------------------
-- CORTEX log-remediation-service - Flyway V2 (P9.3 anomaly read model).
--
-- Persists valid anomaly CloudEvents consumed from cortex.anomalies.v1 so
-- P9.3a can expose GET /api/v1/anomalies without asking Kafka to behave like
-- a query store. This table is deliberately owned by log-remediation-service:
-- no FK to log-ingest-service.tenants, and a service-specific Flyway history
-- table is configured in application.yml for local-dev database sharing.
-- Version V2 is intentional: when local dev shares a non-empty smoke DB
-- with ingest, Flyway baseline-on-migrate creates a baseline at version 1,
-- so the first remediation-owned migration must be greater than that
-- baseline.
--
-- Duplicate Kafka deliveries are absorbed by UNIQUE (tenant_id, event_id).
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS anomalies (
    id                BIGSERIAL     PRIMARY KEY,
    tenant_id         VARCHAR(64)   NOT NULL,
    event_id          VARCHAR(128)  NOT NULL,
    severity          VARCHAR(32)   NOT NULL,
    reason            TEXT          NOT NULL,
    ts                TIMESTAMPTZ   NOT NULL,
    level             VARCHAR(32),
    service           VARCHAR(128),
    message           TEXT,
    confidence        DOUBLE PRECISION NOT NULL DEFAULT 0,
    anomaly_type      VARCHAR(128)  NOT NULL DEFAULT 'UNKNOWN',
    remediation_key   VARCHAR(255)  NOT NULL DEFAULT 'none',
    received_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT anomalies_tenant_event_uk UNIQUE (tenant_id, event_id)
);

CREATE INDEX IF NOT EXISTS anomalies_tenant_ts_idx
    ON anomalies (tenant_id, ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS anomalies_ts_idx
    ON anomalies (ts DESC, id DESC);

CREATE INDEX IF NOT EXISTS anomalies_tenant_severity_ts_idx
    ON anomalies (tenant_id, severity, ts DESC);
