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
