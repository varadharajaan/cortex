# log-monitoring-service

**Status: P8.0 + P8.1 + P8.2 + P8.2a + P8.1a + P8.2b SHIPPED** -- scaffold carves the
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
| `cortex.monitoring.probe.enabled`              | `false`                              | Master switch for the P8.2b `ScheduledProbeEvaluator` multi-target probe pump (NOT gating the probe SPI beans themselves) |
| `cortex.monitoring.probe.evaluation-interval`  | `30s`                                | Duration between `ScheduledProbeEvaluator` scheduled ticks (Spring `Duration` syntax: `30s`, `1m`, `2m30s`); routed through the `probeEvaluationIntervalMillis` Long adapter bean per LD141 |
| `cortex.monitoring.probe.targets`              | six cortex services                  | Operator-declared list of Eureka service ids the P8.2b probe pump iterates each tick; defaults to all six cortex services (`log-gateway`, `log-ingest-service`, `log-echo-service`, `log-processor-service`, `log-remediation-service`, `log-indexer-service`) |
| `cortex.monitoring.eureka.request-timeout`     | `5s`                                 | Dual connect+read timeout for the P8.1 `EurekaActuatorHealthProbe` `RestClient` (LD121) |
| `cortex.monitoring.eureka.actuator-path`       | `/actuator/health`                   | Per-instance actuator path the P8.1 probe scrapes (must start with `/`)        |
| `cortex.monitoring.slo.enabled`                | `false`                              | Master switch for the P8.2 `SloEvaluator` scheduled tick (NOT gating the engine beans themselves) |
| `cortex.monitoring.slo.backend`                | `noop`                               | `SloBudgetEngine` binder gate (`noop` default; set to `micrometer-derivation` to activate the P8.2 in-process Micrometer-derivation backend) |
| `cortex.monitoring.slo.evaluation-interval`    | `30s`                                | Duration between `SloEvaluator` scheduled ticks (Spring `Duration` syntax: `30s`, `1m`, `2m30s`)            |
| `cortex.monitoring.slo.definitions`            | six default availability SLOs        | Default list of `SloDefinition(serviceId, sloName, targetSuccessRatio, window)` rows -- one `availability` SLO (`target-success-ratio=0.99`, `window=PT1H`) per cortex service, matching `cortex.monitoring.probe.targets` (P8.2b ADR-0046 Amendment 3 2026-06-08); operator overrides via standard `@ConfigurationProperties` precedence |

Environment-variable overrides (all profiles):

```bash
EUREKA_DEFAULT_ZONE=http://eureka:8761/eureka/
CORTEX_MONITORING_PROBE_BACKEND=noop   # or "eureka-actuator" to activate the P8.1 HTTP probe
CORTEX_MONITORING_EUREKA_REQUEST_TIMEOUT=5s
CORTEX_MONITORING_EUREKA_ACTUATOR_PATH=/actuator/health
CORTEX_MONITORING_SLO_ENABLED=false           # flip to "true" to start the SloEvaluator @Scheduled tick
CORTEX_MONITORING_SLO_BACKEND=noop            # or "micrometer-derivation" to activate the P8.2 in-process derivation backend
CORTEX_MONITORING_SLO_EVALUATION_INTERVAL=30s # Spring Duration syntax: "30s", "1m", "2m30s"
```

## 8. Observability

**Metrics** -- one counter family at P8.0 (incremented by the
active probe per call) plus two SLO gauges at P8.2 (registered
on first `recordSlo` call per `(serviceId, sloName)` key):

| Metric                                            | Tags                            | Description                                                                                    |
|---------------------------------------------------|---------------------------------|------------------------------------------------------------------------------------------------|
| `cortex.monitoring.probe_total`                   | `backend, outcome, service_id`  | Health probes handled by the active `ServiceHealthProbe` per backend per outcome per Eureka service id |
| `cortex.monitoring.slo_budget_remaining`          | `service_id, slo_name`          | P8.2 -- error-budget remaining in `[-1.0, +1.0]` per `(serviceId, sloName)` (1.0 = full budget; 0.0 = exhausted; negative = over-burned). Defaulted to 1.0 for cold-start / no-data and the noop backend. |
| `cortex.monitoring.slo_burn_rate`                 | `service_id, slo_name`          | P8.2 -- burn-rate ratio per `(serviceId, sloName)` (`errorRate / errorBudget`). 0.0 = no errors; 1.0 = burning at exactly the SLO target rate; >1.0 = burning faster than allowed. Defaulted to 0.0 for cold-start / no-data and the noop backend. |

