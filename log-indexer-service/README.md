# log-indexer-service

**Status: P7.0 + P7.1 + P7.2 + P7.3 + P7.4 + P7.1a SHIPPED** -- P7.0 carved the
`QuickwitIndexAdmin` SPI + `NoopQuickwitIndexAdmin` default +
bootstrap counter family + `QuickwitHealthIndicator`. P7.1 lands
the FIRST real backend impl behind the SPI: `QuickwitHttpAdmin`
(`@ConditionalOnProperty(backend="quickwit")`) talks to the real
Quickwit REST surface (`POST /api/v1/indexes`, `GET
/api/v1/indexes/<id>`, `DELETE /api/v1/indexes/<id>`) with the
P5.3 + P6.x HTTP/1.1 pin (LD42) + dual connect+read timeout
(LD121) + outcome-classification template mirroring the P6.0a
`RestDispatchTemplate` (ADR-0036). P7.2 adds the third SPI method
`applyRetention(IndexSpec, RetentionPolicy)` backed by the
Quickwit Delete API (`POST /api/v1/{indexId}/delete-tasks` with
body `{"query":"*","end_timestamp":<epoch_seconds>}`) plus a new
`RetentionPolicy(Duration ttl)` immutable value type (strict
null/zero/negative rejection) + new
`IndexAdminResult.OUTCOME_RETENTION_APPLIED` outcome bootstrapped
into the `IndexerMetrics` OCP loop (ADR-0040). P7.3 adds the
budget-aware SPI overload `ensureIndex(IndexSpec,
CardinalityBudget)` plus a new `CardinalityBudget(int maxIndexes)`
immutable value type (strict positive-int rejection). The
`QuickwitHttpAdmin` implementation runs the existing
`checkExists` probe first (idempotent re-check of an existing
index skips the gate), then on 404 fetches `GET /api/v1/indexes`
and counts entries whose `index_config.index_id` starts with
`cortex-<tenantId>-`; count at or above the ceiling returns
`permanent_failure / quickwit:budget-exceeded` (REUSES the
existing outcome with a new reason -- no new outcome constant,
Part 17 allowlist holds, `IndexerMetrics` bootstrap loop
unchanged) (ADR-0041). P7.4 lands the **read-side** SPI
`LogSearchClient` (sibling of `QuickwitIndexAdmin`) with a noop
default + `QuickwitHttpSearch` adapter forwarding to
`POST /api/v1/{indexId}/search` with body
`{"query":"...","max_hits":N}` and parsing the
`{"num_hits":N,"hits":[...]}` response. The adapter enforces a
**strict client-side tenant-routing guardrail** -- `indexId`
MUST start with `cortex-<tenantId>-` or the call returns
`permanent_failure / quickwit:tenant-mismatch` WITHOUT
contacting Quickwit, stopping cross-tenant query leaks at the
lowest possible cost. Reuses the P7.1
`quickwitAdminRestClient` bean (HTTP/1.1 pin + dual timeout)
so the wire posture matches the admin path. A new sibling
counter `cortex.indexer.search_total{backend, outcome,
tenant_id}` joins the Prometheus surface on the same Part 17
allowlist (ADR-0042). Mutually exclusive with the noop default
at the `@ConditionalOnProperty` level. The IndexerMetrics OCP
bootstrap loop picks up new admin and search backends with
zero edits. ADR-0039 + ADR-0040 + ADR-0041 + ADR-0042 document
the decision drivers + rejected alternatives. P7.1a closer
ships the cross-phase IT + smoke + Postman for all of
P7.0..P7.4. ADR-0043 documents the closer's design + the
`@Lazy` bean-creation cycle fix on `QuickwitHttpAdmin` +
`QuickwitHttpSearch` that the closer's `QuickwitCrossPhaseIT`
surfaced (the per-phase WireMock ITs structurally cannot
detect it because they instantiate adapters with `new`,
bypassing Spring).

CORTEX log-indexer-service is the **operator-facing leg of the
search tier**. There is no inbound REST contract for the data path
in P7.0; the service has only the actuator surface (`:8097`). All
real work lands in P7.1+ behind
`cortex.indexer.admin.backend=quickwit`.

## 1. Overview

