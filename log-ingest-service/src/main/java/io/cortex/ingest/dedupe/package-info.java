/**
 * Hot-path dedupe components for {@code log-ingest-service} (D3 /
 * plan.md row P4.2 / spec Sec 5.3 "Idempotency via Redis").
 *
 * <p>{@code IdempotencyDedupeService} writes a Redis SETNX entry
 * keyed on {@code cortex:ingest:idem:{tenantId}:{idempotencyKey}} so
 * that replays of the same batch within the TTL window short-circuit
 * without touching Postgres. The cold-path
 * {@code UNIQUE (tenant_id, event_id)} constraint on {@code raw_logs}
 * remains the durable backstop and is always active regardless of
 * Redis availability (the hot-path layer fails open).</p>
 */
package io.cortex.ingest.dedupe;
