# 0038. log-indexer-service `QuickwitIndexAdmin` SPI + per-backend admin contract

- Status: accepted
- Date: 2026-06-06
- Deciders: @varadharajaan
- Tags: indexer, quickwit, admin, search-tier, scaffold

## Context and problem statement

P7 introduces the `log-indexer-service`: a new Spring Boot module
that owns the Quickwit full-text-search index lifecycle (create /
drop / retention / cardinality budgets + future search proxy) for
the CORTEX platform. The writer side (`QuickwitSink`) already lives
in `log-processor-service` P5.3 + ADR-0030 -- it streams parsed
events into a per-tenant Quickwit index. But nothing in the
codebase today owns the index ADMIN surface: today the operator
manually `curl`s `POST /api/v1/indexes` against the local Quickwit
container; that is fine for a single-developer scaffold and
unworkable as soon as multi-tenant onboarding lands.

P7.0 ships the scaffold: the new module, the `QuickwitIndexAdmin`
SPI, a `NoopQuickwitIndexAdmin` default that boots green with no
Quickwit dependency, a Micrometer counter, and a health indicator.
The actual Quickwit HTTP admin client lands in P7.1 (#TBD); the
scheduled retention sweeper in P7.2; per-tenant cardinality
budgets in P7.3; the search proxy in P7.4. To keep those follow-up
PRs narrow we need to lock the admin contract NOW, before any real
backend exists: a Service Provider Interface
(`QuickwitIndexAdmin`) the caller talks to, a verdict record
(`IndexAdminResult`) the metric layer reads, an input record
(`IndexSpec`) that carries the three identification fields, and a
binder-gate property (`cortex.indexer.admin.backend`) that
selects exactly one admin implementation per profile.

The open question this ADR settles is the SHAPE of that SPI:
synchronous vs reactive, single-bean vs multi-bean fan-out,
exception-throwing vs verdict-returning, how the backend / outcome
/ tenant tags reach the Micrometer counter without exploding
cardinality, and -- critically -- where the OWNERSHIP BOUNDARY
between this service and `log-processor-service` P5.3 lives. We
also need to decide whether the P7.0 scaffold ships with a no-op
default or whether the caller short-circuits when no backend is
configured.

## Decision drivers

- D1 -- the admin interface MUST be synchronous + thread-safe so
  it can be called from REST controllers (P7.4 search proxy +
  admin endpoints) AND scheduled retention sweepers (P7.2)
  without contortions. Reactive (`Mono<...>`) would force a
  second concurrency model on a service whose primary surface is
  REST + scheduled tasks, with zero throughput win for outbound
  HTTP calls that are already faster than the upstream cadence.
- D2 -- the input MUST carry exactly three fields (`tenantId`,
  `indexId`, `docMappingVersion`) so the SPI signature stays
  stable as the P7.1+ HTTP admin client materialises the full
  Quickwit doc-mapping JSON downstream from
  `docMappingVersion`. The full Quickwit `indexId` format
  (`cortex-<tenantId>-<docMappingVersion>`) is enforced by the
  P7.1 binding -- the SPI accepts whatever id the caller chose
  so the test harness can substitute fixtures.
- D3 -- the verdict record MUST carry exactly three bounded
  enum-like strings (`backend`, `outcome`, `reason`) so the
  `cortex.indexer.index_admin_total{backend,outcome,tenant_id}`
  counter stays inside the Part 17 tag-key allowlist with
  bounded cardinality (noop/quickwit x 6 outcomes x N tenants
  = predictable cap).
- D4 -- exactly ONE admin backend bean MUST be active in any
  given profile, selected by `cortex.indexer.admin.backend` via
  `@ConditionalOnProperty`, so the caller never has to multiplex
  over a list of admin backends. (`IndexerMetrics` and
  `QuickwitHealthIndicator` still inject `List<...>` /
  the single bean for OCP bootstrap, but the runtime call path
  resolves to exactly one bean.)
- D5 -- the default (no property set) MUST be a no-op admin
  that returns `IndexAdminResult.noop(...)` so the P7.0 scaffold
  boots green without ANY downstream HTTP dependency (no
  Quickwit container, no admin credentials, no port mapping
  contention with the local Quickwit dev container the writer
  side talks to). `matchIfMissing=true` on the
  `@ConditionalOnProperty` gate ensures this.
- D6 -- the admin SPI MUST NOT throw on transient downstream
  failures (Quickwit 429, 5xx, IO timeout); a verdict with
  `outcome=transient_failure` ticks the failed-outcome counter
  and lets the caller decide retry policy. Throwing is reserved
  for adapter contract violations (null arg, illegal config) and
  is logged + counted by the caller's catch-all. Symmetric with
  the P6 `RemediationDispatcher` contract per ADR-0032 D6.
- D7 -- the SPI shape MUST be agnostic to the future P7.2
  scheduled-sweeper + P7.3 cardinality-budget rejection paths
  so we don't have to break the contract when retention +
  budgets land. Returning a verdict (instead of throwing)
  reserves those future axes.
- D8 -- the OWNERSHIP BOUNDARY between this module and P5.3 +
  ADR-0030 MUST be documented in writing (this ADR) and
  enforced in code (the indexer module has no source-level
  reference to `QuickwitSink`; the ArchUnit layered contract +
  the `dependencyConvergence` enforcer + the indexer's own pom
  combine to make the boundary mechanical).

## Considered options

1. **Synchronous `IndexAdminResult ensureIndex(IndexSpec) +
   IndexAdminResult dropIndex(String indexId) + String
   backendId()` SPI with exactly one backend bean per profile
   selected by `cortex.indexer.admin.backend` +
   `@ConditionalOnProperty`; default `NoopQuickwitIndexAdmin`
   gated by `matchIfMissing=true`.** -- accepted.
2. **Reactive `Mono<IndexAdminResult> ensureIndex(IndexSpec)`
   SPI backed by Spring WebFlux + Reactor.** -- rejected: the
   rest of the service is servlet
   (`spring-boot-starter-web` + `RestClient` blocking) per
   ADR-0014 + LD42 + LD121; introducing Reactor here forces a
   second concurrency model on a service that runs one Tomcat
   request thread per call + a `@Scheduled` retention sweeper
   on the main scheduler, with zero throughput benefit for
   outbound HTTP calls that are already orders of magnitude
   faster than the upstream cadence. The same resilience
   primitives (Resilience4j `CircuitBreaker` + `TimeLimiter`)
   work identically on a blocking call.
3. **Co-locate the admin surface inside
   `log-processor-service` next to `QuickwitSink`.** --
   rejected: violates LD3 ("the indexer is the Quickwit
   owner"); welds two unrelated lifecycles (writer + admin)
   into one deployment + one team + one release cadence; the
   processor's existing reactor surface (Kafka consumer +
   Spring AI classifier + sink fan-out) is already the largest
   surface in the platform and bears no responsibility for
   index lifecycle. The admin surface needs its own SLOs
   (admin-call success rate, retention sweep completion) that
   would be lost in the processor's noise.
4. **Native Quickwit Java SDK (no such SDK exists; the
   community options are auto-generated OpenAPI clients with
   transitive Jackson 2.10 pins that fight Spring Boot
   3.3.6).** -- rejected on dependency-tree grounds. The
   platform already pins HTTP/1.1 `JdkClientHttpRequestFactory`
   per LD42; a plain `RestClient` + 4 lines of JSON per admin
   call is smaller, easier to audit, and avoids the
   dependency-convergence failures the parent POM Enforcer
   rule would catch.
5. **Single shared `HttpQuickwitIndexAdmin` parameterized by
   YAML doc-mapping templates per tenant.** -- rejected: the
   Quickwit doc-mapping JSON varies per `docMappingVersion`
   (v1 = log-only fields; v2 will add anomaly-classification
   fields per the P5 epic; future versions will land
   incrementally). Folding them behind one bean either bloats
   the config (YAML-as-code for each schema version) or forces
   a template-string indirection that's strictly harder to
   test than three small admin classes keyed on
   `docMappingVersion`. Separation per ADR-0030 (sinks) +
   ADR-0032 (dispatchers) is the established pattern.
6. **Server-side bus -- republish admin commands to per-tenant
   topics (`cortex.indexer.admin.<tenantId>.v1`) consumed by
   separate workers.** -- rejected: the admin surface is
   request/response by nature (callers want to know whether
   the index was created before returning HTTP 201 to the
   tenant onboarding UI). A Kafka hop adds an async boundary
   that turns every admin call into a poll loop on the
   verdict topic, with no testability win over an in-process
   SPI. Retention sweeper + cardinality budgets are
   periodic / synchronous and need no async fan-out.
7. **Use Spring Boot autoconfiguration over
   `@ConditionalOnProperty`.** -- rejected at this stage: the
   admin surface is internal to this service; there is no
   third-party consumer that would benefit from a
   `META-INF/spring/...imports` file. The pattern matches
   ADR-0030 (per-sink feature gates) + ADR-0032 (per-channel
   dispatcher gates) + ADR-0033/34/35 (per-adapter gates).

## Decision outcome

We pick option 1: a synchronous `QuickwitIndexAdmin` SPI with
exactly three methods (`ensureIndex(IndexSpec)`,
`dropIndex(String)`, `backendId()`), an immutable
`IndexAdminResult` verdict record carrying
`(backend, outcome, reason)`, an immutable `IndexSpec` input
record carrying `(tenantId, indexId, docMappingVersion)`, a
binder-gate property `cortex.indexer.admin.backend` resolved
by `@ConditionalOnProperty` on each backend impl, and a
default `NoopQuickwitIndexAdmin` (gated
`havingValue="noop", matchIfMissing=true`) that returns
`IndexAdminResult.noop("noop admin (P7.0 scaffold)...")` for
every call. The Micrometer counter
`cortex.indexer.index_admin_total{backend, outcome, tenant_id}`
is bootstrap-registered at `@PostConstruct` so the
`/actuator/prometheus` surface shows the counter family on the
first scrape, before any admin call ticks. The OCP-flipped
bootstrap loop iterates over the injected
`List<QuickwitIndexAdmin>` so adding a new admin backend (e.g.
the P7.1 `QuickwitHttpIndexAdmin`) requires zero edits in
`IndexerMetrics`. The `QuickwitHealthIndicator` is bound to
`/actuator/health/quickwit` and surfaces the active backend id
as a detail (P7.0 reports UP unconditionally for the noop
backend; the P7.1+ binding probes `GET /api/v1/health` on the
real Quickwit endpoint).

The ownership boundary against `log-processor-service` is
captured in three mechanical places: (a) this ADR; (b) the
indexer module's `pom.xml` has zero dependency on the processor
module; (c) the ArchUnit layered contract on the indexer side
keeps the `io.cortex.indexer.admin` package self-contained.
The processor side keeps its existing `QuickwitSink` writer +
ADR-0030; nothing in the processor needs to change as P7
ships.

## Consequences

- The P7.0 scaffold module is the SMALLEST a CORTEX service
  module has shipped at since P3.0: 11 production java files
  (App + 3 admin SPI files + 1 noop default + 1 metrics + 1
  health + 1 root `package-info` + 3 sub-package `package-info`)
  + 7 test files + 3 resources files + README + ADR-0038 +
  INDEX bump + CHANGELOG entry. No Kafka. No CloudEvents. No
  Testcontainers. No WireMock. 29 tests / 0 failures /
  JaCoCo 0.80/0.80 met from day one.
- The OCP bootstrap loop in `IndexerMetrics` is the same
  pattern P6.0a / ADR-0036 introduced for
  `RemediationMetrics`: a `@PostConstruct bootstrapMeters()`
  walks `List<QuickwitIndexAdmin>` and registers
  `{created, exists, dropped, transient_failure,
  permanent_failure}` for each backend's
  `backendId()`. Adding a new backend in P7.1 ships zero
  edits to the metrics layer.
- The P7.1 follow-up PR will introduce
  `QuickwitHttpIndexAdmin` (gated
  `havingValue="quickwit"`); the bean will register
  alongside (NOT instead of) the noop default, and the
  `@ConditionalOnProperty` matrix will resolve at startup to
  exactly one. The `IndexerMetrics` bootstrap loop will see
  both backends in the list during a hybrid dev run (where
  the operator wants to compare verdicts side by side) but
  the call path inside `ensureIndex` / `dropIndex` will only
  invoke the configured one.
- The P7.1a closer (per LD104 closer-pattern) will land the
  cross-phase Failsafe IT (singleton Testcontainers Quickwit
  container shared across P7.1..P7.4 subclasses), the
  PowerShell full-stack boot smoke, and the Postman
  collection. P7.0 explicitly does NOT ship those legs; the
  scaffold-phase precedent (P3.0, P6.0) is Leg A only
  (`mvn verify` BUILD SUCCESS).
- The ownership boundary is now PERMANENT: any future P7
  follow-up that needs to touch the Quickwit writer side
  MUST route through the processor module + ADR-0030, NOT
  by reaching into `QuickwitSink` from the indexer.
  Symmetrically, the processor side MUST NOT take a
  dependency on `QuickwitIndexAdmin`; the writer assumes the
  index exists and the indexer owns making that true.

## Pros and cons of the options

### 1. Synchronous `QuickwitIndexAdmin` SPI -- accepted

- **+** Matches the established ADR-0030 / ADR-0032 / ADR-0033 /
  ADR-0034 / ADR-0035 patterns; a fifth instance of "one SPI +
  one verdict record + one binder-gate property + one default
  no-op" is by now the project's house style.
- **+** Zero throughput cost vs reactive for an outbound HTTP
  workload that's already faster than the upstream cadence.
- **+** Trivial to unit-test: the noop default is a 4-line
  class; the verdict factories cover every outcome string.
- **-** Adds a third RPC tier to the platform (gateway -> service
  -> Quickwit admin) but only when the property is flipped to
  `quickwit`; P7.0 the scaffold has zero outbound HTTP.

### 2. Reactive (Mono<...>) SPI -- rejected

- **+** Composable with downstream WebFlux callers (none exist
  in CORTEX).
- **-** Forces a second concurrency model on a service whose
  primary surface is REST + scheduled tasks.
- **-** Loses the line-level + branch-level test simplicity of
  the synchronous variant; verdict assertions become
  `StepVerifier` chains for no gain.

### 3. Co-locate inside log-processor-service -- rejected

- **+** No new module.
- **-** Violates LD3 (the indexer is the Quickwit owner).
- **-** Welds two unrelated lifecycles into one deployment.
- **-** The processor module is already the largest surface in
  the platform; adding admin SLOs would lose them in the noise.

### 4. Native Quickwit Java SDK -- rejected

- **+** Zero hand-rolled JSON.
- **-** No first-party SDK exists; community options pull
  transitive dependencies that fight the Spring Boot 3.3.6
  pin.

### 5. Single parameterised `HttpQuickwitIndexAdmin` -- rejected

- **+** One class per backend rather than one class per schema
  version.
- **-** Doc-mapping JSON varies meaningfully per
  `docMappingVersion`; folding them behind one bean bloats the
  config or forces a template-string indirection that's
  strictly harder to test.

### 6. Server-side bus per-tenant admin topics -- rejected

- **+** Decouples the caller from admin latency.
- **-** Admin surface is request/response by nature; a Kafka
  hop turns every admin call into a verdict-topic poll loop.
- **-** Retention sweeper + cardinality budgets are
  periodic / synchronous and need no async fan-out.

### 7. Autoconfiguration over @ConditionalOnProperty -- rejected at this stage

- **+** Idiomatic for third-party-consumed starters.
- **-** No third-party consumer of this SPI exists; the
  binder-gate pattern matches ADR-0030 / ADR-0032 / ADR-0033 /
  ADR-0034 / ADR-0035.

## Links

- ADR-0001 -- Java 17 LTS, no virtual threads.
- ADR-0014 -- Spring Cloud Gateway MVC (servlet, not WebFlux).
- ADR-0016 -- Eureka for local discovery.
- ADR-0030 -- `ParsedEventSink` fan-out to Loki + Quickwit.
  This ADR carves the ownership boundary against P5.3 + ADR-0030
  on the writer side.
- ADR-0032 -- `RemediationDispatcher` SPI. This ADR
  intentionally mirrors the same single-method + single-verdict
  + binder-gate + default-noop pattern, one tier up in the
  platform.
- LD3 -- the indexer is the Quickwit owner.
- LD42 + LD121 -- `RestClient` + `JdkClientHttpRequestFactory`
  HTTP/1.1 pin + dual-timeout for the P7.1 HTTP admin client.
- LD92 -- port `:8097` allocation.
- LD100 -- `src/test/resources/application.yml` full shadow.
- LD104 -- closer-pattern (scaffold phase = Leg A only).
- LD106 + LD112 -- counter family bootstrap-registration with
  `unknown` placeholder + OCP-flipped bootstrap loop.
- LD125 -- per-channel topic isolation (applies in later P7.x
  phases that add Kafka consumers, NOT P7.0).