`log-indexer-service` is the **sole owner of Quickwit
administration** per LD3 + ADR-0038. The writer side
(`QuickwitSink`) lives in `log-processor-service` P5.3 + ADR-0030
and is intentionally NOT referenced from this module. P7 carves
out the lifecycle surface:

1. **Create / drop index** -- `QuickwitIndexAdmin.ensureIndex(
   IndexSpec)` + `dropIndex(String)`, surfaced as REST in P7.4.
2. **Retention enforcement** -- scheduled sweeper drops splits
   older than N days per tenant (P7.2).
3. **Per-tenant cardinality budgets** -- `ensureIndex` rejects
   creation when the tenant is over its allotted index count
   (P7.3).
4. **Search proxy** -- tenant-scoped query routing against the
   Quickwit search API (P7.4).

P7.0 (this commit) is the SCAFFOLD only: SPI + DTOs + noop default
impl + metric counter + health indicator + ArchUnit layered
contract + context-loads smoke. Zero outbound HTTP. Zero
Testcontainers. Mirror of the P3.0 / P6.0 scaffold-phase pattern.

## 2. Architecture (one screen)

```
                    +-----------------------------+
   /actuator/health |  Spring Boot Actuator       |
   /actuator/info   |  (4 endpoints exposed:      |
   /actuator/       |   health, info, metrics,    |
     prometheus     |   prometheus, beans)        |
   /actuator/beans  +-----------------------------+
                                |
                                v
                    +-----------------------------+
                    |  QuickwitHealthIndicator    |
                    |  (bound to                  |
                    |   /actuator/health/quickwit;|
                    |   UP for the noop backend;  |
                    |   P7.1+ probes the real     |
                    |   Quickwit /api/v1/health)  |
                    +-----------------------------+
                                ^
                                |
                                |   reads backendId()
                                |
                    +-----------------------------+
                    |  QuickwitIndexAdmin (SPI)   |
                    |   - default:                |
                    |     NoopQuickwitIndexAdmin  |
                    |     (returns                |
                    |      IndexAdminResult.noop  |
                    |      for every call;        |
                    |      zero outbound HTTP)    |
                    |   - P7.1+:                  |
                    |     QuickwitHttpIndexAdmin  |
                    |   gated by                  |
                    |   cortex.indexer.admin      |
                    |     .backend=<one of above> |
                    +-----------------------------+
                                ^
                                |
                                | List<QuickwitIndexAdmin>
                                | injection (OCP bootstrap loop)
                                |
                    +-----------------------------+
                    |  IndexerMetrics.            |
                    |    incIndexAdmin(backend,   |
                    |                  outcome,   |
                    |                  tenantId)  |
                    |  -> cortex.indexer.         |
                    |       index_admin_total     |
                    |       {backend, outcome,    |
                    |        tenant_id}           |
                    |  Bootstrap-registered at    |
                    |  @PostConstruct so          |
                    |  /actuator/prometheus       |
                    |  exposes the family on the  |
                    |  very first scrape.         |
                    +-----------------------------+
```

## 3. Tech stack

| Layer                      | Tech                                                                 |
|----------------------------|----------------------------------------------------------------------|
| Runtime                    | Java 17 LTS (no virtual threads per ADR-0001)                        |
| Framework                  | Spring Boot 3.3.6 / Spring Cloud 2023.0.4                            |
| Service registry           | Spring Cloud Netflix Eureka client (`lb://`)                           |
| Index admin SPI            | `QuickwitIndexAdmin` + `IndexAdminResult` + `IndexSpec` (ADR-0038)                   |
| Quickwit HTTP admin client | `QuickwitHttpAdmin` + `RestAdminTemplate` + `RestClient` HTTP/1.1 pin + dual timeout (ADR-0039) |
| Metrics                    | Micrometer Prometheus with Part 17 allowlist (`backend`, `outcome`, `tenant_id`)      |
| Health / probes            | Spring Boot Actuator (`health,info,metrics,prometheus,beans`)                      |
| Persistence                | None                                                                 |
| Build                      | Maven 3.9.9 wrapper, JaCoCo BUNDLE 0.80 line + 0.80 branch                       |
| Integration tests          | WireMock 3.9.2 IT for `QuickwitHttpAdmin` (P7.1); cross-phase Testcontainers Quickwit IT in P7.1a closer |

