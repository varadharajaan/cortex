# 0023 - log-ingest hot-path dedupe (Redis SETNX) and server-side PII masking

Status: Accepted
Date: 2026-06-01
Deciders: CORTEX core engineering
Tags: P4.2, ingest, redis, dedupe, pii, masking, idempotency

## Context

P4.1 landed the durable write path for `log-ingest-service`:
`POST /api/v1/ingest/batch` validates, computes a deterministic
`event_id`, and INSERTs each `LogEntry` into `raw_logs` with
`UNIQUE (tenant_id, event_id)` as the cold-path dedupe backstop
(ADR-0022 / amendment A2 / A5).

P4.2 adds the two pieces the spec (Sec 5.3 "Idempotency via Redis"
and Sec 7.2 "PII handling") requires before this service can go to
production:

- **Hot-path idempotency**: a request-scoped dedupe keyed on the
  caller-supplied `Idempotency-Key` header so a retried batch does
  NOT pay the cost of computing N `event_id` hashes + N INSERT
  attempts before the cold-path UNIQUE absorbs them. Cold-path
  remains the durable backstop.
- **Server-side PII masking**: the same `PiiMasker` the agent uses
  client-side (`log-agent-lib`) MUST run a second time on the
  ingest server BEFORE persistence so the durable store never holds
  raw email / JWT / AWS access key / credit-card / SSN strings,
  regardless of whether an out-of-date agent (or a non-agent
  producer) forwarded them in clear.

ADR-0017 (log-gateway rate limit) already picked Lettuce + Redis
for the same operational topology; this ADR records the new
ingest-side use of the same Redis cluster.

## Decision drivers

- **D1. Two-tier dedupe.** Hot path (Redis SETNX) handles the 99 %
  case where a retry arrives within the TTL window; cold path
  (Postgres UNIQUE) handles the case where Redis is unreachable,
  the `Idempotency-Key` header is missing, or the TTL has expired.
  Hot path saves cost; cold path provides the correctness guarantee.
- **D2. Fail-open on Redis.** Ingest availability MUST NOT be
  coupled to Redis availability. If `SETNX` throws
  `DataAccessException`, the service WARN-logs and proceeds as if
  the claim succeeded -- the cold-path UNIQUE still absorbs any
  duplicate that races through.
- **D3. Single round trip.** Atomic `SET ... NX EX` (one call) MUST
  be used; a `GET`-then-`SETEX` pattern is forbidden because it
  races between the two calls.
- **D4. Mask before persist, hash before mask.** `event_id` MUST be
  computed against the ORIGINAL pre-mask message so two entries
  whose PII masks to the same token (e.g. `alice@x.com` and
  `bob@y.com` both becoming `<email>`) still receive distinct
  `event_id`s and are NOT falsely deduped by the cold-path UNIQUE.
- **D5. Same masker as the agent.** Reusing `io.cortex.agent.pii.PiiMasker`
  from `log-agent-lib` keeps the mask token set identical on both
  sides (no client/server drift) and lets a single set of unit
  tests govern the regex behaviour.
- **D6. Observability is non-optional.** Every absorbed duplicate
  and every applied mask MUST publish a Micrometer counter so the
  SRE dashboard can answer "how many requests is hot-path dedupe
  saving?" and "how aggressive is server-side masking?" without
  log scraping.

## Considered options

### Option A - Redis `SET ... NX EX` per Idempotency-Key (CHOSEN)

- One atomic round trip; the standard pattern Redis was built for.
- TTL bounds memory growth without needing a sweeper.
- Lettuce is already on the classpath (transitively for the agent,
  newly added for ingest in P4.2 via `spring-boot-starter-data-redis`).
- `IdempotencyDedupeService.claim(tenantId, key)` returns a single
  boolean; the controller / service do not need to know how the
  store is implemented (R2DBC, in-memory, or a different cache
  could swap in without API change).

### Option B - Postgres `INSERT ... ON CONFLICT DO NOTHING RETURNING id`

- Pros: only one store; no fail-open contract to reason about.
- Cons: still costs the network + parse + plan + UNIQUE-violation
  per duplicate retry, which is exactly the cost hot-path dedupe is
  supposed to avoid. Cold-path UNIQUE already gives us this guarantee
  for free; layering ON CONFLICT on top would duplicate the check
  with no upside.

### Option C - In-process cache (Caffeine) per pod

- Pros: zero network hop.
- Cons: per-pod state; multiple ingest replicas would each accept
  the same retry, defeating the purpose. Same reason ADR-0017
  rejected an in-process bucket for the gateway rate limit.

### Option D - Redis-only dedupe (no Postgres UNIQUE backstop)

- Pros: simplest mental model -- one store decides.
- Cons: when Redis is unreachable, fail-open admits the duplicate
  into the durable store with no recourse. Two-tier dedupe is the
  only configuration that survives a cache-tier outage without
  corrupting the system of record.

### Option E - Mask on the agent only, trust the wire

- Pros: zero server-side regex cost.
- Cons: an out-of-date agent or a non-agent producer can ship raw
  PII directly into `raw_logs`; the durable store would then need a
  re-scrub migration the first time PII is detected. Belt-and-braces
  masking eliminates the audit risk.

### Option F - Mask BEFORE computing event_id

- Pros: one fewer field to track.
- Cons: collapses two genuinely-different events whose PII happens
  to mask to the same token (D4). Rejected -- violates the
  "event_id == row identity" contract from ADR-0022.

## Decision

Implement two-tier dedupe (Option A + cold-path UNIQUE from
ADR-0022) with fail-open on Redis (D2), and double-layer PII
masking (Option E rejected) with hash-before-mask ordering (D4 /
Option F rejected).

