# 0025 - log-ingest transactional outbox (raw_logs + outbox_events per-row REQUIRES_NEW)

Status: Accepted
Date: 2026-06-02
Deciders: CORTEX core engineering
Tags: P4.4a, ingest, outbox, transactional, B10.1, ADR-0022, ADR-0023, ADR-0024

## Context

P4.1 made `log-ingest-service` durable (ADR-0022). P4.2 added
idempotent dedupe + PII masking (ADR-0023). P4.3 closed the
enrichment / correlation gap (ADR-0024). P4.4 closes the
remaining hot-path gap between "accepted" and "downstream
consumer can see it": **publish each accepted log line as an
event to the queue** (`cortex.logs.events.v1`) so the search
indexer (Loki ingester, P5), the analytics fan-out (P6), and the
self-healing rules engine (P7) can consume from the broker
instead of polling Postgres.

Three constraints make this non-trivial:

- **B10.1 (strict rule)**: never publish a domain event from
  inside a `@Transactional` method. A broker `send()` cannot be
  rolled back by a downstream JDBC failure, so a fire-and-forget
  publish inside the persistence transaction guarantees the
  "wrote but never published" or "published but never wrote"
  drift the outbox pattern exists to prevent.
- **Cold-path dedupe must not leak**: when the
  `UNIQUE (tenant_id, event_id)` constraint on `raw_logs` absorbs
  a duplicate batch entry, the consumer downstream MUST NOT see
  the duplicate event. If raw_logs rolls back but the outbox row
  commits, the poller will publish a "ghost" event that the
  system-of-record never accepted.
- **Per-row failures must not roll back the batch**: today the
  service intentionally does NOT wrap `persistBatchWithMasking`
  in a single `@Transactional` so that one duplicate row out of
  100 does not abort the other 99 inserts (ADR-0022). The outbox
  design must preserve that behaviour.

This ADR locks the shape of the outbox table, the transactional
boundary, the writer bean, and the rationale for the
deferred-publish split (publisher / broker / DLQ all land in
PR-2 / PR-3, see Plan section 9 P4.4b / P4.4c).

## Decision drivers

- **D1. Atomicity per-row, not per-batch.** Each `raw_logs` INSERT
  must commit (or roll back) atomically with its sibling
  `outbox_events` INSERT, and a cold-path dedupe on any one row
  must NOT roll back the rest of the batch.
- **D2. Spring AOP visibility.** The `@Transactional(REQUIRES_NEW)`
  boundary must live on a method that is invoked across a Spring
  proxy, not via a self-call inside `IngestServiceImpl`, because
  Spring AOP cannot intercept self-calls and the proxy would no-op.
- **D3. Schema migration tail.** Adding the outbox table must not
  require a destructive Flyway repair (no `V1` / `V2` rewrite, no
  `out-of-order: true`). The migration is the next monotonic
  version (`V3`) and is additive only.
- **D4. Read-path neutrality.** PR-1 deliberately ships no broker
  client, no scheduled poller, and no metrics that depend on
  publish state. The outbox is a write-side commitment for PR-1;
  the publish path lands in P4.4b. This keeps PR-1 reviewable
  without dragging in Spring Cloud Stream, Kafka, or Service Bus
  dependencies.
- **D5. Payload type pragmatism.** The outbox payload is opaque
  bytes for the poller; no `outbox_events.payload->>'field' = ?`
  read path is planned. TEXT keeps the storage simple and avoids
  the custom Spring Data JDBC converter that arbitrary-shape JSON
  would require (the existing `MapToJsonbWritingConverter` is
  typed for `Map<String,String>` and does not handle a String
  payload). Migrating to JSONB later is a one-line
  `ALTER TABLE ... USING payload::jsonb` if a read path appears.

## Decision

### Schema (Flyway V3)