## 4. Design decisions (ADR pointers)

- **ADR-0038** -- `QuickwitIndexAdmin` SPI + per-backend
  selection contract. Single backend bean per profile selected
  by `cortex.indexer.admin.backend` + `@ConditionalOnProperty`;
  default `NoopQuickwitIndexAdmin` gated `matchIfMissing=true`
  so the scaffold boots green. Ownership boundary documented
  against P5.3 / ADR-0030 (writer side).
- **ADR-0039** -- P7.1 real `QuickwitHttpAdmin` impl behind
  the same SPI gate, activated by
  `cortex.indexer.admin.backend=quickwit`. `ensureIndex` is
  GET-then-POST (avoids parsing Quickwit's unstable
  `IndexAlreadyExists` 400 body); `dropIndex` is
  DELETE-and-classify-404-as-success per the SPI idempotence
  contract. Composition-based `RestAdminTemplate` mirrors the
  P6.0a `RestDispatchTemplate` outcome table.
- **ADR-0040** -- P7.2 `applyRetention(IndexSpec,
  RetentionPolicy)` SPI method backed by the Quickwit Delete
  API (`POST /api/v1/{indexId}/delete-tasks` with body
  `{"query":"*","end_timestamp":<epoch_seconds>}`). New
  `RetentionPolicy(Duration ttl)` strict value type;
  `Clock`-injected dual-ctor test seam mirroring P5.4
  `AnomaliesPublisher`. The 404 status here is
  **permanent failure** (config error == missing index),
  distinct from `dropIndex`'s 404-is-success semantic.
- **ADR-0042** -- P7.4 tenant-scoped Quickwit search proxy.
  New read-side `LogSearchClient` SPI (mirror of P7.0
  `QuickwitIndexAdmin`) in `io.cortex.indexer.search`;
  `NoopLogSearchClient` default + `QuickwitHttpSearch`
  `@ConditionalOnProperty(backend="quickwit")` adapter
  posting `{"query":"...","max_hits":N}` to
  `/api/v1/{indexId}/search` and parsing
  `{"num_hits":N,"hits":[...]}`. Strict client-side
  tenant-routing guardrail (`indexId` MUST start with
  `cortex-<tenantId>-` or `permanent_failure /
  quickwit:tenant-mismatch` WITHOUT contacting Quickwit).
  New sibling counter
  `cortex.indexer.search_total{backend, outcome,
  tenant_id}` joins the Part 17 allowlist;
  `IndexerMetrics` ctor gains `List<LogSearchClient>`
  injection; `ArchitectureTest` gains the `Search` layer.
- **ADR-0043** -- P7.1a cross-phase closer. Adds
  `QuickwitCrossPhaseIT` (`@SpringBootTest` with BOTH
  binder gates flipped to `quickwit`; singleton in-process
  `WireMockServer`; 12 tests covering every P7.0..P7.4 SPI
  flow through autowired beans) + `scripts/smoke-p7-1a.ps1`
  + `postman/log-indexer.postman_collection.json` +
  `infra/local/docker-compose.smoke.yml quickwit:` service.
  Surfaced a real production startup defect:
  `BeanCurrentlyInCreationException` from the cycle
  `IndexerMetrics -> List<QuickwitIndexAdmin|LogSearchClient>
  -> QuickwitHttp{Admin,Search} -> IndexerMetrics` fires
  whenever the binder gates are flipped to `quickwit`. Fix:
  `@Lazy` on the `IndexerMetrics` ctor parameter of BOTH
  adapters (Spring substitutes a JDK proxy that resolves the
  real bean on first method call, breaking the cycle without
  changing the SPI or the bootstrap-loop semantics). The
  per-phase `*WireMockIT` classes could not detect this
  because they construct adapters with `new`, never going
  through Spring's bean factory. See section 4z + LD131.
- **ADR-0036** -- the `RestDispatchTemplate` composition
  pattern P7.1's `RestAdminTemplate` mirrors.
- **ADR-0030** -- writer side of the Quickwit fan-out lives in
  `log-processor-service` (`QuickwitSink`). This service owns
  the admin / lifecycle side only; ADR-0030 + ADR-0038 carve
  the boundary.
