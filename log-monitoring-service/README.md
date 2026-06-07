# log-monitoring-service

**Status: P8.0 SHIPPED** -- scaffold carves the
`ServiceHealthProbe` SPI + `NoopServiceHealthProbe` default impl
(binder-gated `cortex.monitoring.probe.backend=noop`,
`matchIfMissing=true`) + bootstrap-registered
`cortex.monitoring.probe_total` Micrometer counter family +
`MonitoringHealthIndicator` (bound to
`/actuator/health/monitoring`). Zero outbound HTTP at P8.0; real
Eureka-discovery REST probe lands in P8.1 behind
`cortex.monitoring.probe.backend=eureka-actuator`. SLO budget
engine + alert rules land in P8.2; Grafana dashboards stay
deferred to P17.

CORTEX log-monitoring-service is the **operator-facing leg of the
control plane**. There is no inbound REST contract for the data
path in P8.0; the service has only the actuator surface
(`:8098`). All real work lands in P8.1+ behind
`cortex.monitoring.probe.backend=eureka-actuator`.

## 1. Overview

`log-monitoring-service` is the **sole owner of cross-service
health aggregation** per ADR-0044. Per-service `/actuator/health`
endpoints stay; this module **subscribes** to those endpoints via
Eureka service discovery, classifies the per-service verdict into
the bounded {healthy, degraded, unhealthy, unreachable,
transient_failure, permanent_failure} outcome set, and exports
the aggregate as a single counter family the Grafana dashboards
+ Alertmanager rules subscribe to. P8 carves out four
sub-phases:

1. **Probe SPI + noop default** -- `ServiceHealthProbe.probe(
   ProbeRequest)`, surfaced as a metric counter only at P8.0
   (no REST yet).
2. **Eureka-actuator REST adapter** -- `EurekaActuatorHealthProbe`
   gated `cortex.monitoring.probe.backend=eureka-actuator`,
   walks the Eureka registry every N seconds and probes each
   instance's `/actuator/health` over the LD42 HTTP/1.1 pin +
   LD121 dual connect+read timeout `RestClient` (P8.1).
3. **SLO budget engine** -- consumes the
   `cortex.monitoring.probe_total` counter family + per-service
   SLO targets to publish
   `cortex_monitoring_slo_budget_remaining{service_id}` +
   `cortex_monitoring_slo_burn_rate{service_id, window}` gauges
   (P8.2). Alert rules under `infra/local/alerts/` get
   bootstrapped in the same phase.
4. **Cross-phase closer** -- Failsafe IT + smoke + Postman per
   LD104 (P8.1a).

P8.0 (this commit) is the SCAFFOLD only: SPI + DTOs + noop
default impl + metric counter + health indicator + ArchUnit
layered contract + context-loads smoke. Zero outbound HTTP.
Zero Testcontainers. Mirror of the P3.0 / P6.0 / P7.0
scaffold-phase pattern.

## 2. Architecture (one screen)

```
                    +-----------------------------+
   /actuator/health |  Spring Boot Actuator       |
   /actuator/info   |  (5 endpoints exposed:      |
   /actuator/       |   health, info, metrics,    |
     prometheus     |   prometheus, beans)        |
   /actuator/beans  +-----------------------------+
                                |
                                v
                    +-----------------------------+
                    |  MonitoringHealthIndicator  |
                    |  (bound to                  |
                    |   /actuator/health/         |
                    |     monitoring;             |
                    |   UP for the noop backend;  |
                    |   P8.1+ aggregates the      |
                    |   latest per-service        |
                    |   HealthSnapshot and        |
                    |   flips DOWN on any         |
                    |   unhealthy/unreachable     |
                    |   downstream)               |
                    +-----------------------------+
                                ^
                                |
                                |   reads backendId()
                                |
                    +-----------------------------+
                    |  ServiceHealthProbe (SPI)   |
                    |   - default:                |
                    |     NoopServiceHealthProbe  |
                    |     (returns                |
                    |      HealthSnapshot.noop    |
                    |      for every call;        |
                    |      zero outbound HTTP)    |
                    |   - P8.1+:                  |
                    |     EurekaActuatorHealth-   |
                    |       Probe                 |
                    |   gated by                  |
                    |   cortex.monitoring.probe   |
                    |     .backend=<one of above> |
                    +-----------------------------+
                                ^
                                |
                                | List<ServiceHealthProbe>
                                | injection (OCP bootstrap loop)
                                |
                    +-----------------------------+
                    |  MonitoringMetrics.         |
                    |    incProbe(backend,        |
                    |             outcome,        |
                    |             serviceId)      |
                    |  -> cortex.monitoring.      |
                    |       probe_total           |
                    |       {backend, outcome,    |
                    |        service_id}          |
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
| Service registry           | Spring Cloud Netflix Eureka client (`lb://`)                         |
| Probe SPI                  | `ServiceHealthProbe` + `HealthSnapshot` + `ProbeRequest` (ADR-0044)  |
| Metrics                    | Micrometer Prometheus with Part 17 allowlist (`backend`, `outcome`, `service_id`) |
| Health / probes            | Spring Boot Actuator (`health,info,metrics,prometheus,beans`)        |
| Persistence                | None                                                                 |
| Build                      | Maven 3.9.9 wrapper, JaCoCo BUNDLE 0.80 line + 0.80 branch           |
| Integration tests          | None at P8.0 (deferred to P8.1+; WireMock IT lands with the first real probe adapter) |

