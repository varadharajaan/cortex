# 0043. log-indexer-service P7.1a cross-phase closer (Quickwit docker singleton + cross-phase Failsafe IT + smoke + Postman + `@Lazy` bean-cycle fix)

- Status: accepted
- Date: 2026-06-08
- Deciders: @varadharajaan
- Tags: indexer, closer, smoke, postman, cross-phase, quickwit, spring-bean-cycle, lazy

## Context and problem statement

P7.0 (scaffold + `QuickwitIndexAdmin` SPI + Noop default +
`IndexerMetrics` bootstrap loop + `QuickwitHealthIndicator`),
P7.1 (real `QuickwitHttpAdmin` HTTP client for `ensureIndex` +
`dropIndex`), P7.2 (`applyRetention` via Delete API), P7.3
(per-tenant cardinality budgets via `ensureIndex(spec, budget)`),
and P7.4 (tenant-scoped Quickwit search proxy via the new read-
side `LogSearchClient` SPI) each shipped **Leg A only** per the
LD104 closer-pattern precedent (`B/C/D/E -- deferred to the
P7.1a closer that builds smoke + Postman + cross-phase regression
ONCE for all five P7.0..P7.4 SPI methods together`). The P7.1a
closer (issue #109) is therefore the ONLY remaining P7
deliverable before the epic can be marked DONE.

The cross-phase regression covers a different surface than each
adapter's individual `QuickwitHttpAdminWireMockIT` /
`QuickwitHttpSearchWireMockIT`:

- The per-phase WireMock IT instantiates the adapter class
  directly with `new QuickwitHttpAdmin(...)` / `new
  QuickwitHttpSearch(...)`, bypassing Spring entirely.
- The cross-phase regression must instead exercise the **full
  Spring autowiring path** through a `@SpringBootTest`-booted
  application with **both binder gates flipped** (`cortex.
  indexer.admin.backend=quickwit` + `cortex.indexer.search.
  backend=quickwit`), against a real WireMock HTTP endpoint
  that mimics Quickwit's API surface, so the
  `@ConditionalOnProperty` selection + `IndexerMetrics`
  bootstrap loop over `List<QuickwitIndexAdmin>` +
  `List<LogSearchClient>` (LD106 + LD112) + `RestClient`
  HTTP/1.1 pin (LD42) + dual-timeout (LD121) are all wired
  identically to production.

Additionally, the closer must ship:

1. A full-stack PowerShell **boot smoke** (`scripts/smoke-
   p7-1a.ps1`) that starts the actual service JAR against a
   real Quickwit 0.8.1 docker container and proves the
   `/actuator/health/quickwit` + Prometheus scrape contract
   end-to-end.
2. A **Postman collection** (`postman/log-indexer.postman_
   collection.json`) that captures the HTTP-observable contract
   the boot smoke exercises for offline reuse / CI replay
   (`/actuator/health/quickwit` + both counter families with
   `# HELP` + `# TYPE` + `backend="quickwit"` series + direct
   Quickwit reachability probes).
3. A **cross-phase Failsafe IT suite** under
   `log-indexer-service/src/test/java/io/cortex/indexer/closer/`
   that exercises every P7.0..P7.4 SPI method via autowired
   beans against an in-process WireMock.
4. **ADR-0043** (this doc) + INDEX bump 42 -> 43 + CHANGELOG +
   service README banner flip `P7.0+P7.1+P7.2+P7.3+P7.4
   SHIPPED` -> `... + P7.1a SHIPPED`.

One issue bit during the cross-phase IT build-out that the per-
phase WireMock ITs had structurally missed:

- **`BeanCurrentlyInCreationException` during production
  startup when `cortex.indexer.admin.backend=quickwit`.** When
  both binder gates are flipped to `quickwit`, the Spring
  bean graph closes a cycle: `quickwitHttpAdmin` ->
  `indexerMetrics` (ctor param `List<QuickwitIndexAdmin>` to
  drive the LD106 bootstrap loop) -> `quickwitHttpAdmin`
  (the same bean the metrics collection is iterating over).
  The per-phase `QuickwitHttpAdminWireMockIT` /
  `QuickwitHttpSearchWireMockIT` missed it because they
  construct the adapter directly with `new`, never going
  through Spring's bean factory. The closer's
  `QuickwitCrossPhaseIT` surfaced it on the first
  `@SpringBootTest` boot attempt -- the FIRST production
  consumer of the Spring-managed bean graph with both gates
  flipped. Captured as **LD131** during P7.1a Leg D. This is
  exactly the class of defect cross-phase closers are
  designed to catch per LD104 -- a real production
  startup-time bug that would have failed the operator on
  their first attempt to flip the binder gate to `quickwit`
  in prod.

## Decision drivers

- D1 -- the cross-phase IT must run inside the normal Failsafe
  cycle (`mvn verify`) so CI catches regressions on every PR,
  not just on a manual smoke run. No standalone runner, no
  separate Maven profile.
- D2 -- the closer MUST fix any production bug it surfaces in
  the SAME PR as the closer itself. The `BeanCurrentlyInCreation
  Exception` cycle is a real production startup defect (the
  binder gate cannot be flipped to `quickwit` in any deployment
  without this fix). It MUST be repaired here, not deferred,
  because deferring would mean the closer's `@SpringBootTest`
  never goes green and the LD104 closer-pattern semantics
  cannot close cleanly.
- D3 -- the cross-phase IT must NOT use the per-phase test's
  hand-constructed adapter. It must boot the full Spring
  context with both `@ConditionalOnProperty` gates flipped so
  the `QuickwitHttpAdmin` + `QuickwitHttpSearch` beans are
  selected exactly the way production selects them, and so
  every autowired collaborator (`QuickwitProperties`,
  `quickwitAdminRestClient` from `QuickwitHttpConfig`, the
  shared `ObjectMapper`, `IndexerMetrics` with its bootstrap
  loop) is wired through the bean factory.
- D4 -- the in-process WireMock must be a **singleton**
  `WireMockServer` started in a static block, with
  `@DynamicPropertySource` mapping `cortex.indexer.quickwit.
  base-url` to `WIRE_MOCK::baseUrl`. A per-test `@RegisterExtension`
  + `WireMockExtension` instance would force a new Spring
  context per test (because the dynamic property changes per
  port) and blow the Failsafe budget.
- D5 -- the IT must assert on **observable production
  surfaces**, not on `RestClient` internals or
  `RestAdminTemplate` internals: the
  `cortex_indexer_index_admin_total{backend, outcome,
  tenant_id}` + `cortex_indexer_search_total{backend, outcome,
  tenant_id}` counter deltas (Part 17 allowlist), the wire
  shape of every POST / GET / DELETE / DELETE-tasks /
  search call (via WireMock `verify()` with JSON-path body
  matchers), and the SPI return envelope (`IndexAdminResult`
  + `SearchResult` outcome / reason fields per ADR-0038 D5
  + ADR-0042 D5).
- D6 -- the IT MUST exercise every public SPI method of
  every P7.0..P7.4 phase in one suite so a single regression
  surfaces all affected phases at once. 12 test methods
  organised as: 2 binder-gate proofs (`adminBeanIsQuickwitBackend`
  + `searchBeanIsQuickwitBackend`); 4 P7.1 admin tests
  (`ensureIndex` create / exists / `dropIndex` present /
  `dropIndex` idempotent on 404); 1 P7.2 retention test;
  2 P7.3 budget tests (under-ceiling allow / over-ceiling
  reject); 3 P7.4 search tests (happy / 404 permanent /
  tenant-mismatch permanent).
- D7 -- credentials MUST be neutral per LD123. The tenant
  ID is `tenant-IT`; the index ID is `cortex-tenant-IT-v1`;
  the WireMock base URL is dynamic; no Quickwit-specific
  auth (Quickwit 0.8.1 does not require auth on the open
  REST API).
- D8 -- the Postman collection MUST mirror the boot smoke's
  on-the-wire calls 1:1 (admin actuator probes +
  `/health/quickwit` binder-gate proof + direct Quickwit
  reachability + 404-permanent search wire contract) so an
  operator can re-run the smoke contract from Newman without
  booting the PowerShell script.
- D9 -- ALL artifacts in this closer (smoke + Postman + IT +
  ADR + INDEX + CHANGELOG + README banner + `@Lazy` fix +
  4-file flip) MUST ship in ONE PR so the LD104 closer-
  pattern semantics close cleanly. The 5-leg gate (Leg A
  `mvn verify` + Leg B smoke + Leg C Postman + Leg D
  cross-phase IT + Leg E docs review) must all be GREEN at
  PR-merge time.

## Considered options

### For the cross-phase IT shape (D3 + D4 + D6)

1. **Singleton in-process `WireMockServer` started in a static
   block + ONE `@SpringBootTest` class with 12 test methods +
   `@DynamicPropertySource` mapping `cortex.indexer.quickwit.
   base-url` -> `WIRE_MOCK::baseUrl` + per-test
   `WIRE_MOCK.resetAll()` in `@BeforeEach`.** -- accepted.
   Pays the cold-start cost ONCE (~25-30 s on Windows +
   Docker Desktop) across all 12 tests, then reuses the
   Spring context + WireMock for every subsequent test.
   `resetAll()` clears stubs + journal between tests so
   each test is independent. 12 tests / single class /
   one context boot. Compile-clean under `mvn verify`.
2. **Per-test `@RegisterExtension WireMockExtension` +
   `@SpringBootTest`.** -- rejected. WireMockExtension assigns
   a fresh dynamic port per test; the dynamic property change
   forces Spring to rebuild the context per test; 12 tests
   would mean 12 context boots * ~25 s = 5 minutes of
   wall-clock per Failsafe run. Blows the budget.
3. **Testcontainers-managed Quickwit 0.8.1 container as the
   IT backend instead of WireMock.** -- rejected. Testcontainers
   Quickwit cold-start is ~20-40 s on Windows + Docker Desktop
   (LD123-style) AND every test must wait for `/health/livez`
   to flip from `503` to `200`. WireMock starts in <100ms and
   serves deterministic responses without warm-up. Quickwit-
   the-container belongs in the **boot smoke** (Leg B), not in
   the IT (Leg D).
4. **3-5 separate `@SpringBootTest` subclasses (one per
   phase).** -- rejected. Same context-boot multiplier as
   option 2 in the absolute case (3-5 boots vs 12), still
   adds 1-2 minutes vs 25 s. The 12 SPI flows fit cleanly
   into 12 methods on ONE class; subclassing buys no
   readability win.

### For the bean-creation cycle fix (D2)

A. **`@Lazy` on the `IndexerMetrics` ctor parameter of
   `QuickwitHttpAdmin` AND `QuickwitHttpSearch`.** -- accepted.
   Spring hands the adapter a JDK proxy that resolves the real
   `IndexerMetrics` bean on first method call, by which time
   both ends of the cycle are fully constructed. The
   proxy-vs-real-bean swap is transparent at the call site
   (the adapter calls `metrics.incIndexAdmin(...)` /
   `metrics.incSearch(...)` without caring whether the
   reference is a proxy or the real bean). Single-import +
   single-annotation change per adapter; minimal blast
   radius; no public API change to the SPI; no behaviour
   change in the metrics bootstrap loop.
B. **Convert `IndexerMetrics` from `@Component` to
   `@EventListener(ApplicationReadyEvent)` registration.** --
   rejected. The bootstrap loop runs in `@PostConstruct` so
   the `cortex.indexer.{index_admin,search}_total` families
   are exposed on the FIRST `/actuator/prometheus` scrape.
   Deferring to `ApplicationReadyEvent` would create a
   scrape-vs-event race where the first scrape after boot
   could miss the family entirely. Breaks the LD112 contract.
C. **Inject `ObjectProvider<IndexerMetrics>` into the
   adapters and call `objectProvider.getObject()` on each
   metrics tick.** -- rejected. `ObjectProvider` has the same
   late-resolution semantics as `@Lazy` but adds API surface
   to the adapter (the `objectProvider.getObject()` call is
   explicit at every tick site) and pays a lookup cost per
   tick instead of per startup. `@Lazy` is the more idiomatic
   Spring solution for this exact shape (cycle-break on
   collaborator injection).
D. **Refactor `IndexerMetrics` to NOT inject the
   `List<QuickwitIndexAdmin>` / `List<LogSearchClient>`
   ctor params; instead use `BeanFactory.getBeansOfType` in
   the bootstrap loop.** -- rejected. The
   `@PostConstruct`-time `getBeansOfType` call would still
   trigger the adapter bean construction (Spring needs the
   bean instances to return them from `getBeansOfType`),
   recreating the cycle. The cycle is structural -- the
   adapter must know `IndexerMetrics` to tick on outcomes,
   and the metrics bootstrap must know the adapter list to
   bootstrap per-backend series -- and the only correct fix
   is the `@Lazy` proxy break.

### For the smoke / Postman shape (D8)

X. **Mirror the boot smoke 1:1 in Postman**: admin actuator
   probes + `/health/quickwit` binder-gate proof + Prometheus
   exposition with HELP+TYPE + Metrics-Baseline +
   Quickwit-Admin (re-check + direct Quickwit reachability) +
   Quickwit-Search (404 permanent wire contract probe) +
   Metrics-After (non-decreasing). 5 folders / 13 requests /
   55 assertions. `pm.execution.skipRequest()` when
   `quickwit_base_url` is empty (staging + prod env files
   leave it blank so the same collection runs offline against
   admin-only surfaces in those tiers). -- accepted.
Y. **Postman as a separate contract from the smoke.** --
   rejected. Same LD104 closer-pattern rationale as ADR-0037
   option B: two contracts means two surfaces to keep in
   sync.
Z. **Newman invokes the inbound SPI through a new REST
   controller exposed by log-indexer-service.** -- rejected.
   P7.1a is a closer, not a feature phase; introducing a new
   inbound REST controller (e.g. `POST /admin/v1/indexes`)
   would be a public API surface change that needs its own
   ADR + scaffold-phase + integration design. The cross-
   phase Failsafe IT (`QuickwitCrossPhaseIT`) is the source
   of truth for autowired SPI behaviour; Newman covers the
   HTTP-observable surfaces that DO exist (actuator +
   `/health/quickwit` + Prometheus exposition + direct
   Quickwit probes). The future P8 read-path service is the
   right place to introduce a `LogSearchClient`-fronting
   REST controller.

## Decision outcome

Chosen options (combined): **option 1 (singleton WireMock +
ONE `@SpringBootTest` class with 12 tests) + option A
(`@Lazy` on the `IndexerMetrics` ctor param of both adapters)
+ option X (Postman mirrors the smoke 1:1).**

### Implementation shape

- New cross-phase IT
  `log-indexer-service/src/test/java/io/cortex/indexer/closer/QuickwitCrossPhaseIT.java`
  (~500 lines):
  - `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"cortex.indexer.admin.backend=quickwit",
    "cortex.indexer.search.backend=quickwit",
    "cortex.indexer.quickwit.request-timeout=30s",
    "cortex.indexer.quickwit.doc-mapping-version=v1",
    "eureka.client.enabled=false", ...})`
  - `@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)`
    + `private final` fields + package-private constructor for
    `QuickwitIndexAdmin admin`, `LogSearchClient search`,
    `MeterRegistry registry` (mirrors the
    `JiraCrossPhaseIT` / `PagerDutyCrossPhaseIT` /
    `SlackCrossPhaseIT` pattern from log-remediation-service;
    Checkstyle Rule 14.1 forbids field `@Autowired`).
  - `private static final WireMockServer WIRE_MOCK = new
    WireMockServer(WireMockConfiguration.options().
    dynamicPort());` started in a static block.
  - `@DynamicPropertySource` lambda sets
    `cortex.indexer.quickwit.base-url` to `WIRE_MOCK::baseUrl`.
  - `@BeforeEach resetWireMock()` -> `WIRE_MOCK.resetAll()`.
  - 12 test methods (binder gates / P7.1 / P7.2 / P7.3 /
    P7.4) -- see D6.
  - Helper overloads:
    `adminCounterValue(outcome)` defaults tenant to
    `TENANT_ID`; `adminCounterValue(outcome, tenantId)`
    accepts the override; `searchCounterValue(outcome)`
    defaults tenant to `TENANT_ID`;
    `searchCounterValueForTenant(outcome, tenantId)` accepts
    the override. The override variants exist because
    `dropIndex(String)` SPI has no `IndexSpec` param, so
    the adapter ticks with `tenantId=null` which
    `IndexerMetrics.coerce()` maps to
    `IndexerMetrics.UNKNOWN`; the search tenant-mismatch
    test offending tenant id is `other-tenant` (per the
    request's `tenantId`).

- Production fix
  `log-indexer-service/src/main/java/io/cortex/indexer/admin/quickwit/QuickwitHttpAdmin.java`:
  - Added `import org.springframework.context.annotation.Lazy;`.
  - Annotated the `IndexerMetrics metrics` ctor parameter with
    `@Lazy` on the `@Autowired` ctor signature. Multi-paragraph
    Javadoc cites ADR-0043 D2 / LD131, explains the cycle
    `IndexerMetrics -> List<QuickwitIndexAdmin> ->
    QuickwitHttpAdmin -> IndexerMetrics`, and notes that the
    cycle only fires under Spring with both binder gates
    flipped to `quickwit` (per-phase `*WireMockIT` classes
    bypass Spring with `new`).

- Production fix
  `log-indexer-service/src/main/java/io/cortex/indexer/search/quickwit/QuickwitHttpSearch.java`:
  - Same `@Lazy` treatment on the `IndexerMetrics metrics`
    ctor parameter + same Javadoc pattern + same import.
    The search adapter has the symmetric cycle
    `IndexerMetrics -> List<LogSearchClient> ->
    QuickwitHttpSearch -> IndexerMetrics`.

- New PowerShell smoke
  `scripts/smoke-p7-1a.ps1` (~280 lines, per LD113 + LD114
  + LD115 boot-script discipline):
  - `Start-Transcript` to `scripts/logs/p7-1a/smoke-<ts>.log`.
  - Params `-SkipInfra`, `-KeepInfra`, `-SkipEureka`.
  - `docker compose -f infra/local/docker-compose.smoke.yml
    up -d quickwit` then `Wait-Url $quickwit/health/livez 90s`
    + `Wait-Url $quickwit/api/v1/version 30s`.
  - Optional Eureka boot.
  - Boots log-indexer-service with env bag
    `CORTEX_INDEXER_BACKEND=quickwit`,
    `CORTEX_INDEXER_SEARCH_BACKEND=quickwit`,
    `CORTEX_QUICKWIT_BASE_URL=http://localhost:7280`,
    `CORTEX_QUICKWIT_REQUEST_TIMEOUT=10s`,
    `CORTEX_QUICKWIT_DOC_MAPPING_VERSION=v1`,
    `EUREKA_DEFAULT_ZONE=http://localhost:8761/eureka/`.
  - Asserts `/actuator/health/quickwit` `status=UP` +
    `details.backend=quickwit`.
  - Asserts `/actuator/prometheus` has both counter families
    with `# HELP` + `# TYPE counter` + at least one
    `backend="quickwit"` series for each.
  - Tear down via `pidFile` + `Register-EngineEvent`
    `PowerShell.Exiting` exit trap so the JVM cannot leak
    on Ctrl-C (LD-windows-stale-java-process).

- New `infra/local/docker-compose.smoke.yml` service:
  `quickwit:` `image: quickwit/quickwit:0.8.1`,
  `container_name: cortex-smoke-quickwit`, `ports:
  ["7280:7280"]`, `command: ["run"]`, `healthcheck:
  ["CMD","curl","-fsS","http://localhost:7280/health/livez"]`
  with 10s interval / 18 retries / 30s start-period,
  `restart: unless-stopped`.

- New `postman/log-indexer.postman_collection.json`
  (5 folders / 13 requests / 55 assertions):
  - Admin (actuator): health / liveness / readiness /
    `health/quickwit` (binder-gate proof) / info / metrics /
    prometheus (7 requests).
  - Metrics-Baseline: snapshot per-family sums into env vars
    `index_admin_baseline_quickwit` +
    `search_baseline_quickwit`.
  - Quickwit-Admin: `/health/quickwit` re-check + direct
    Quickwit `/health/livez` + `/api/v1/indexes` (skipped
    when `quickwit_base_url` is empty so the collection
    runs against admin-only surfaces in staging + prod).
  - Quickwit-Search: 404-permanent wire contract probe at
    `/api/v1/cortex-no-such-index-v1/search` (proves the
    `QuickwitHttpSearch` adapter's `4xx:404 -> permanent`
    classification per ADR-0042 D5; skipped when
    `quickwit_base_url` is empty).
  - Metrics-After: asserts both counter families still
    exposed with HELP+TYPE + per-family sums monotonically
    non-decreasing vs the baseline (NOT a strict delta --
    Postman has no inbound SPI endpoint to invoke from
    log-indexer-service in P7.1a; the
    `QuickwitCrossPhaseIT` is the source of truth for
    counter deltas).
  - Pre-request: upserts `X-Request-Id` + `X-Correlation-ID`
    + `X-Tenant-Id`.
  - Top-level test: `responseTime < 5000ms`.
  - 3 env files: `local` (`base_url=http://localhost:8097`
    + `quickwit_base_url=http://localhost:7280` +
    `tenant_id=tenant-IT`), `staging` (blank quickwit_base_url),
    `prod` (blank quickwit_base_url).

- `docs/adr/INDEX.md` -- count bump 42 -> 43 + new ADR-0043
  row under "Indexer pipeline (P7)" + `Last refreshed`
  bumped to `2026-06-08 (P7.1a cross-phase closer + @Lazy
  bean-cycle fix, PR for #109)`.

- `CHANGELOG.md` -- new `[Unreleased] > ### Added` P7.1a
  entry inserted ABOVE the existing P7.4 `### Added` entry.
  Additionally a `[Unreleased] > ### Fixed` entry for the
  `@Lazy` bean-cycle break with a back-pointer to ADR-0043
  D2 / LD131.

- `log-indexer-service/README.md` -- banner flipped
  `P7.0+P7.1+P7.2+P7.3+P7.4 SHIPPED` ->
  `P7.0+P7.1+P7.2+P7.3+P7.4+P7.1a SHIPPED` with full closer
  scope summary; new section 4z "Cross-phase closer (P7.1a,
  ADR-0043)" describing the 5-leg gate + cross-phase IT
  contract + `@Lazy` fix + smoke pointer + Postman pointer.

- `memory.md` -- one new LD captured by this closer:
  - **LD131** -- `BeanCurrentlyInCreationException`
    `IndexerMetrics <-> QuickwitHttpAdmin /
    QuickwitHttpSearch`. Root cause: `IndexerMetrics`
    bootstrap loop injects `List<QuickwitIndexAdmin>` +
    `List<LogSearchClient>` to pre-register per-backend
    outcome series in `@PostConstruct` (LD106 + LD112);
    each adapter injects `IndexerMetrics` to tick on
    outcomes. With both binder gates flipped to
    `quickwit`, Spring closes the cycle and fails
    construction. Fix: `@Lazy` on the metrics ctor param
    of both adapters; Spring substitutes a JDK proxy that
    resolves the real bean on first method call. Per-phase
    `*WireMockIT` classes CANNOT surface this because they
    construct adapters with `new` (no Spring). This is
    the canonical pattern for any future module where
    `<X>Metrics`-style bootstrap loop injects
    `List<SomeSpi>` AND the SPI adapters inject the same
    metrics bean. Cross-phase closers (`@SpringBootTest`)
    MUST be added wherever this shape exists --
    LD104 closer pattern is the only structural defence
    against this defect class.

### Tracking artifacts

- New issue **#109** OPEN (created P7.1a kickoff).
- New branch `feat/109-p7-1a-closer` off `14b8d45` (P7.4
  main HEAD post-merge).
- `docs/adr/INDEX.md` row + count bump 42 -> 43 + refreshed-
  on date.
- `CHANGELOG.md` entry under `[Unreleased] > ### Added`
  (P7.1a) + `[Unreleased] > ### Fixed` (`@Lazy` bean-cycle
  break).

### Positive consequences

- Cross-phase regression closes the P7 ring: every PR going
  forward runs the full SPI surface (P7.0..P7.4) through
  Spring autowiring against WireMock, not just the per-phase
  adapter-with-`new` slice. Any future regression in the
  `IndexerMetrics` bootstrap loop, the `QuickwitHttpConfig`
  bean wiring, the `@ConditionalOnProperty` binder gates, or
  the `RestAdminTemplate` outcome classification will trip
  at least one of the 12 tests at PR time.
- LD131 captured + the `@Lazy` fix shipped in the SAME PR
  means future modules (P8 read-path service, future
  Quickwit-write-side fan-out from log-processor-service if
  it ever adopts a similar `Metrics` bootstrap loop)
  inherit the cycle-break discipline by precedent + LD
  reference + ADR-0043 D2 worked example.
- Postman collection lets operators replay the smoke
  contract offline without booting PowerShell + Docker;
  staging + prod env files exercise admin-only surfaces
  (skipped Quickwit-direct calls).
- Failsafe runtime budget held: `Tests run: 41` total IT
  (29 prior P7.0..P7.4 + 12 new closer) in ~5 min wall
  clock vs ~3:30 prior -- ~7-8 s/test marginal cost
  amortised across one context boot, well under the 25-40
  s/test if each subclass booted its own context.

### Negative consequences and trade-offs

- The singleton WireMock pattern across 12 tests means a
  test isolation failure in one test can leak state into
  the next (e.g. an un-reset stub could affect the next
  test's outcome). Mitigated by `@BeforeEach
  WIRE_MOCK.resetAll()`; if a future flake surfaces, add
  per-test `WIRE_MOCK.verify()` calls to catch state leaks
  at the assertion boundary.
- The `@Lazy` proxy adds one level of indirection on every
  metrics tick call (the proxy resolves to the real
  `IndexerMetrics` bean on first call, then caches it).
  Negligible perf cost (single AtomicReference read per
  tick) and the alternative (no fix) is a production
  startup crash, so the trade-off is strictly positive.
- The cross-phase IT does not run against a real Quickwit
  container -- that responsibility lives in the boot smoke
  (Leg B). A future regression in Quickwit's wire shape
  (e.g. `0.9.x` changes the `/api/v1/{id}/search` body
  schema) would slip past the IT and be caught only by
  the smoke. Acceptable trade-off: WireMock IT runs in
  ~5 min per `mvn verify` cycle; Quickwit-in-Testcontainers
  IT would push that to 15-20 min and run on every PR,
  which would dominate CI cost. The smoke runs against
  the real container daily/per-release; that's the right
  gate for Quickwit-version regression detection.
- The Postman collection's `pm.execution.skipRequest()`
  gate on `quickwit_base_url` means staging + prod env runs
  cover fewer requests than local -- this is intentional
  (no Quickwit-direct probes in those tiers) but operators
  must read the README to know which folders are gated off.

## Links / references

- Builds on: ADR-0038 (`QuickwitIndexAdmin` SPI + P7.0
  scaffold), ADR-0039 (`QuickwitHttpAdmin` HTTP client),
  ADR-0040 (`applyRetention` via Delete API), ADR-0041
  (per-tenant cardinality budgets), ADR-0042 (Quickwit
  search proxy via `LogSearchClient` SPI), ADR-0037
  (closer-pattern precedent in log-remediation-service).
- Captures: LD131 (`IndexerMetrics` bean-creation cycle +
  `@Lazy` fix as canonical pattern for any future
  metrics-bootstrap-loop-injects-SPI-list shape).
- Reinforces: LD104 (closer-pattern: B/C/D/E shipped once
  for all SPI methods), LD106 + LD112 (bootstrap loop
  pre-registers per-backend outcome series in
  `@PostConstruct`), LD42 + LD121 (HTTP/1.1 + dual-timeout
  pin), LD123 (neutral test credentials), LD113 + LD114 +
  LD115 (boot script discipline), LD89 + LD90 (truthful
  merge + issue-close evidence), Checkstyle Rule 14.1
  (`@Autowired` on ctor signature; `@TestConstructor` for
  IT classes).
- Closes the P7 epic together with: PR #99 (P7.0), #101
  (P7.1), #104 (P7.2), #106 (P7.3), #108 (P7.4).
- Tracking issue: #109
  (`feat/109-p7-1a-closer`).

## How to validate

```bash
# Leg A
./mvnw verify -pl log-indexer-service -am
# Surefire 106 unit tests PASS, Failsafe Tests run: 41
# (29 prior P7.0..P7.4 + 12 new closer ITs all PASS),
# 0 Checkstyle, 0 SpotBugs, JaCoCo BUNDLE 0.80/0.80 met,
# BUILD SUCCESS.

# Leg B
./scripts/smoke-p7-1a.ps1
# log-indexer-service boots on :8097 against a real
# Quickwit 0.8.1 container on :7280;
# /actuator/health/quickwit returns UP +
# details.backend=quickwit; /actuator/prometheus exposes
# both counter families with HELP+TYPE +
# backend="quickwit" series; transcript under
# scripts/logs/p7-1a/.

# Leg C
npx newman run postman/log-indexer.postman_collection.json `
  -e postman/log-indexer.postman_environment_local.json `
  --reporters cli --bail
# All admin + metrics + Quickwit-Admin +
# Quickwit-Search + Metrics-After assertions PASS
# against a live local stack (service on :8097 +
# Quickwit on :7280).

# Leg D
# (subset of Leg A) -- ./mvnw verify -Dit.test=
# 'closer.QuickwitCrossPhaseIT' -pl log-indexer-service

# Leg E
# Manual: ADR-0043 + INDEX + CHANGELOG + README banner
# + 4-file flip + LD131 reviewed in the PR diff.
```