- **LD3** -- the indexer is the Quickwit owner. P5.3 is a
  writer, not an owner.
- **LD12 + LD14** -- compiler override pattern: child pom uses
  `<annotationProcessorPaths combine.self="override">` +
  `<compilerArgs combine.self="override">` to drop the parent's
  MapStruct annotation processor (this module has no
  `@Mapper`-annotated types).
- **LD92** -- port `:8097` (next free after `:8090` gateway,
  `:8092` ingest, `:8093` echo, `:8094` WireMock, `:8095`
  processor, `:8096` remediation).
- **LD100** -- `src/test/resources/application.yml` fully
  shadows the main yml under Spring Boot test, so the test
  resources file declares the full
  `cortex.indexer.admin.backend` block; it does NOT inherit
  from main.
- **LD104** -- closer pattern -- scaffold phase ships Leg A
  (`mvn verify` BUILD SUCCESS) only; Legs B..E (Failsafe IT,
  smoke, Postman) land in P7.1a closer.
- **LD106 + LD112** -- the
  `cortex.indexer.index_admin_total` counter family is
  bootstrap-registered at `@PostConstruct` with all-`unknown`
  tag values so the `/actuator/prometheus` scrape exposes the
  family on the very first scrape, before any admin call ticks.
  OCP-flipped bootstrap loop iterates over the injected
  `List<QuickwitIndexAdmin>` so adding a new admin backend
  requires zero edits in `IndexerMetrics`.
- **Part 17 allowlist** -- the three permitted Prometheus tags
  on the indexer counter are `backend`, `outcome`,
  `tenant_id`. The `IndexAdminResult.BACKEND_*` +
  `IndexAdminResult.OUTCOME_*` constants bound the first two
  axes by construction.

## 4z. Cross-phase closer (P7.1a, ADR-0043)

P7.1a is the cross-phase closer that retires the P7 epic.
Per the LD104 closer-pattern precedent, every P7.x scaffold /
feature phase (P7.0..P7.4) shipped **Leg A only** (`mvn
verify` BUILD SUCCESS on the per-phase code + per-phase unit
+ WireMock ITs). The cross-phase / smoke / Postman / docs
artifacts (Legs B..E) were deferred to this single closer
PR so they land ONCE for the full P7.0..P7.4 SPI surface
together.

### What ships in P7.1a

1. **Cross-phase Failsafe IT**
   `log-indexer-service/src/test/java/io/cortex/indexer/closer/QuickwitCrossPhaseIT.java`
   -- ONE `@SpringBootTest` class with BOTH binder gates
   flipped to `quickwit`
   (`cortex.indexer.admin.backend=quickwit` +
   `cortex.indexer.search.backend=quickwit`); singleton
   in-process `WireMockServer` started in a static block;
   `@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)`
   + `private final` fields + package-private constructor
   (Checkstyle Rule 14.1 forbids field `@Autowired`); 12
   test methods exercising every P7.0..P7.4 SPI flow
   through autowired Spring beans (2 binder-gate proofs +
   4 P7.1 admin tests + 1 P7.2 retention test + 2 P7.3
   budget tests + 3 P7.4 search tests). Runs inside the
   normal `mvn verify` Failsafe cycle so CI catches
   cross-phase regressions on every PR (Leg D).
2. **Full-stack PowerShell smoke** `scripts/smoke-p7-1a.ps1`
   -- boots a real Quickwit 0.8.1 docker container on
   `:7280` from `infra/local/docker-compose.smoke.yml`,
   waits for `/health/livez` to flip green, boots
   log-indexer-service on `:8097` with both binder gates
   set to `quickwit`, asserts `/actuator/health/quickwit`
   returns `status=UP` + `details.backend=quickwit`,
   asserts `/actuator/prometheus` exposes BOTH counter
   families with `# HELP` + `# TYPE counter` + at least
   one `backend="quickwit"` series for each, tears down
   via `pidFile` + `Register-EngineEvent`
   `PowerShell.Exiting` exit trap so the JVM cannot leak
   on Ctrl-C. Transcripts under `scripts/logs/p7-1a/`
   (Leg B).