### Redis key shape

```
cortex:ingest:idem:{tenantId}:{idempotencyKey}
```

- The `cortex:ingest:idem:` prefix scopes the keyspace away from
  the gateway rate-limit buckets (`cortex:gateway:rate:`).
- `{tenantId}` is injected BEFORE `{idempotencyKey}` so two tenants
  sharing the same opaque idempotency-key value (e.g. a UUIDv7)
  cannot collide. This is verified end-to-end by
  `IdempotencyDedupeIT.claimIsScopedPerTenant`.
- The value is the opaque marker `"1"`; presence is the only
  signal. We deliberately do NOT cache the response body: the
  second call recomputes `receivedAt`, which is consistent with the
  existing cold-path replay behaviour and avoids the cache-coherency
  burden of caching mutable fields.

### TTL

`PT24H` by default, overridable via
`${cortex.ingest.dedupe.ttl:PT24H}`. The 24-hour window covers:

- the longest realistic retry window for a healthy agent (the
  `log-agent-lib` `BufferedSender` retries on transient failures
  with bounded backoff that maxes out well below this),
- a full operational on-call rotation (so a hot-path hit is still
  meaningful when the on-call engineer reviews overnight retries),
- comfortably less than the `raw_logs` retention window, so the
  cold-path UNIQUE remains the authority for retries that exceed
  the TTL.

### Fail-open contract

`IdempotencyDedupeService.claim` catches `DataAccessException`
(superclass of `RedisConnectionFailureException`,
`RedisCommandTimeoutException`, etc.), WARN-logs the failure with
`tenantId`, `idempotencyKey`, and the exception cause, and returns
`true`. The cold-path UNIQUE then absorbs any duplicate that races
through.

### Bean gating

`@ConditionalOnProperty(name = "cortex.ingest.dedupe.enabled",
havingValue = "true", matchIfMissing = true)`. The shared test
classpath `application.yml` sets `cortex.ingest.dedupe.enabled:
false` so slice / Postgres-IT tests do NOT need a Redis container
to boot; `IdempotencyDedupeIT` flips it back to `true` via
`@DynamicPropertySource`.

### Masking call-site

```
acceptBatch(req, tenantId, idempotencyKey):
  if dedupe present and !dedupe.claim(tenantId, idempotencyKey):
    METRIC cortex.ingest.dedupe.hits{path=hot} += total
    return IngestBatchResponse(total, receivedAt)
  persistBatchWithMasking(req, ...)  # always after the claim

persistBatchWithMasking:
  for each entry:
    eventId   = computeEventId(tenantId, entry)          # ORIGINAL message
    masked    = PiiMasker.mask(entry.message)            # masked AFTER
    rawLog    = toRawLog(entry, ..., masked.text())
    save with absorbing DuplicateKeyException
  METRIC cortex.ingest.mask.applied += sum(masked.appliedCount)
```

### Counters

Two Micrometer counters published via `micrometer-registry-prometheus`
(already on the actuator classpath):

- `cortex.ingest.dedupe.hits{path=hot|cold}` -- incremented by the
  absorbed batch entry count on the hot path, and by one per
  absorbed row on the cold path (both `DuplicateKeyException`
  catches inside `saveAbsorbingDuplicate`).
- `cortex.ingest.mask.applied` -- incremented by the cumulative
  `MaskResult.appliedCount` across the batch when greater than
  zero.

Counters are eagerly registered in the `IngestServiceImpl`
constructor so they are exposed at `/actuator/prometheus` even
before the first hit (avoids a "did the metric get registered?"
debugging cliff).

## Consequences

### Positive

- Hot-path retries cost one Redis round trip instead of N hash
  computations + N INSERT attempts.
- Durable store never holds raw PII, regardless of agent version
  or producer.
- Cold-path UNIQUE remains the correctness backstop -- a Redis
  outage degrades performance, not correctness.
- One masker, one set of rules -- agent and ingest cannot drift.
- Prometheus counters answer the operational questions without log
  scraping.

### Negative

- Adds a new infrastructure dependency to ingest (Redis). Mitigated
  by D2 (fail-open) and by sharing the same Redis cluster the
  gateway already uses (ADR-0017).
- `IdempotencyDedupeIT` boots two Testcontainers (Postgres +
  Redis), adding ~30 s of cold-start per CI run for ingest.
  Acceptable; the IT only runs under `mvn verify` (Failsafe), not
  on every unit-test cycle.

### Neutral

- The `Idempotency-Key` header remains OPTIONAL. Producers that do
  not set it are deduped only by the cold path; producers that DO
  set it get both.
- Future profiles (P4.4 outbox / publish-once) MAY share the same
  Redis cluster but MUST use a distinct key prefix
  (`cortex:ingest:outbox:`).

## References

- Spec Sec 5.3 "Idempotency via Redis", Sec 7.2 "PII handling".
- ADR-0017 (log-gateway rate limit, Lettuce + Redis topology).
- ADR-0022 (persistence; cold-path UNIQUE; event_id contract).
- plan.md row P4.2 ("Hot-path dedupe + server-side masking").
- memory.md "### P4 PLAN RATIFIED -- 2026-05-31" (D3, D4).
- log-ingest-service/src/main/java/io/cortex/ingest/dedupe/IdempotencyDedupeService.java
- log-ingest-service/src/main/java/io/cortex/ingest/service/impl/IngestServiceImpl.java
- log-ingest-service/src/test/java/io/cortex/ingest/dedupe/IdempotencyDedupeIT.java
- log-agent-lib/src/main/java/io/cortex/agent/pii/PiiMasker.java
