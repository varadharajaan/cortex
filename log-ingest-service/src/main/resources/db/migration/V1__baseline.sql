-- ---------------------------------------------------------------------------
-- CORTEX log-ingest-service - Flyway baseline (P4.0 / D7).
--
-- P4.0 scope: scaffold ONLY. This baseline creates the `tenants` table
-- so per-tenant resolution (P4.3) has a foreign-key target ready. The
-- full `raw_logs` schema arrives in P4.1 as V2__raw_logs.sql, dedupe
-- index in P4.2 as V3__dedupe_index.sql, outbox in P4.4.
--
-- Portable SQL: works on PostgreSQL (prod / smoke) and on H2 in
-- PostgreSQL compatibility mode (unit + slice tests).
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS tenants (
    tenant_id    VARCHAR(64)  PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Seed a default tenant so local-dev smoke tests have a valid target
-- without requiring an admin endpoint that does not exist yet. Removed
-- in P5 once the admin onboarding flow ships.
INSERT INTO tenants (tenant_id, display_name)
SELECT 'cortex-dev', 'Local Development Tenant'
WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE tenant_id = 'cortex-dev');
