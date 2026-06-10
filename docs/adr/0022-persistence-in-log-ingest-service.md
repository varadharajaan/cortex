# 0022 - persistence in log-ingest-service: Spring Data JDBC over JPA

Status: Accepted
Date: 2026-05-31
Deciders: CORTEX core engineering
Tags: P4.0, ingest, persistence, postgres, jdbc, jpa

## Context

log-ingest-service is the durable system-of-record for raw log
batches submitted by `log-agent-lib` and other producers. P4.0
scaffolds the module; P4.1 adds the `raw_logs` table and the
durable write path; P4.2 layers Redis SETNX dedupe + Postgres
`UNIQUE (tenant_id, event_id)` on top; P4.4 publishes enriched
batches onto Spring Cloud Stream (Azure Service Bus in `prod`,
Kafka in `dev` / `ci`).

The persistence shape this service needs is unusually narrow for a
Spring Boot service:

- **One write path** -- append `raw_logs` rows from a batch handler.
- **One indexed read path** -- lookup by `(tenant_id, event_id)` for
  dedupe.
- **One housekeeping path** -- TTL eviction by `received_at` once
  P4.5 wires the retention job.
- **No graph traversal, no lazy loading, no second-level cache, no
  N+1 risk.** A batch is a flat list of `LogEntry` records; the row
  shape is fixed at write time.

The strict-rules contract (PART 19 / rule 19.1) leaves the choice of
ORM open: "the persistence layer is an implementation detail of each
service; pick the lightest abstraction that satisfies the read /
write shape of that service." This ADR makes that choice explicit
for log-ingest-service so the same decision is not relitigated in
every sub-phase review.

## Decision drivers

- **D1. Narrow shape.** Spring Data JDBC is built for aggregate-root
  CRUD. Our write path inserts a `RawLog` aggregate (no children);
  our read path is a single-column lookup. JPA's relationship
  graph + dirty-tracking infrastructure buys us nothing here.
- **D2. Predictable SQL.** Ingest is on the hot write path. Spring
  Data JDBC issues the SQL we wrote (or generates straightforward
  inserts from the aggregate); there is no fetch plan, no flush
  ordering, no `@Transactional` lifecycle to reason about per
  request. This makes p99 write latency predictable.
- **D3. Zero managed state.** JPA's `EntityManager` keeps a
  per-transaction identity map; Hibernate adds a session-scoped
  first-level cache. Ingest is fire-and-write -- nothing reads back
  the row in the same transaction. Carrying a cache we never query
  is dead weight.
- **D4. Migration tooling unchanged.** Flyway + `flyway-database-postgresql`
  remain the schema authority regardless of which mapping
  framework we pick. Spring Data JDBC does not introduce a parallel
  DDL story (JPA's `hbm2ddl.auto` is a foot-gun we already ban via
  PART 19 / rule 19.4).
- **D5. ArchUnit-enforceable.** "No production class depends on
  `jakarta.persistence..` or `org.hibernate..`" is a one-line
  ArchUnit rule. The same rule on JPA would be vacuous.
- **D6. Reactive door left open.** If P10 needs a reactive ingest
  variant, Spring Data R2DBC is the drop-in successor to Spring
  Data JDBC; JPA has no equivalent reactive story.
- **D7. Less to learn for new contributors.** The repository methods
  are derived from method names exactly the same way Spring Data
  JPA does it -- no second mental model.

## Considered options

### Option A - Spring Data JDBC (CHOSEN)

- Lightweight aggregate mapping; no proxies, no lazy graphs, no
  `EntityManager` lifecycle.
- Repository interface; method-name query derivation; explicit
  `@Query("...")` when needed.
- Starter: `spring-boot-starter-data-jdbc` (already pulls
  HikariCP + Spring TX).
- Driver and migrations: `postgresql` + `flyway-core` +
  `flyway-database-postgresql` (already pinned in cortex-parent).
- Aggregate root carries `@Id` + `@Table`; no relationship
  annotations needed for `raw_logs`.

### Option B - Spring Data JPA + Hibernate (REJECTED)

- Pulls Hibernate ORM + Jakarta Persistence + Antlr (~6 MB extra
  on the classpath) for capabilities we do not use.
