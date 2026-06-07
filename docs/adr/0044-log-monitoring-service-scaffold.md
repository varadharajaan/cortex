# 0044. log-monitoring-service `ServiceHealthProbe` SPI + per-backend probe contract

- Status: accepted
- Date: 2026-06-07
- Deciders: @varadharajaan
- Tags: monitoring, observability, slo, control-plane, scaffold

## Context and problem statement

P8 introduces the `log-monitoring-service`: a new Spring Boot
module that owns the cross-service health-aggregation surface
+ the SLO budget engine for the CORTEX platform. Today every
service exposes its own `/actuator/health` endpoint and ships
its own Micrometer counter family with module-local
conventions; nothing in the codebase **aggregates** those
signals into a single per-service-id verdict that a Grafana
dashboard or an Alertmanager rule can subscribe to. The
operator currently has to either (a) point Prometheus at every
service's `/actuator/prometheus` endpoint directly + write
seven separate dashboards, or (b) hit `gh run list` + curl
seven actuator URIs manually whenever the smoke is red.
Neither scales past the seven-service line we are about to
cross.

P8.0 ships the scaffold: the new module, the
`ServiceHealthProbe` SPI, a `NoopServiceHealthProbe` default
that boots green with no Eureka dependency, a Micrometer
counter, and a health indicator. The actual Eureka-discovery
REST probe adapter lands in P8.1 (#TBD); the SLO budget engine
+ Alertmanager rules in P8.2; the cross-phase closer in P8.1a
per LD104. To keep those follow-up PRs narrow we need to lock
the probe contract NOW, before any real backend exists: a
Service Provider Interface (`ServiceHealthProbe`) the caller
talks to, a verdict record (`HealthSnapshot`) the metric layer
reads, an input record (`ProbeRequest`) that carries the two
identification fields, and a binder-gate property
(`cortex.monitoring.probe.backend`) that selects exactly one
probe implementation per profile.

The open question this ADR settles is the SHAPE of that SPI:
synchronous vs reactive, single-bean vs multi-bean fan-out,
exception-throwing vs verdict-returning, how the backend /
outcome / service_id tags reach the Micrometer counter
without exploding cardinality, and -- critically -- where the
OWNERSHIP BOUNDARY between this aggregation service and the
per-service `/actuator/health` endpoints lives. We also need
to decide whether the P8.0 scaffold ships with a no-op default
or whether the caller short-circuits when no backend is
configured.

## Decision drivers

- D1 -- the probe interface MUST be synchronous + thread-safe
  so it can be called from `@Scheduled` aggregator loops (P8.1)
  AND from on-demand REST controllers (P8.2 SLO budget
  inspection) without contortions. Reactive (`Mono<...>`) would
  force a second concurrency model on a service whose primary
  surfaces are `@Scheduled` + servlet REST, with zero
  throughput win for outbound HTTP calls that are already
  faster than the upstream cadence.
- D2 -- the input MUST carry exactly two fields (`serviceId`,
  `instanceId`) so the SPI signature stays stable as the
  P8.1+ Eureka-discovery probe adapter materialises the
  full instance URI downstream from
  `serviceId` + Eureka registry lookup. The full per-instance
  actuator URI format
  (`http://<host>:<port>/actuator/health`) is computed by the
  P8.1 binding from the Eureka `InstanceInfo` record -- the
  SPI accepts whatever id the caller chose so the test
  harness can substitute fixtures.
- D3 -- the verdict record MUST carry exactly four bounded
  strings (`backend`, `outcome`, `reason`, `detail`) where
  `backend` and `outcome` are enum-like (constrained by
  `HealthSnapshot.BACKEND_*` + `HealthSnapshot.OUTCOME_*`
  constants) so the
  `cortex.monitoring.probe_total{backend,outcome,service_id}`
  counter stays inside the Part 17 tag-key allowlist with
  bounded cardinality (`noop|eureka-actuator` x 7 outcomes x
  N service ids = predictable cap). `reason` + `detail` are
  free-form strings used in caller log lines only, NEVER as
  Micrometer tag values.
- D4 -- the Spring Boot Actuator surface (`/actuator/health`,
  `/actuator/health/monitoring`) MUST surface the active
  probe backend id as a detail so the operator can verify the
  binder gate at a glance without parsing
  `/actuator/prometheus`. The `MonitoringHealthIndicator`
  reports UP for the noop backend at P8.0; the P8.1+ binding
  will aggregate the latest `HealthSnapshot` per registered
  service + flip the indicator to DOWN when any downstream is
  reporting `unhealthy` or `unreachable` so a real outage
  lifts the monitoring pod out of the K8s readiness pool
  (and Kubernetes routes scrape traffic to the healthy
  replica instead).
- D5 -- exactly ONE probe backend bean MUST be active in any
  given profile, selected by `cortex.monitoring.probe.backend`
  via `@ConditionalOnProperty`, so the caller never has to
  multiplex over a list of probe backends. (`MonitoringMetrics`
  and `MonitoringHealthIndicator` still inject
  `List<ServiceHealthProbe>` / the single bean for OCP
  bootstrap, but the runtime call path resolves to exactly one
  bean.)
- D6 -- the default (no property set) MUST be a no-op probe
  that returns `HealthSnapshot.noop(...)` so the P8.0 scaffold
  boots green without ANY downstream HTTP dependency (no
  Eureka registry, no per-service actuator credentials, no
  port mapping contention with the local-dev stack). The
  `@ConditionalOnProperty(matchIfMissing=true)` gate ensures
  this.
- D7 -- the probe SPI MUST NOT throw on transient downstream
  failures (connect-refused, IO timeout, 5xx, 429); a verdict
  with `outcome=transient_failure` or `outcome=unreachable`
  ticks the failed-outcome counter and lets the caller decide
  retry policy. Throwing is reserved for adapter contract
  violations (null arg, illegal config) and is logged +
  counted by the caller's catch-all. Symmetric with the
  P6 `RemediationDispatcher` contract per ADR-0032 D6 +
  P7 `QuickwitIndexAdmin` contract per ADR-0038 D6.

## Considered options

1. **Synchronous `HealthSnapshot probe(ProbeRequest) + String
   backendId()` SPI with exactly one backend bean per profile
   selected by `cortex.monitoring.probe.backend` +
   `@ConditionalOnProperty`; default `NoopServiceHealthProbe`
   gated by `matchIfMissing=true`.** -- accepted.
2. **In-gateway health-check filter (move the aggregation
   logic into `log-gateway` as a Spring Cloud Gateway global
   filter that intercepts a `/health/_aggregate` route).** --
   rejected: violates the gateway's narrow role (route + rate
   limit + auth per ADR-0011); welds two unrelated cadences
   (per-request HTTP filter vs `@Scheduled` aggregation)
   into one deployment; the aggregator needs its own SLOs
   (probe-call success rate, budget burn rate) that would be
   lost in the gateway's request-noise. A failed
   aggregation would surface as a gateway 5xx and risk
   tripping the same gateway-level alerts that the
   aggregator exists to feed.