Multi-window burn-rate alert rules ship in
`infra/local/alerts/slo-burn-rate.rules.yml` -- mount that file
into Prometheus's `rule_files` glob to get
`CortexSloFastBurn` (5m+1h > 14.4x, page),
`CortexSloSlowBurn` (1h+6h > 6x, ticket), and
`CortexSloBudgetExhausted` (budget < 0, ticket) per the SRE
workbook chapter 5.

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

P8.0+P8.1+P8.2 ship 104 unit tests across 14 classes (and 9
Failsafe IT tests in 1 class):

- `ArchitectureTest` -- 1 test; ArchUnit
  App/Probe/Metrics/Health/Slo/Constants layers.
- `CortexMonitoringApplicationTests` -- 1 test; context-loads
  smoke.
- `NoopServiceHealthProbeTest` -- 2 tests; noop returns
  `HealthSnapshot.noop(...)` for the SPI call.
- `HealthSnapshotTest` -- 11 tests; every factory + the
  constant surface + null/blank coercions.
- `ProbeRequestTest` -- 5 tests; compact-ctor validation.
- `EurekaActuatorPropertiesTest` -- 6 tests; defensive
  defaults + LD42 + LD121 dual-timeout + actuator-path
  guards.
- `EurekaActuatorHealthProbeTest` -- 6 tests; hand-rolled
  `DiscoveryClient` doubles drive the SPI through every
  classification arm without WireMock.
- `RestProbeTemplateTest` -- 10 tests; full HTTP outcome
  classification matrix (200/429/5xx/4xx/timeout/transport)
  mirrored from P7.1 `RestAdminTemplate`.
- `EurekaActuatorHealthProbeWireMockIT` -- 9 Failsafe tests;
  singleton in-process WireMock proves the adapter end-to-end
  including `Fault.CONNECTION_RESET_BY_PEER` transport-fault
  injection per LD120.
- `SloDefinitionTest` -- 11 tests; compact-ctor rejection
  matrix (null/blank serviceId/sloName,
  target<=0/>=1, null/zero/negative window).
- `SloSnapshotTest` -- 13 tests; every factory shape per
  LD133 + `classifyBand` boundaries (0.501/0.5/0.11/0.1)
  + null coercion.
- `SloPropertiesTest` -- 8 tests; defensive defaults (blank
  backend / null/zero/negative interval / null definitions)
  + defensive `List.copyOf` on definitions.
- `NoopSloBudgetEngineTest` -- 3 tests; `backendId`
  constant, noop verdict, gauge defaults to all-clear.
- `MicrometerSloBudgetEngineTest` -- 10 tests; Micrometer
  derivation table (no-data unknown, all-success healthy,
  banded healthy/at_risk/exhausted, over-burn clamps -1.0,
  cross-service counter isolation, `DEGRADED` counts as
  success, `NOOP/UNKNOWN` ignored).
- `SloEvaluatorTest` -- 5 tests; hand-rolled
  `SloBudgetEngine` doubles drive the scheduled-tick loop
  (empty defs no-op, per-def-per-engine ticks, throwing
  engine doesn't stall the loop, null snapshot logged +
  skipped, snapshot reaches `MonitoringMetrics.recordSlo`).
- `MonitoringMetricsTest` -- 11 tests; bootstrap loop
  registers the probe counter family + `incProbe` tag
  triple + null/blank coercion + 4 new tests for the P8.2
  `recordSlo` gauge surface (registration / idempotency /
  null rejection / blank tag coercion to `unknown`).
- `MonitoringHealthIndicatorTest` -- 1 test; UP + `backend`
  detail surfaces.

JaCoCo BUNDLE 0.80 line + 0.80 branch gate met from P8.0 day
one (no relaxed override block in the child pom).

### 9.1 Cross-phase IT (P8.2a closer)

`io.cortex.monitoring.closer.MonitoringProbeAndSloPipelineIT`
(9 tests) boots the full Spring context with both binder
gates flipped (`cortex.monitoring.probe.backend=eureka-actuator`
+ `cortex.monitoring.slo.backend=micrometer-derivation` +
`cortex.monitoring.slo.enabled=true`) against a singleton
in-process `WireMockServer` + a `@TestConfiguration @Bean
@Primary DiscoveryClient` stub. Exercises the FULL probe ->
counter -> SLO -> gauge ring through autowired beans (ADR-0047).
Per-test isolation via `WIRE_MOCK.resetAll()` in `@BeforeEach`.

