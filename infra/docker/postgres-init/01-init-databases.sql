-- ---------------------------------------------------------------------------
-- CORTEX P10.1 -- Postgres init for the full compose stack.
--
-- The official postgres image creates exactly one database from POSTGRES_DB
-- (cortex_ingest, owned by cortex_ingest). log-ingest-service Flyways into it.
-- log-remediation-service points at its OWN database so the two services'
-- Flyway schema histories never collide on a shared DB (separate-DB-per-
-- service, the production-realistic posture). This script runs once on first
-- container init via /docker-entrypoint-initdb.d.
-- ---------------------------------------------------------------------------

CREATE DATABASE cortex_remediation OWNER cortex_ingest;