3. **Spring Cloud Sleuth / Micrometer Tracing aggregator
   (treat health as a span and let the OpenTelemetry
   collector aggregate downstream).** -- rejected: health
   verdicts are point-in-time observations, not spans;
   they have no parent-child structure; their useful
   aggregation is over a per-service-id time window, not
   over a request trace. Reusing the tracing pipeline for
   health would also couple the monitoring SLO to the
   tracing infrastructure -- one of which the operator is
   most likely to disable first under load.
4. **Prometheus-only / no aggregator service (let Prometheus
   federation pull `/actuator/health` JSON via the
   `blackbox_exporter` HTTP probe + write recording rules
   that compute per-service health rollups).** -- rejected:
   this is a viable read path for a single tenant but
   moves the *classification* logic (raw `status=UP` JSON
   -> `healthy|degraded|unhealthy` outcome on the bounded
   Part 17 allowlist) out of the codebase and into PromQL
   recording rules. Recording rules are not version-
   controlled in the service repo and cannot be unit-
   tested with JUnit; classification edge cases (partial
   degradation when one component indicator is DOWN but
   the aggregate is UP, e.g. Quickwit reporting
   `OUT_OF_SERVICE` while Kafka still answers) become
   PromQL puzzles instead of Java code. Also blocked by
   D7 -- recording rules cannot distinguish
   `transient_failure` (retriable 5xx) from
   `permanent_failure` (4xx / actuator not exposed) without
   probing the underlying error class.
5. **Push-model agents (sidecar per service ships a Vector
   or Fluentbit health-pull instance + pushes the verdict
   over OTLP to a central collector).** -- rejected on
   complexity grounds. The platform already runs seven
   Spring Boot services without sidecars; adding a sidecar
   per pod doubles the deployment surface, introduces a
   second runtime per pod, and bears no resilience win for
   what is fundamentally a low-frequency
   (`every-N-seconds`) HTTP poll. The aggregator service
   pattern is the established Spring shape for this kind
   of work (the same shape `log-remediation-service` P6
   uses to aggregate cross-channel remediation outcomes).

## Decision outcome

We pick option 1: a synchronous `ServiceHealthProbe` SPI with
exactly two methods (`probe(ProbeRequest)`, `backendId()`), an
immutable `HealthSnapshot` verdict record carrying
`(backend, outcome, reason, detail)`, an immutable
`ProbeRequest` input record carrying `(serviceId, instanceId)`,
a binder-gate property `cortex.monitoring.probe.backend`
resolved by `@ConditionalOnProperty` on each backend impl, and
a default `NoopServiceHealthProbe` (gated
`havingValue="noop", matchIfMissing=true`) that returns
`HealthSnapshot.noop("noop probe (P8.0 scaffold)...")` for
every call. The Micrometer counter
`cortex.monitoring.probe_total{backend, outcome, service_id}`
is bootstrap-registered at `@PostConstruct` so the
`/actuator/prometheus` surface shows the counter family on the
first scrape, before any probe call ticks. The OCP-flipped
bootstrap loop iterates over the injected
`List<ServiceHealthProbe>` so adding a new probe backend (e.g.
the P8.1 `EurekaActuatorHealthProbe`) requires zero edits in
`MonitoringMetrics`. The `MonitoringHealthIndicator` is bound
to `/actuator/health/monitoring` and surfaces the active
backend id as a detail (P8.0 reports UP unconditionally for
the noop backend; the P8.1+ binding aggregates per-service
`HealthSnapshot`s + flips DOWN on any unhealthy / unreachable
downstream).