- `@Transactional` semantics on a single-statement insert add a
  layer of indirection (entity attach -> dirty check -> flush)
  without buying any correctness.
- Default lazy-loading + `OpenEntityManagerInView` are footguns
  that we would have to explicitly disable in every profile.
- Larger startup time (Hibernate's metamodel build is the single
  biggest cost in a Spring Boot 3.x ingest service today).

### Option C - jOOQ (REJECTED for P4)

- Excellent for complex queries; ingest has none.
- Adds a code-generation step against the live schema (-> new
  Maven plugin + Postgres-on-CI requirement on every build).
- Worth reconsidering at P7 (search service has joinable
  read paths).

### Option D - Hand-rolled `JdbcTemplate` only (REJECTED)

- No repository abstraction means every test goes through SQL
  string assertions; refactor cost is paid in test churn.
- Spring Data JDBC IS the thin layer on top of `JdbcTemplate`;
  picking raw `JdbcTemplate` saves zero dependencies and loses
  the derived-query ergonomics.

## Decision

Adopt **Spring Data JDBC** as the persistence layer for
log-ingest-service. Forbid `jakarta.persistence..` and
`org.hibernate..` in `io.cortex.ingest..` via ArchUnit (see
`ArchitectureRulesTest.NO_JPA` -- already in P4.0 scaffold). The
`spring-boot-starter-data-jdbc` dependency is the only persistence
starter on log-ingest-service's `pom.xml`.

## Consequences

### Positive

- Smaller classpath; faster cold start (no Hibernate metamodel).
- Predictable SQL on the hot write path; lower p99 latency.
- ArchUnit rule prevents drift -- a future PR cannot accidentally
  add `@Entity` and trigger JPA autoconfiguration.
- Migration story (Flyway) is unchanged and remains the schema
  authority.

### Negative

- Spring Data JDBC has a steeper learning curve for engineers
  coming from JPA (no lazy loading, no cascade, aggregates must
  be hand-modeled). The README and ADR-0022 itself mitigate
  this.
- Many-to-many relationships are awkward; if the schema ever
  grows one, the rule may need to be revisited.

### Neutral

- Future profile additions (reactive ingest, jOOQ for search)
  do not affect this decision -- they are scoped to their own
  service.

## References

- Spring Data JDBC reference docs: https://docs.spring.io/spring-data/relational/reference/jdbc.html
- plan.md section 9b (P4 sub-phases)
- memory.md "### P4 PLAN RATIFIED -- 2026-05-31" (D1, OQ4 -> RESOLVED)
- log-ingest-service/src/test/java/io/cortex/ingest/architecture/ArchitectureRulesTest.java (`NO_JPA`)

---

## Amendment - 2026-06-01 (P4.1 ratification)

Status: Accepted (amendment)
Date: 2026-06-01
Scope: locks in the `raw_logs` schema, the server-computed `event_id`
contract, the JSONB roundtrip pattern, and the duplicate-key unwrap
strategy that the P4.1 implementation chose. Issue #47, PR for branch
`feat/47-p4-1-validate-persist-raw`. No previous decision is reversed
or superseded.

### A1. `raw_logs` schema (Flyway V2)

`log-ingest-service/src/main/resources/db/migration/V2__raw_logs.sql`:

```sql
CREATE TABLE raw_logs (
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

CREATE INDEX raw_logs_received_at_idx       ON raw_logs (received_at);
CREATE INDEX raw_logs_tenant_service_ts_idx ON raw_logs (tenant_id, service, ts DESC);
```

- `UNIQUE (tenant_id, event_id)` is the cold-path dedupe key. Hot-path
  Redis SETNX still lands in P4.2 -- but every retry that races past
  the cache must be absorbed silently at the DB so the API stays
  idempotent.
- `received_at` index supports the P4.5 retention sweeper.
- `(tenant_id, service, ts DESC)` is the canonical read shape the search
  service (P7) will project from.
- `labels` is `JSONB`, NOT `TEXT`. JSONB compresses, indexes via GIN if
  ever needed, and Postgres validates the input. See A4 for the Spring
  Data JDBC binding pattern.

### A2. Server-computed `event_id`

The wire `LogEntry` does NOT carry an `eventId` field. P4.1 computes it
server-side as:

