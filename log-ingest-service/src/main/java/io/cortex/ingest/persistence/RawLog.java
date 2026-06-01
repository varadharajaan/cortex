package io.cortex.ingest.persistence;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC aggregate root for the {@code raw_logs} table
 * (P4.1 / ADR-0022).
 *
 * <p>Mirrors the V2 Flyway schema column-for-column. Persisted by
 * {@link RawLogRepository}; the {@code labels} JSONB column
 * roundtrips via the {@code Map&lt;String, String&gt; <-> PGobject}
 * converter pair declared in {@link JdbcConvertersConfig}.</p>
 *
 * <p>{@code id} stays {@code null} on insert so Spring Data JDBC
 * issues an INSERT (versus an UPDATE for an attached id) and the
 * Postgres {@code BIGSERIAL} sequence supplies the value.</p>
 *
 * @param id             surrogate primary key; {@code null} on
 *                       insert, populated by the database
 * @param tenantId       resolved tenant identifier (FK to
 *                       {@code tenants.tenant_id})
 * @param eventId        server-computed dedupe key (SHA-256 hex of
 *                       tenant + service + ts + message + labels)
 * @param ts             event UTC timestamp from the inbound entry
 * @param level          severity name (TRACE / DEBUG / INFO / WARN /
 *                       ERROR)
 * @param service        logical service name from the inbound entry
 * @param message        raw human-readable message (PII masking
 *                       lands in P4.2)
 * @param labels         flat string-string label map persisted as
 *                       JSONB; never {@code null}
 * @param idempotencyKey verbatim {@code Idempotency-Key} header from
 *                       the inbound request; {@code null} when
 *                       absent. Enforcement (Redis SETNX) lands in
 *                       P4.2; the column is recorded today so the
 *                       cold path has the value when needed.
 * @param receivedAt     server-side acceptance timestamp; pinned by
 *                       the service on insert
 */
@Table("raw_logs")
public record RawLog(
        @Id Long id,
        @Column("tenant_id") String tenantId,
        @Column("event_id") String eventId,
        @Column("ts") Instant ts,
        @Column("level") String level,
        @Column("service") String service,
        @Column("message") String message,
        @Column("labels") Map<String, String> labels,
        @Column("idempotency_key") String idempotencyKey,
        @Column("received_at") Instant receivedAt) {
}