3. **Postman collection**
   `postman/log-indexer.postman_collection.json` (5
   folders / 13 requests / 55 assertions) -- Admin
   (actuator: health + liveness + readiness +
   `health/quickwit` binder-gate proof + info + metrics +
   prometheus with HELP+TYPE) + Metrics-Baseline +
   Quickwit-Admin (`/health/quickwit` re-check + direct
   Quickwit `/health/livez` + `/api/v1/indexes`) +
   Quickwit-Search (404-permanent wire contract probe) +
   Metrics-After (non-decreasing counter check). Three
   env files: `local`
   (`base_url=http://localhost:8097` +
   `quickwit_base_url=http://localhost:7280` +
   `tenant_id=tenant-IT`), `staging` (blank
   `quickwit_base_url`), `prod` (blank
   `quickwit_base_url`). Mirrors the smoke 1:1 (Leg C).
4. **`@Lazy` bean-creation cycle fix on `QuickwitHttpAdmin`
   AND `QuickwitHttpSearch`** -- the closer's
   `QuickwitCrossPhaseIT` surfaced a real production
   defect on first boot:
   `BeanCurrentlyInCreationException` from the cycle
   `IndexerMetrics` (ctor injects
   `List<QuickwitIndexAdmin>` +
   `List<LogSearchClient>` to drive the LD106 + LD112
   bootstrap loop in `@PostConstruct`) ->
   `QuickwitHttpAdmin` / `QuickwitHttpSearch` (each
   injects `IndexerMetrics` to tick on outcomes) ->
   `IndexerMetrics`. The cycle only fires when both
   binder gates are flipped to `quickwit`; the per-phase
   `QuickwitHttpAdminWireMockIT` /
   `QuickwitHttpSearchWireMockIT` could not detect it
   because they construct the adapter directly with
   `new`, bypassing Spring's bean factory. Fix:
   `@Lazy` on the `IndexerMetrics metrics` ctor parameter
   of both adapters; Spring substitutes a JDK proxy that
   resolves the real bean on first method call. The
   proxy-vs-real-bean swap is transparent at the call
   site -- the adapters continue to call
   `metrics.incIndexAdmin(...)` / `metrics.incSearch(...)`
   without caring whether the reference is a proxy or
   the real bean. Multi-paragraph Javadoc added to both
   ctors cross-referencing ADR-0043 D2 + LD131. This is
   the canonical pattern for any future module where a
   `<X>Metrics`-style bootstrap loop injects
   `List<SomeSpi>` AND the SPI adapters inject the same
   metrics bean.