```
event_id = sha256_hex(
    tenantId | service | ts.epochMicros | message | sortedLabelsJson
)
```

with FIELD_SEPARATOR = `|` (single ASCII pipe), `ts.epochMicros` as a
base-10 string of Unix epoch microseconds, and `sortedLabelsJson` the
canonical JSON encoding of the labels map after sorting keys
lexicographically. The implementation lives in
`io.cortex.ingest.service.impl.IngestServiceImpl.computeEventId` (see
the `FIELD_SEPARATOR`, `MICROS_PER_SECOND`, `NANOS_PER_MICRO`,
`HEX_PER_BYTE`, `BYTE_MASK` constants there).

Why server-side:

- **Tamper-resistant.** Clients cannot bypass dedupe by mutating or
  omitting an id field.
- **Deterministic.** Two identical payloads from any producer collapse
  to the same row. This is the contract the cold-path UNIQUE relies on
  to mean "same event".
- **Idempotency separate.** `Idempotency-Key` (P4.4 outbox /
  publish-once) is an orthogonal request-scoped key; `event_id` is the
  durable row identity.

### A3. Tenant resolution (`X-Tenant-Id`, JWT-claim ready)

`io.cortex.ingest.tenant.TenantResolver` requires the `X-Tenant-Id`
header today and rejects null / blank with
`ApplicationException(VALIDATION_FAILED, ...)`. The interface is
designed so the P5.x service-JWT introspection can drop in a
JWT-claim resolution branch (`token.claim("tenant_id")`) without
touching `IngestController`.

### A4. JSONB roundtrip via `AbstractJdbcConfiguration.userConverters()`

The `labels` column is bound through two converters wired in
`io.cortex.ingest.persistence.JdbcConvertersConfig`, which extends
`AbstractJdbcConfiguration`. Two non-obvious choices are LOCKED here:

- **Extend `AbstractJdbcConfiguration` and override `userConverters()`.**
  Exposing a bare `@Bean JdbcCustomConversions` works in isolation but
  REPLACES the conversion service Spring Boot's
  `SpringBootJdbcConfiguration` wires; in particular the dialect-specific
  store conversions are lost and writes silently revert to inferring
  `VARCHAR` from the Java type. Overriding `userConverters()` prepends
  our two converters while keeping the dialect store conversions
  intact.
- **Writing converter returns `JdbcValue.of(PGobject, JDBCType.OTHER)`,
  NOT a bare `PGobject`.** Even when the converter hands back a
  `PGobject`, Spring Data JDBC re-infers `VARCHAR` from the property's
  declared type (`Map<String, String>`) and binds with `Types.VARCHAR`.
  Postgres then refuses the cast and the insert dies with
  `column "labels" is of type jsonb but expression is of type character
  varying`. Wrapping the bind in `JdbcValue.of(obj, JDBCType.OTHER)`
  forces the parameter to be bound with `Types.OTHER`, which pgjdbc
  routes through `PGobject`'s own typed write path.

### A5. `DbActionExecutionException` unwrap for cold-path dedupe

`IngestServiceImpl.saveAbsorbingDuplicate` catches BOTH:

- a bare `org.springframework.dao.DuplicateKeyException` (fallback for
  contexts where Spring Data JDBC does not wrap), AND
- an `org.springframework.data.relational.core.conversion.DbActionExecutionException`
  whose `getCause() instanceof DuplicateKeyException`.

Spring Data JDBC wraps repository-action exceptions inside
`DbActionExecutionException`; a narrow `catch (DuplicateKeyException)`
silently misses every cold-path duplicate that arrives via a
`@Repository.save` call. The two-arm catch keeps dedupe absorption
correct for both code paths. Any other `DbActionExecutionException`
cause is rethrown.

### A6. `postgresql` MUST be at `compile` scope (not `runtime`)

`org.postgresql.util.PGobject` is referenced directly from
`JdbcConvertersConfig` (production code, not just the JDBC driver
runtime ServiceLoader). Pinning `postgresql` at `runtime` scope, the
default for vanilla web modules, fails compilation. The
`log-ingest-service/pom.xml` overrides scope to `compile`.

### A7. Testcontainers 1.21.4 BOM bump (Docker Engine 29 on Windows)