The ownership boundary against per-service `/actuator/health`
endpoints is captured in three mechanical places: (a) this
ADR; (b) the monitoring module's `pom.xml` has zero
dependency on any other CORTEX service module; (c) the
ArchUnit layered contract on the monitoring side keeps the
`io.cortex.monitoring.probe` package self-contained. Per-
service actuator endpoints stay owned by their respective
services; this module is a **consumer + aggregator** of those
endpoints, NOT a replacement.

## Consequences

- The P8.0 scaffold module is the SMALLEST a CORTEX service
  module has shipped at since P3.0: 8 production java files
  (App + 3 probe SPI files + 1 noop default + 1 metrics + 1
  health + 1 root `package-info` + 3 sub-package `package-info`)
  + 7 test files + 3 resources files + README + ADR-0044 +
  INDEX bump + CHANGELOG entry. No Kafka. No CloudEvents. No
  Testcontainers. No WireMock. 27 tests / 0 failures /
  JaCoCo 0.80/0.80 met from day one.
- The OCP bootstrap loop in `MonitoringMetrics` is the same
  pattern P6.0a / ADR-0036 + P7.0 / ADR-0038 introduced for
  `RemediationMetrics` + `IndexerMetrics`: a `@PostConstruct
  bootstrapMeters()` walks `List<ServiceHealthProbe>` and
  registers `{healthy, degraded, unhealthy, unreachable,
  transient_failure, permanent_failure}` series per backend
  with `service_id=unknown` placeholder so the
  `/actuator/prometheus` surface shows the family on the
  very first scrape. Adding a new probe backend (P8.1) is
  zero-edit in `MonitoringMetrics`.
- Per LD104 closer pattern, the P8.0 scaffold ships
  Leg A (`mvn verify`) only; Legs B..E (Failsafe IT, smoke,
  Postman, multi-module docs) land in the P8.1a closer once
  the real `EurekaActuatorHealthProbe` exists to validate
  the wire contract against. The scaffold's per-class unit
  tests already exercise every SPI method + every metric
  bootstrap path + the architecture layered contract.
- The bounded enum-like backend / outcome string axes mean
  the Part 17 tag-key allowlist cap (`backend`, `outcome`,
  `service_id`) cannot be violated by accident: a free-form
  string reaches the counter only via the `service_id` tag,
  whose cardinality is bounded by the platform service
  count (currently 7).
- Grafana dashboard wiring + Alertmanager rule files stay
  deferred to P17 (the dashboards epic). The P8 counter +
  gauge surface is the **schema** Grafana will subscribe to;
  the actual JSON dashboard files + alert YAML do not ship
  in any P8 phase.

## Related decisions / pointers

- **ADR-0030** -- per-sink writer side of the Quickwit fan-out
  in `log-processor-service`. Same `@ConditionalOnProperty`
  binder-gate pattern this ADR mirrors.
- **ADR-0032** -- `RemediationDispatcher` SPI in
  `log-remediation-service`. Same verdict-returning,
  exception-free SPI shape this ADR mirrors for D7.
- **ADR-0036** -- OCP-flipped bootstrap loop pattern in
  `RemediationMetrics` that `MonitoringMetrics.bootstrapMeters()`
  reuses verbatim.
- **ADR-0038** -- `QuickwitIndexAdmin` SPI in
  `log-indexer-service`. The P7.0 scaffold this ADR
  structurally mirrors (SPI + DTOs + noop default + metric
  counter + health indicator + ArchUnit contract).
- **LD3** -- the indexer is the Quickwit owner; the
  monitoring service is the cross-service health-aggregation
  owner. Per-service actuator endpoints stay.
- **LD42 + LD121** -- HTTP/1.1 pin + dual connect+read
  timeout `RestClient` posture used by P8.1
  `EurekaActuatorHealthProbe` when it lands. NOT exercised
  by P8.0 (no outbound HTTP).
- **LD104** -- scaffold-phase ships Leg A only; closer ships
  Legs B..E.
- **LD106 + LD112 + LD125** -- bootstrap-register the counter
  family at `@PostConstruct` so the first Prometheus scrape
  sees a stable surface.
- **LD129** -- explicit `<maven-failsafe-plugin>` declaration
  required in the child pom.
- **Part 17 allowlist** -- the three permitted Prometheus
  tags on the monitoring counter are `backend`, `outcome`,
  `service_id`.