The IT requires three IT-only properties to win the `@Primary
DiscoveryClient` contest against Spring Cloud Commons defaults
(D2a / ADR-0047): `spring.autoconfigure.exclude=` listing
`EurekaClientAutoConfiguration` +
`CompositeDiscoveryClientAutoConfiguration` +
`SimpleDiscoveryClientAutoConfiguration`. The IT historically
also pinned `cortex.monitoring.slo.evaluation-interval=3600000`
(numeric millis) as a workaround for the prod bug captured as
LD137: Spring's
`ScheduledAnnotationBeanPostProcessor.fixedRateString` does NOT
accept `Duration` strings (`Long.parseLong("1h") ->
NumberFormatException`). The prod fix shipped via issue #120 /
ADR-0046 Amendment 2026-06-08 -- `SloEvaluator.@Scheduled` now
reads its cadence through the SpEL bean reference
`#{@sloEvaluationIntervalMillis}` (resolved in `SloEngineConfig`),
so the IT now uses the operator-friendly
`cortex.monitoring.slo.evaluation-interval=1h` form. The new
`SloEvaluatorScheduledBootIT` (also under
`io.cortex.monitoring.closer`) is the narrowest CI-protected
proof that the fix holds.

### 9.2 Local smoke (P8.2a closer)

`scripts/smoke-p8-2a.ps1` runs the full Leg B contract:

1. `docker compose -f infra/local/docker-compose.smoke.yml up
   -d prometheus` (Prometheus 2.55.1 on `:9090`).
2. Waits for `/-/healthy`.
3. Boots the actual service JAR on `:8098` with env bag
   (`CORTEX_MONITORING_PROBE_BACKEND=eureka-actuator,
   CORTEX_MONITORING_SLO_ENABLED=true,
   CORTEX_MONITORING_SLO_BACKEND=micrometer-derivation,
   EUREKA_CLIENT_ENABLED=false`).
4. Asserts `/actuator/health/monitoring`
   `details.backend=eureka-actuator`.
5. Scrapes `/actuator/prometheus` for the three metric
   families with `# HELP` + `# TYPE` + Part 17 tag allowlist.
6. Asserts Prometheus `/api/v1/rules` loaded the three
   CORTEX alert rules (`CortexSloFastBurn`,
   `CortexSloSlowBurn`, `CortexSloBudgetExhausted`) and
   `/api/v1/targets` job `log-monitoring-service` is UP.
7. Tears down with `docker compose ... down` (skip with
   `-KeepInfra` to keep Prometheus + JVM alive for Newman
   replay against `postman/log-monitoring.postman_
   collection.json` + `..._environment_local.json`).

### 9.3 Probe-only local smoke (P8.1a closer)

`scripts/smoke-p8-1a.ps1` (LOCAL-ONLY gitignored under
`/scripts/`) runs the probe-only Leg B contract -- no
Prometheus container, no SLO scheduler. Mirrors a
production-shaped operator deployment where only the probe
binder gate is flipped:

1. Boots the actual service JAR on `:8098` with env bag
   (`CORTEX_MONITORING_PROBE_BACKEND=eureka-actuator,
   EUREKA_CLIENT_ENABLED=false` -- no
   `CORTEX_MONITORING_SLO_*` env vars; `slo.enabled` stays
   at its `application.yml` default of false so the
   `SloEvaluator @Scheduled` bean is gated off entirely
   in this shape).
2. Asserts `/actuator/health/monitoring`
   `details.backend=eureka-actuator`.
3. Scrapes `/actuator/prometheus` for the
   `cortex_monitoring_probe_total` family with `# HELP` +
   `# TYPE` + Part 17 tag allowlist
   (`backend, outcome, service_id`).
4. Tears down the JVM (skip with `-KeepInfra` to keep the
   JVM alive for Newman replay against the existing
   `postman/log-monitoring.postman_collection.json` --
   the Prometheus + Metrics-After folders skip cleanly
   via `pm.execution.skipRequest()` when
   `prometheus_base_url` is absent).

This closer is the canonical proof that the probe surface
works in a production-shaped configuration. The smoke remains
unaffected by the issue #120 / LD137 fix
(`SloEvaluator.fixedRateString` now reads via the
`#{@sloEvaluationIntervalMillis}` SpEL bean reference per
ADR-0046 Amendment 2026-06-08) because this shape leaves the
SLO scheduler gated off entirely -- exactly the LD104
closer-separation invariant.

## 10. Roadmap