```sql
CREATE TABLE outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    event_id        VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE INDEX outbox_events_pending_idx
    ON outbox_events (status, next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX outbox_events_tenant_event_idx
    ON outbox_events (tenant_id, event_id);
```

Notes:

- No foreign key to `raw_logs(tenant_id, event_id)`. The per-row
  REQUIRES_NEW transaction ensures the outbox row only exists if
  the raw_logs row INSERTed successfully; the FK would only
  duplicate that guarantee while doubling the constraint-check
  cost on the hot path.
- `(tenant_id, event_id)` is INDEXED but NOT UNIQUE because the
  raw_logs UNIQUE always fires first inside the same tx. The
  index supports observability joins back to `raw_logs`.
- `outbox_events_pending_idx` is a PARTIAL INDEX on the columns
  the P4.4b poller will scan (`status = 'PENDING'` filtered by
  `next_attempt_at`), keeping the index small as the table grows.

### Java surface

- `io.cortex.ingest.outbox.OutboxStatus` (enum: `PENDING`,
  `PUBLISHED`, `FAILED`) - mirrors the CHECK constraint.
- `io.cortex.ingest.outbox.OutboxEvent` - Spring Data JDBC
  aggregate root; `status` persisted as `String` (matches the
  CHECK), `id` is `null` on insert (BIGSERIAL supplies it). Static
  factory `OutboxEvent.pending(tenantId, eventId, payload,
  receivedAt)` for the PR-1 hot path.
- `io.cortex.ingest.outbox.OutboxRepository extends
  CrudRepository<OutboxEvent, Long>` - PR-1 only needs save +
  count; the poller's `findTop100ByStatusAndNextAttemptAtBefore`
  derived query lands in PR-2.
- `io.cortex.ingest.outbox.OutboxEventFactory` - `@Component`,
  pure function: serialises a `RawLog` into a deterministic JSON
  envelope (sorted label keys, ISO-8601 UTC timestamps).
- `io.cortex.ingest.outbox.RawLogTransactionalWriter` - `@Service`
  carrying the `@Transactional(propagation = Propagation.REQUIRES_NEW)`
  boundary; `writeRawLogAndOutbox(RawLog, OutboxEvent)` calls
  `rawLogRepository.save(raw)` then `outboxRepository.save(outbox)`
  inside one transaction; lets `DuplicateKeyException` /
  `DbActionExecutionException` propagate so `IngestServiceImpl`
  catches them in its per-row absorber.

### Wiring

`IngestServiceImpl` gains two constructor parameters
(`RawLogTransactionalWriter`, `OutboxEventFactory`).
`saveAbsorbingDuplicate` is renamed in signature only to take
the pre-built outbox event and now calls
`writer.writeRawLogAndOutbox(raw, outbox)` in place of the old
`repository.save(raw)`. The existing dedupe-absorber
`try { ... } catch (DuplicateKeyException | DbActionExecutionException ...)`
block is unchanged; the rollback semantics are identical
because the writer's REQUIRES_NEW transaction rolls back both
rows on any exception.

### Rollback semantics

| raw_logs INSERT | outbox INSERT | tx state | counter |
|-----------------|---------------|----------|---------|
| OK              | OK            | commit   | (none)  |
| DuplicateKey    | (never run)   | rollback | `cortex.ingest.dedupe.hits{path=cold}` +1 |
| OK              | DB error      | rollback | (rethrown to caller) |
| DB error        | (never run)   | rollback | (rethrown to caller) |

The duplicate path is the most important: the writer's first
save throws `DuplicateKeyException`, so the second save is
never invoked, AND the REQUIRES_NEW transaction rolls back any
auto-incremented sequence value that the first save touched.
The caller catches, increments the cold-path dedupe counter,
and continues the batch loop with the next entry.

### Counters (deferred to PR-2)

PR-1 ships NO new counters. The poller counters
(`cortex.ingest.outbox.pending`,
`cortex.ingest.outbox.published`,
`cortex.ingest.outbox.failed`,
`cortex.ingest.outbox.publish.latency`) all land in PR-2
alongside the poller bean that actually drives them.