`org.testcontainers:testcontainers-bom:1.21.4` is the lowest version
whose bundled `docker-java` client speaks the Docker Engine 29 API on
Windows Docker Desktop. The 1.20.x BOM bundled by Spring Boot's
managed dependencies handshakes against Engine 28 and throws
`ApiVersion not supported` against current Docker Desktop. The parent
pom now overrides the BOM; every Testcontainers-using module
(log-ingest-service, log-gateway, future P5..P10 services) inherits
the override automatically.

### A8. Failsafe explicit in module pom

The integration-test reactor relies on `*IT.java` execution via
`maven-failsafe-plugin`. Inheritance from the parent pom alone leaves
the plugin in `<pluginManagement>` only; the module pom MUST add an
explicit `<plugin>` entry with `<configuration><goals>` for `verify`
to actually run the ITs. This is the same pattern P3 (log-gateway)
already uses; P4.1 confirms it for log-ingest-service.

### A9. JaCoCo gate ON; `GlobalExceptionHandler` NOT excluded

P4.0 set `<skip.jacoco>true</skip.jacoco>` for log-ingest-service to
let the scaffold land without coverage. P4.1 flips the gate ON. The
parent JaCoCo `<excludes>` only excludes `**/exception/*Exception.class`
(i.e. concrete `*Exception` types), NOT `**/exception/**`. That means
`GlobalExceptionHandler` IS counted by the bundle gate and requires
real switch-arm coverage; see
`io.cortex.ingest.exception.GlobalExceptionHandlerTest` (12 tests
covering every `ErrorCodes` branch + the three Spring-built-in
overloads + the catch-all).

### A10. Out of scope for this amendment

- Redis SETNX hot-path dedupe (P4.2).
- PII masking on the write path (P4.2).
- Correlation-id propagation + end-to-end split + geo enrichment
  (P4.3).
- Spring Cloud Stream publish + outbox (P4.4).
- Retention sweeper consuming the `received_at` index (P4.5).

### A11. Verification evidence

- `mvnw verify -B` (full reactor): BUILD SUCCESS, 99 tests pass (51
  agent + 43 gateway + 5 ingest test classes); both `log-gateway` and
  `log-ingest-service` meet the 0.80 line and branch BUNDLE gates.
  Reactor green at branch HEAD prior to PR open.
- `scripts/smoke-p4-1.ps1` against the live booted stack
  (cortex-smoke-postgres + eureka:8761 + ingest:8092): 10 / 10 groups
  PASS, including DB row-count deltas via `docker exec ... psql`.
- `npx newman run postman/log-ingest.postman_collection.json`: 8
  requests / 21 assertions / 0 failed.

### A12. Lessons distilled

The amendments above were arrived at the hard way; the matching
LD entries land in `memory.md` under "### P4.1 SHIPPED" so future
modules do not relitigate them.

## Amendment 2 - 2026-06-09 (P9.2a inbound REST read surface)

Status: Accepted (amendment)
Date: 2026-06-09
Scope: adds the first **read** accessor over `raw_logs` and the
tenant-scoped `GET /api/v1/logs/{eventId}` HTTP surface that backs the
gateway `getLogById` query (P9.2b / ADR-0004 / ADR-0049). Issue #141.
No previous decision is reversed; this is purely additive (P4.1 shipped
the write path; P9.2a adds the read path on the same system-of-record).

### A2.1. Why the read lives in log-ingest-service

log-ingest-service owns `raw_logs` (it is the system-of-record). Rather
than have the gateway reach into another service's database or duplicate
the projection, the read endpoint lives next to the write, mirroring the
P9.1a decision that put the indexer search surface in log-indexer-service
(ADR-0042 Amendment 1). The gateway (P9.2b) forwards the resolved
`X-Tenant-Id` + the `eventId` path variable over `lb://log-ingest-service`
to this endpoint, exactly as P9.1b forwards to the indexer.

### A2.2. Endpoint contract

`GET /api/v1/logs/{eventId}` (constant `ApiPaths.LOGS_BY_ID`):

