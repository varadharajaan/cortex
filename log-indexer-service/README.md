# log-indexer-service

**Status: P7.0 SHIPPED** -- P7.0 scaffold (this module): Spring
Boot context loads with Eureka + actuator + Prometheus exposition,
`QuickwitIndexAdmin` SPI is in place with a default
`NoopQuickwitIndexAdmin` (gated
`cortex.indexer.admin.backend=noop`, `matchIfMissing=true`) so the
service boots green with no Quickwit dependency, one Micrometer
counter family (`cortex.indexer.index_admin_total{backend, outcome,
tenant_id}`) is bootstrap-registered, and `QuickwitHealthIndicator`
surfaces on `/actuator/health/quickwit`. ADR-0038 documents the
ownership boundary against `log-processor-service` P5.3 (which owns
the writer-side `QuickwitSink`). P7.1..P7.4 follow with real HTTP
admin + retention + cardinality budgets + search proxy; P7.1a
closer ships the cross-phase IT.

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
| Metrics                    | Micrometer Prometheus with Part 17 allowlist (`backend`, `outcome`, `tenant_id`)      |
| Health / probes            | Spring Boot Actuator (`health,info,metrics,prometheus,beans`)                      |
| Persistence                | None                                                                 |
| Build                      | Maven 3.9.9 wrapper, JaCoCo BUNDLE 0.80 line + 0.80 branch                       |
| Integration tests          | None at P7.0 (real Quickwit HTTP arrives in P7.1+)                           |

## 4. Design decisions (ADR pointers)

- **ADR-0038** -- `QuickwitIndexAdmin` SPI + per-backend
  selection contract. Single backend bean per profile selected
  by `cortex.indexer.admin.backend` + `@ConditionalOnProperty`;
  default `NoopQuickwitIndexAdmin` gated `matchIfMissing=true`
  so the scaffold boots green. Ownership boundary documented
  against P5.3 / ADR-0030 (writer side).
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

## 5. Package layout

```
io.cortex.indexer
    CortexIndexerApplication        - @SpringBootApplication entrypoint
    admin/
        QuickwitIndexAdmin          - SPI (ensureIndex, dropIndex, backendId)
        IndexAdminResult            - immutable verdict (backend, outcome, reason)
        IndexSpec                   - immutable input record (tenantId, indexId, docMappingVersion)
        NoopQuickwitIndexAdmin      - default impl (gated noop, matchIfMissing=true)
    metrics/
        IndexerMetrics              - bootstrap-registered counter family
    health/
        QuickwitHealthIndicator     - /actuator/health/quickwit
```

ArchUnit layered-architecture contract (see
`ArchitectureTest.java`):

- **App** (`io.cortex.indexer`) -- bootstrap layer.
- **Admin** (`io.cortex.indexer.admin..`) -- SPI seam; reached
  by App + Metrics + Health.
- **Metrics** (`io.cortex.indexer.metrics..`) -- reached by App.
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

| Property                                  | Default                              | Purpose                                                                       |
|-------------------------------------------|--------------------------------------|-------------------------------------------------------------------------------|
| `server.port`                             | `8097`                               | LD92                                                                          |
| `eureka.client.service-url.defaultZone`   | `http://localhost:8761/eureka/`      | Local Eureka                                                                  |
| `cortex.indexer.admin.backend`            | `noop`                               | `QuickwitIndexAdmin` binder gate (`noop` in P7.0; `quickwit` in P7.1+)        |
| `cortex.indexer.quickwit.base-url`        | `http://localhost:7280`              | Placeholder for the P7.1 HTTP admin client; unused by the P7.0 noop backend   |

Environment-variable overrides (all profiles):

```bash
EUREKA_DEFAULT_ZONE=http://eureka:8761/eureka/
CORTEX_INDEXER_BACKEND=noop          # or "quickwit" in P7.1+
CORTEX_QUICKWIT_BASE_URL=http://quickwit:7280
```

## 8. Observability

**Metrics** -- one counter family at P7.0:

| Metric                                  | Tags                            | Description                                                                                    |
|-----------------------------------------|---------------------------------|------------------------------------------------------------------------------------------------|
| `cortex.indexer.index_admin_total`      | `backend, outcome, tenant_id`   | Index admin calls handled by the active `QuickwitIndexAdmin` per backend per outcome per tenant |

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

- **P7.0** -- scaffold (this commit; #98).
- **P7.1** -- real `QuickwitHttpIndexAdmin` HTTP client against
  `/api/v1/indexes` (create / get / drop) with LD42 + LD121
  dual-timeout `RestClient`.
- **P7.2** -- retention policy enforcement (scheduled sweeper
  drops splits older than N days per tenant).
- **P7.3** -- per-tenant cardinality budgets (reject
  `ensureIndex` when over budget).
- **P7.4** -- search proxy + tenant-scoped query routing
  against the Quickwit search API.
- **P7.1a** -- cross-phase closer (Failsafe IT singleton
  Testcontainers Quickwit + Postman + smoke per LD104).