5. **ADR-0043** (this closer's ADR) + INDEX bump 42 -> 43
   + CHANGELOG `### Added` (P7.1a) + `### Fixed`
   (`@Lazy`) entries + this README banner flip +
   `infra/local/docker-compose.smoke.yml quickwit:`
   service entry + `postman/README.md` matrix bump
   (LD116) (Leg E docs).

### 5-leg gate

```bash
# Leg A
./mvnw verify -pl log-indexer-service -am
# Surefire 106 unit tests PASS, Failsafe Tests run: 41
# (29 prior P7.0..P7.4 + 12 new closer ITs all PASS),
# 0 Checkstyle, 0 SpotBugs, JaCoCo BUNDLE 0.80/0.80 met,
# BUILD SUCCESS.

# Leg B
./scripts/smoke-p7-1a.ps1
# Boots Quickwit on :7280 + log-indexer-service on :8097;
# asserts /actuator/health/quickwit UP + details.backend=quickwit;
# asserts /actuator/prometheus exposes both counter families.

# Leg C
npx newman run postman/log-indexer.postman_collection.json `
  -e postman/log-indexer.postman_environment_local.json `
  --reporters cli --bail
# 55 assertions across 13 requests in 5 folders, all PASS
# against the running smoke stack.

# Leg D
# (subset of Leg A) -- the cross-phase IT class:
./mvnw verify -Dit.test='closer.QuickwitCrossPhaseIT' -pl log-indexer-service

# Leg E
# Manual: ADR-0043 + INDEX + CHANGELOG + README banner +
# 4-file flip + LD131 reviewed in the PR diff.
```

## 5. Package layout

```
io.cortex.indexer
    CortexIndexerApplication        - @SpringBootApplication entrypoint
    admin/
        QuickwitIndexAdmin          - SPI (ensureIndex, dropIndex, applyRetention, backendId)
        IndexAdminResult            - immutable verdict (backend, outcome, reason)
        IndexSpec                   - immutable input record (tenantId, indexId, docMappingVersion)
        RetentionPolicy             - immutable input record (ttl); strict null/zero/negative reject (ADR-0040)
        NoopQuickwitIndexAdmin      - default impl (gated noop, matchIfMissing=true)
        quickwit/
            QuickwitProperties      - @ConfigurationProperties(prefix=cortex.indexer.quickwit)
            QuickwitHttpConfig      - @Configuration; publishes the HTTP/1.1 RestClient bean
            RestAdminTemplate       - package-private classify{Http,Transport,Unknown}
            QuickwitHttpAdmin       - @Component; real Quickwit HTTP admin impl (gated quickwit)
    search/
        LogSearchClient             - SPI (search, backendId); read-side sibling of QuickwitIndexAdmin (ADR-0042)
        SearchRequest               - immutable input record (tenantId, indexId, query, maxHits)
        SearchResult                - immutable envelope (backend, outcome, reason, numHits, hits); BACKEND/OUTCOME constants + factories
        NoopLogSearchClient         - default impl (gated noop, matchIfMissing=true)
        quickwit/
            QuickwitHttpSearch      - @Component; real Quickwit HTTP search impl (gated quickwit); tenant-prefix guardrail per ADR-0042 D3
    constants/
        IndexerHttp                 - TOO_MANY_REQUESTS, SERVER_ERROR_FLOOR, NOT_FOUND
    metrics/
        IndexerMetrics              - bootstrap-registered counter families (index_admin_total + search_total)
    health/
        QuickwitHealthIndicator     - /actuator/health/quickwit
```

ArchUnit layered-architecture contract (see
`ArchitectureTest.java`):

- **App** (`io.cortex.indexer`) -- bootstrap layer.
- **Admin** (`io.cortex.indexer.admin..`) -- SPI seam; reached
  by App + Metrics + Health + Search (Search and Admin are
  siblings -- Search reaches Admin only via the shared
  `quickwitAdminRestClient` bean published by
  `admin/quickwit/QuickwitHttpConfig`).
- **Search** (`io.cortex.indexer.search..`) -- read-side SPI
  seam; reached by App + Metrics (ADR-0042).
- **Metrics** (`io.cortex.indexer.metrics..`) -- reached by
  App + Admin + Search.
- **Health** (`io.cortex.indexer.health..`) -- reached by App.

## 6. Running locally

```powershell
# From repo root:
.\mvnw.cmd -pl log-indexer-service -am verify
java -jar log-indexer-service\target\log-indexer-service-0.1.0-SNAPSHOT.jar
```

The service registers with the local-dev Eureka registry at
`http://localhost:8761/eureka/` (start it first via the
`scripts/start-eureka.ps1` helper if not already running).

Actuator surface:

```
GET http://localhost:8097/actuator/health
GET http://localhost:8097/actuator/health/quickwit
GET http://localhost:8097/actuator/info
GET http://localhost:8097/actuator/metrics
GET http://localhost:8097/actuator/prometheus
GET http://localhost:8097/actuator/beans
```

## 7. Configuration

| Property                                       | Default                              | Purpose                                                                       |
|------------------------------------------------|--------------------------------------|-------------------------------------------------------------------------------|
| `server.port`                                  | `8097`                               | LD92                                                                          |
| `eureka.client.service-url.defaultZone`        | `http://localhost:8761/eureka/`      | Local Eureka                                                                  |
| `cortex.indexer.admin.backend`                 | `noop`                               | `QuickwitIndexAdmin` binder gate (`noop` default; set to `quickwit` to activate `QuickwitHttpAdmin`) |
| `cortex.indexer.search.backend`                | `noop`                               | `LogSearchClient` binder gate (`noop` default; set to `quickwit` to activate `QuickwitHttpSearch`; ADR-0042) |
| `cortex.indexer.quickwit.base-url`             | `http://localhost:7280`              | Quickwit cluster root URL consumed by `QuickwitHttpAdmin` when `backend=quickwit` (P7.1) |
| `cortex.indexer.quickwit.request-timeout`      | `5s`                                 | Dual connect+read timeout for the Quickwit admin `RestClient` (LD121, ADR-0039) |
| `cortex.indexer.quickwit.doc-mapping-version`  | `v1`                                 | Stable doc-mapping schema id stamped into every `index_id` (ADR-0038 D2)         |

Environment-variable overrides (all profiles):

```bash
EUREKA_DEFAULT_ZONE=http://eureka:8761/eureka/
CORTEX_INDEXER_BACKEND=noop                       # or "quickwit" to activate the P7.1 HTTP admin
CORTEX_INDEXER_SEARCH_BACKEND=noop                # or "quickwit" to activate the P7.4 HTTP search
CORTEX_QUICKWIT_BASE_URL=http://quickwit:7280
CORTEX_QUICKWIT_REQUEST_TIMEOUT=5s                # ISO-8601 duration
CORTEX_QUICKWIT_DOC_MAPPING_VERSION=v1
```

## 8. Observability

**Metrics** -- two sibling counter families at P7.4; both
incremented by the active backend per call:

| Metric                                  | Tags                            | Description                                                                                    |
|-----------------------------------------|---------------------------------|------------------------------------------------------------------------------------------------|
| `cortex.indexer.index_admin_total`      | `backend, outcome, tenant_id`   | Index admin calls handled by the active `QuickwitIndexAdmin` per backend per outcome per tenant |
| `cortex.indexer.search_total`           | `backend, outcome, tenant_id`   | Search calls handled by the active `LogSearchClient` per backend per outcome per tenant (ADR-0042) |

**Health** -- composite + per-indicator:

```
GET /actuator/health           -> aggregated UP/DOWN
GET /actuator/health/quickwit  -> {"status":"UP","details":{"backend":"noop"}}
GET /actuator/health/readiness -> probed by K8s readiness gate
GET /actuator/health/liveness  -> probed by K8s liveness gate
```

**Structured logs** -- JSON via Logstash encoder by default
(`logback-spring.xml`), with `customFields={"service":"log-indexer-service"}`.
The `dev` Spring profile flips to human-readable console output.

## 9. Tests

P7.0 ships 29 tests across 7 classes:

- `ArchitectureTest` -- 1 test; ArchUnit App/Admin/Metrics/Health layers.
- `CortexIndexerApplicationTests` -- 1 test; context-loads smoke.
- `NoopQuickwitIndexAdminTest` -- 3 tests; noop returns
  `IndexAdminResult.noop(...)` for both `ensureIndex` +
  `dropIndex`.
- `IndexAdminResultTest` -- 10 tests; covers every factory
  method + the constant surface.
- `IndexSpecTest` -- 7 tests; canonical-constructor validation
  on all three fields.
- `IndexerMetricsTest` -- 5 tests; bootstrap-loop registers the
  full counter family + `incIndexAdmin` tags the counter with
  the supplied triple + null-coercion to `unknown`.
- `QuickwitHealthIndicatorTest` -- 2 tests; UP for noop +
  `backend` detail surfaces.

JaCoCo BUNDLE 0.80 line + 0.80 branch gate met from P7.0 day
one (no relaxed override block in the child pom).

## 10. Roadmap

- **P7.0** -- scaffold (#98, ADR-0038). DONE.
- **P7.1** -- real `QuickwitHttpAdmin` HTTP client against
  `/api/v1/indexes` (create / get / drop) with LD42 + LD121
  dual-timeout `RestClient` + composition-based
  `RestAdminTemplate` (#100, ADR-0039). DONE.
- **P7.2** -- `applyRetention(IndexSpec, RetentionPolicy)`
  via Quickwit Delete API (#102, ADR-0040). DONE.
- **P7.3** -- per-tenant cardinality budgets via
  `ensureIndex(IndexSpec, CardinalityBudget)` (#105,
  ADR-0041). DONE.
- **P7.4** -- tenant-scoped Quickwit search proxy via
  `LogSearchClient` SPI + `QuickwitHttpSearch` adapter
  (#107, ADR-0042). DONE.
- **P7.1a** -- cross-phase closer (Failsafe IT singleton
  Testcontainers Quickwit + Postman + smoke per LD104).