- **Tenant** is taken from the required `X-Tenant-Id` header (the single
  source of truth, ADR-0009). It is read as `@RequestHeader(required =
  false)` + an explicit blank check that throws
  `ApplicationException(VALIDATION_FAILED)` -> 400, because
  `required = true` would raise `MissingRequestHeaderException`, for
  which the ingest `GlobalExceptionHandler` has no arm (it would surface
  as a generic 500). This mirrors the P9.1a / P9.1b posture.
- **Lookup** is `RawLogRepository.findByTenantIdAndEventId(tenantId,
  eventId)` -- a Spring Data JDBC derived query. The
  `UNIQUE (tenant_id, event_id)` constraint (A1) guarantees at most one
  row, so the return type is `Optional<RawLog>`.
- **Verdict -> HTTP** (RFC 7807 via the existing `GlobalExceptionHandler`):
  hit -> `200` with a `LogResponse`; miss -> `404 NOT_FOUND`;
  missing/blank tenant -> `400 VALIDATION_FAILED`.
- **No Spring Security** on the ingest side: the gateway authenticates
  the caller and forwards the resolved tenant header. The tenant scoping
  in the query is what stops one tenant reading another's row by guessing
  an `eventId`.

### A2.3. `LogResponse` projection

`LogResponse` (a record, per rule 8.4) projects `RawLog` onto the public
read shape: `eventId, tenantId, ts, level, service, message, labels,
receivedAt`. The internal surrogate `id` and the `idempotencyKey` are
deliberately NOT exposed -- the `id` is a database implementation detail
and the idempotency key is an inbound-request artefact; the `eventId` is
the stable caller-facing identifier (it is the path variable the caller
supplied). `Instant` fields serialize as ISO-8601
(`write-dates-as-timestamps=false`, Spring Boot default).

### A2.4. Rejected alternatives

1. **Gateway reads Postgres directly.** Rejected -- couples the gateway
   to the ingest schema + credentials and duplicates the projection;
   breaks the system-of-record boundary.
2. **Return the `RawLog` aggregate directly.** Rejected -- leaks the
   surrogate `id` + `idempotencyKey` onto the wire; the `LogResponse`
   record is the read contract.
3. **Expose by surrogate `id`.** Rejected -- `id` is a DB detail and is
   not tenant-scoped; `(tenant_id, event_id)` is the stable, safe key.

## Amendment 3 - 2026-06-10 (unknown-tenant integrity violation -> 400)

Status: Accepted (amendment)
Date: 2026-06-10
Scope: maps a Postgres referential-integrity failure on the write path to
a clean `400 VALIDATION_FAILED` instead of a generic `500`. No previous
decision is reversed; this tightens the existing write-path error contract
(A1 / the P4.1 ratification). Caught by the P10.1 live e2e sweep.

### A3.1. The defect

`raw_logs.tenant_id` carries a foreign key to the `tenants` table (A1).
Posting a batch for a tenant that has not been provisioned (no `tenants`
row) makes the Spring Data JDBC insert fail with a
`DbActionExecutionException` wrapping a
`DataIntegrityViolationException` (Postgres FK violation). The ingest
`GlobalExceptionHandler` had no arm for either, so the failure surfaced
as `500 unhandled ingest error` -- a server-fault status for what is
actually a bad request (an unknown tenant the caller chose).

### A3.2. The contract fix

`GlobalExceptionHandler` gains one
`@ExceptionHandler({DataIntegrityViolationException.class,
DbActionExecutionException.class})` arm that returns RFC 7807
`400 VALIDATION_FAILED` with the bounded, non-leaking message "the
request references an unknown or unprovisioned tenant". The raw cause is
unwrapped via `NestedExceptionUtils.getMostSpecificCause(ex)` for
server-side logging ONLY -- the SQL / constraint name is never echoed to
the client (OWASP A09: no internal detail leakage). This mirrors the
existing `VALIDATION_FAILED` posture for Bean Validation failures, so the
caller sees one consistent 4xx contract for every "your input is the
problem" case.

### A3.3. Why not auto-provision the tenant

Rejected -- silently creating a `tenants` row on first ingest would let a
typo'd or spoofed tenant id mint a live tenant, defeating the FK as a
provisioning gate. Tenant creation stays an explicit out-of-band step;
ingest validates against it and rejects unknowns at the boundary.