## Consequences

### Positive

- Single source of truth: the outbox can never disagree with
  `raw_logs` because they commit (or roll back) together.
- Cold-path dedupe is bullet-proof: the system can never publish
  an event that the system-of-record rejected.
- PR-1 stays small, broker-free, and CI-green without Kafka or
  Service Bus client dependencies.
- The poller in PR-2 only needs read access to `outbox_events`;
  the publish path is fully decoupled from the request handler.

### Negative

- Two INSERTs per row instead of one. At the P4.1 measured cost
  (~3 ms / row at p99 against local Postgres) the second INSERT
  adds an estimated ~0.8 ms p99; well inside the spec budget
  (200 ms p99 batch ingest, Sec 11.1). Re-measure under load in
  P4.4c.
- `outbox_events.id` consumes a BIGSERIAL sequence value even on
  cold-path duplicates, because the first save claims the
  sequence value before the UNIQUE on `raw_logs` aborts. This is
  a cosmetic gap in the id sequence; it is NOT a correctness
  bug. The poller never sees the gapped id (the row rolled
  back). Documented here so a future engineer reading
  `SELECT max(id) - count(*) FROM outbox_events` does not file a
  spurious data-loss bug.
- The poller (PR-2) must be safe against partition restarts: at
  least once delivery is the contract; consumers must be
  idempotent on `(tenant_id, event_id)`.

### Neutral

- No new external dependency. PR-1 reuses the existing pgjdbc,
  Spring Data JDBC, and Jackson stack.

## Alternatives rejected

- **One batch-wide transaction.** Wraps `persistBatchWithMasking`
  in `@Transactional`; one duplicate aborts the entire batch.
  Rejected: contradicts ADR-0022 dedupe-absorber contract.
- **Direct broker publish inside the request handler.** Violates
  B10.1; loses durability on broker outage; no replay path on
  consumer-side failures. Rejected outright.
- **Outbox row inserted by a database trigger on raw_logs.**
  Decouples the application from the contract; harder to test;
  the Flyway migration becomes harder to roll back; the trigger
  body must duplicate the deterministic JSON-envelope logic that
  Jackson already does in Java. Rejected: keeps the Spring Data
  JDBC layer free of opaque server-side logic.
- **JSONB payload column from PR-1.** Requires a String -> JSONB
  custom converter; pays the JSONB validation cost at write time;
  no read path needs it in PR-1. Rejected for PR-1; can be
  migrated later with a single `ALTER TABLE ... USING
  payload::jsonb` (deferred to a future ADR if the read path
  actually appears).
- **Status persisted as Postgres ENUM instead of VARCHAR(16) +
  CHECK.** Cleaner semantically; but Postgres ENUMs require
  ALTER TYPE for additions which Flyway does not handle
  gracefully, and Spring Data JDBC has no out-of-the-box ENUM
  mapper. VARCHAR + CHECK keeps the migration story trivial.
- **CloudEvents envelope baked into the PR-1 payload.** The
  envelope serialisation must live with the publisher (PR-2)
  because the publisher owns the `source`, `type`, and `id`
  fields and may choose Avro / Protobuf at P14 (strict rule
  B9.2). Baking it in here would couple the writer to a binder
  decision that PR-1 deliberately does not make. Rejected for
  PR-1; the payload today is a stable Jackson JSON object that
  the publisher will wrap.

## Amendment to ADR-0022

ADR-0022 stated the persistence boundary is per-row auto-commit
without a `@Transactional` wrapper. That statement is now
narrowed: the per-row commit lives inside
`RawLogTransactionalWriter.writeRawLogAndOutbox` under
`Propagation.REQUIRES_NEW`. The batch-wide non-transactional
shape is preserved (no single tx wraps the whole loop), only
the per-row commit is now explicit.