## 4. Design decisions (ADR pointers)

- **ADR-0044** -- `ServiceHealthProbe` SPI + per-backend
  selection contract. Single probe backend bean per profile
  selected by `cortex.monitoring.probe.backend` +
  `@ConditionalOnProperty`; default `NoopServiceHealthProbe`
  gated `matchIfMissing=true` so the scaffold boots green.
  Ownership boundary: per-service `/actuator/health`
  endpoints remain owned by their respective services; this
  module is a consumer + aggregator, NOT a replacement.
- **LD3 (extended)** -- the monitoring service is the
  cross-service health-aggregation owner. Per-service
  actuator endpoints stay; this module subscribes.
- **LD92** -- port `:8098` (next free after `:8090` gateway,
  `:8092` ingest, `:8093` echo, `:8094` WireMock, `:8095`
  processor, `:8096` remediation, `:8097` indexer).
- **LD100** -- `src/test/resources/application.yml` fully
  shadows the main yml under Spring Boot test, so the test
  resources file declares the full
  `cortex.monitoring.probe.backend` block; it does NOT
  inherit from main.
- **LD104** -- closer pattern -- scaffold phase ships Leg A
  (`mvn verify` BUILD SUCCESS) only; Legs B..E (Failsafe IT,
  smoke, Postman) land in P8.1a closer.
- **LD106 + LD112 + LD125** -- the
  `cortex.monitoring.probe_total` counter family is
  bootstrap-registered at `@PostConstruct` with all-`unknown`
  tag values so the `/actuator/prometheus` scrape exposes the
  family on the very first scrape, before any probe call
  ticks. OCP-flipped bootstrap loop iterates over the
  injected `List<ServiceHealthProbe>` so adding a new probe
  backend requires zero edits in `MonitoringMetrics`.
- **LD129** -- explicit `<maven-failsafe-plugin>` declaration
  required in this child pom (parent pluginManagement alone
  does not bind it to the build lifecycle).
- **Part 17 allowlist** -- the three permitted Prometheus
  tags on the monitoring counter are `backend`, `outcome`,
  `service_id`. The `HealthSnapshot.BACKEND_*` +
  `HealthSnapshot.OUTCOME_*` constants bound the first two
  axes by construction.

## 5. Package layout

```
io.cortex.monitoring
    CortexMonitoringApplication     - @SpringBootApplication entrypoint
    probe/
        ServiceHealthProbe          - SPI (probe, backendId)
        HealthSnapshot              - immutable verdict (backend, outcome, reason, detail)
        ProbeRequest                - immutable input record (serviceId, instanceId)
        NoopServiceHealthProbe      - default impl (gated noop, matchIfMissing=true)
    metrics/
        MonitoringMetrics           - bootstrap-registered counter family (probe_total)
    health/
        MonitoringHealthIndicator   - /actuator/health/monitoring
```

ArchUnit layered-architecture contract (see
`ArchitectureTest.java`):

- **App** (`io.cortex.monitoring`) -- bootstrap layer.
- **Probe** (`io.cortex.monitoring.probe..`) -- SPI seam;
  reached by App + Metrics + Health.
- **Metrics** (`io.cortex.monitoring.metrics..`) -- reached
  by App (P8.1 will add Probe -> Metrics edge once the real
  adapter ticks the counter).
- **Health** (`io.cortex.monitoring.health..`) -- reached by
  App.

## 6. Running locally