- **P8.0** -- scaffold (#111, ADR-0044). SHIPPED.
- **P8.1** -- real `EurekaActuatorHealthProbe` HTTP client
  against per-service `/actuator/health` via Eureka
  discovery (LD42 + LD121 dual-timeout `RestClient`).
  SHIPPED (#113, ADR-0045).
- **P8.2** -- SLO budget engine
  (`cortex_monitoring_slo_budget_remaining` +
  `cortex_monitoring_slo_burn_rate` gauges + alert rules
  under `infra/local/alerts/`). SHIPPED (#115, ADR-0046).
- **P8.1a** -- cross-phase closer (probe-only Failsafe IT
  `MonitoringProbeAndHealthIndicatorIT` with
  `WebEnvironment.RANDOM_PORT` + `TestRestTemplate` HTTP-
  surface proof against `/actuator/health/monitoring` +
  standalone smoke without Prometheus; production-shaped
  config that does NOT carry the LD137 numeric-millis
  workaround per LD104). SHIPPED (#122, ADR-0048).
- **P8.2a** -- cross-phase closer (real Prometheus 2.55.1
  container scrapes the gauges + loads the three alert
  rules + cross-phase Failsafe IT exercises the full
  P8.0..P8.2 ring via autowired beans + Postman covers
  `/actuator/prometheus` + Prometheus `/api/v1/rules` +
  `/api/v1/targets` per LD104). SHIPPED (#119, ADR-0047).
- **issue #120 / LD137 prod fix** --
  `SloEvaluator.@Scheduled(fixedRateString=...)` now reads its
  cadence through the SpEL bean reference
  `#{@sloEvaluationIntervalMillis}` (resolved by a new
  `@Bean Long sloEvaluationIntervalMillis(SloProperties)` in
  `SloEngineConfig`) instead of a direct
  `${cortex.monitoring.slo.evaluation-interval}` placeholder.
  This restores the operator-friendly `30s` / `1h` / `PT30S`
  Duration syntax under `slo.enabled=true`. P8.2a IT
  `MonitoringProbeAndSloPipelineIT` reverted to
  `evaluation-interval=1h`. New narrowest-possible
  `SloEvaluatorScheduledBootIT` (under
  `io.cortex.monitoring.closer`) pins the fix. SHIPPED
  (ADR-0046 Amendment 2026-06-08).
- **P8.2b** -- multi-target probe pump (new
  `ScheduledProbeEvaluator` under `io.cortex.monitoring.probe`)
  that fires one `serviceHealthProbe.probe(...)` call per
  service-id in `cortex.monitoring.probe.targets` on every
  `@Scheduled` tick, gated by
  `cortex.monitoring.probe.enabled=true` (OFF default).
  Shipped `application.yml` declares all six cortex services
  as the default `probe.targets` AND matching default
  `slo.definitions` rows (`availability` SLO,
  `target-success-ratio=0.99`, `window=PT1H`) so flipping
  the two `enabled` gates gives cortex-wide availability
  monitoring for free. Cadence routed through
  `probeEvaluationIntervalMillis` Long adapter bean per
  LD141 (mirrors the Amendment 2 fix). New sibling closer
  `MonitoringMultiTargetProbeAndDefaultSlosIT` (under
  `io.cortex.monitoring.closer`) proves the multi-target
  fan-out + default-defs binding end-to-end. SHIPPED
  (#125, ADR-0046 Amendment 3 2026-06-08).
- **P8.3** -- new backend `CounterFamilySloBudgetEngine` +
  `SloDefinition` schema extension carrying
  `(metricName, successTagPredicate, failureTagPredicate)`.
  Unlocks parse-success / dispatch-success / fan-out /
  publish-success SLIs per P5/P6 (ADR-0046 amendment
  2026-06-08). DEFERRED.
- **P8.4** -- new backend `TimerPercentileSloBudgetEngine`
  reading Micrometer `Timer` series for latency SLOs
  ("p95 <= threshold for X% of requests"). Unlocks
  gateway p95 / ingest p95 / search p95 SLIs (ADR-0046
  amendment 2026-06-08). DEFERRED.
- **P8.5** -- new backend `PromQlSloBudgetEngine` calling
  Prometheus `/api/v1/query` for SLIs whose source is not
  an in-process Micrometer series (Loki / Quickwit query
  counts, blackbox-exporter probes). Adds LD42 + LD121
  HTTP/1.1 dual-timeout pin to the monitoring service
  itself (ADR-0046 amendment 2026-06-08). DEFERRED.
- **P8.6** -- new backend `CompositeSloBudgetEngine` that
  reads other engines' snapshot cache and aggregates
  (worst-of-N, weighted-average). Unlocks
  end-to-end-log-latency / system-availability composites
  (ADR-0046 amendment 2026-06-08). DEFERRED.
- **P8.7+** -- OTel / tracing-based SLOs (span-derived
  latency, error-span ratio). Gated on OTel infra not yet
  in the cortex plan (ADR-0046 amendment 2026-06-08).
  DEFERRED.

Grafana dashboards stay scheduled for P17, NOT P8.