```powershell
# From repo root:
.\mvnw.cmd -pl log-monitoring-service -am verify
java -jar log-monitoring-service\target\log-monitoring-service-0.1.0-SNAPSHOT.jar
```

The service registers with the local-dev Eureka registry at
`http://localhost:8761/eureka/` (start it first via the
`scripts/start-eureka.ps1` helper if not already running).

Actuator surface:

```
GET http://localhost:8098/actuator/health
GET http://localhost:8098/actuator/health/monitoring
GET http://localhost:8098/actuator/info
GET http://localhost:8098/actuator/metrics
GET http://localhost:8098/actuator/prometheus
GET http://localhost:8098/actuator/beans
```

## 7. Configuration

| Property                                       | Default                              | Purpose                                                                       |
|------------------------------------------------|--------------------------------------|-------------------------------------------------------------------------------|
| `server.port`                                  | `8098`                               | LD92                                                                          |
| `eureka.client.service-url.defaultZone`        | `http://localhost:8761/eureka/`      | Local Eureka                                                                  |
| `cortex.monitoring.probe.backend`              | `noop`                               | `ServiceHealthProbe` binder gate (`noop` default; set to `eureka-actuator` to activate the P8.1 HTTP probe) |

Environment-variable overrides (all profiles):

```bash
EUREKA_DEFAULT_ZONE=http://eureka:8761/eureka/
CORTEX_MONITORING_PROBE_BACKEND=noop   # or "eureka-actuator" to activate the P8.1 HTTP probe
```

## 8. Observability

**Metrics** -- one counter family at P8.0; incremented by the
active probe per call:

| Metric                                  | Tags                            | Description                                                                                    |
|-----------------------------------------|---------------------------------|------------------------------------------------------------------------------------------------|
| `cortex.monitoring.probe_total`         | `backend, outcome, service_id`  | Health probes handled by the active `ServiceHealthProbe` per backend per outcome per Eureka service id |

P8.2 will add SLO gauges
(`cortex_monitoring_slo_budget_remaining` +
`cortex_monitoring_slo_burn_rate`) on the same allowlist.

**Health** -- composite + per-indicator:

```
GET /actuator/health             -> aggregated UP/DOWN
GET /actuator/health/monitoring  -> {"status":"UP","details":{"backend":"noop"}}
GET /actuator/health/readiness   -> probed by K8s readiness gate
GET /actuator/health/liveness    -> probed by K8s liveness gate
```

**Structured logs** -- JSON via Logstash encoder by default
(`logback-spring.xml`), with
`customFields={"service":"log-monitoring-service"}`. The
`dev` Spring profile flips to human-readable console output.

## 9. Tests

P8.0 ships 27 tests across 7 classes:

- `ArchitectureTest` -- 1 test; ArchUnit
  App/Probe/Metrics/Health layers.
- `CortexMonitoringApplicationTests` -- 1 test; context-loads
  smoke.
- `NoopServiceHealthProbeTest` -- 2 tests; noop returns
  `HealthSnapshot.noop(...)` for the SPI call.
- `HealthSnapshotTest` -- 12 tests; covers every factory
  method + the constant surface + null/blank coercions.
- `ProbeRequestTest` -- 5 tests; canonical-constructor
  validation on the `serviceId` field + `instanceId`
  nullable.
- `MonitoringMetricsTest` -- 7 tests; bootstrap-loop
  registers the full counter family + idempotency +
  `incProbe` tag triple + null/blank coercion to `unknown`.
- `MonitoringHealthIndicatorTest` -- 1 test; UP + `backend`
  detail surfaces.

JaCoCo BUNDLE 0.80 line + 0.80 branch gate met from P8.0 day
one (no relaxed override block in the child pom).

## 10. Roadmap

- **P8.0** -- scaffold (#111, ADR-0044). SHIPPED.
- **P8.1** -- real `EurekaActuatorHealthProbe` HTTP client
  against per-service `/actuator/health` via Eureka
  discovery (LD42 + LD121 dual-timeout `RestClient`).
  DEFERRED.
- **P8.2** -- SLO budget engine
  (`cortex_monitoring_slo_budget_remaining` +
  `cortex_monitoring_slo_burn_rate` gauges + alert rules
  under `infra/local/alerts/`). DEFERRED.
- **P8.1a** -- cross-phase closer (Failsafe IT singleton
  WireMock per-instance actuator stubs + Postman + smoke
  per LD104). DEFERRED.

Grafana dashboards stay scheduled for P17, NOT P8.
