# P7 -> P8 handoff: cross-service monitoring + SLO budget ownership boundary

> Single-page contract pinning everything the future
> `log-monitoring-service` (P8 epic) needs in order to own
> cross-service health aggregation + the SLO budget engine
> without colliding with the per-service `/actuator/health`
> endpoints owned by every other CORTEX service. Shipped with
> P8.0 (scaffold for the P8 epic, PR for issue #111). Owner of
> per-service health: each respective service (gateway,
> ingest, processor, remediation, indexer, echo). Owner of the
> aggregation + SLO surface: `log-monitoring-service` P8 (this
> doc, ADR-0044).

---

## 1. The ownership boundary in one paragraph

Every other CORTEX service owns its own **per-service** health
endpoint -- the standard Spring Boot Actuator surface at
`/actuator/health` reports the local view (Kafka producer
status, downstream HTTP probe verdicts, persistence reachability).
Those endpoints stay; nothing in P8 takes them over.

`log-monitoring-service` P8 owns the **cross-service aggregation**
-- it walks the Eureka registry every N seconds, probes each
registered service's `/actuator/health` over an LD42 HTTP/1.1
pinned + LD121 dual-timeout `RestClient`, classifies the per-
service verdict into the bounded `{healthy, degraded, unhealthy,
unreachable, transient_failure, permanent_failure}` outcome set,
and exports the aggregate as a single
`cortex.monitoring.probe_total{backend, outcome, service_id}`
counter family that Grafana dashboards + Alertmanager rules
subscribe to. P8.2 adds the SLO budget engine that consumes that
counter + per-service SLO targets to publish
`cortex_monitoring_slo_budget_remaining{service_id}` +
`cortex_monitoring_slo_burn_rate{service_id, window}` gauges.

Mechanically the boundary is enforced in three places:

1. **This doc + ADR-0044** (the writing).
2. **The monitoring module's pom.xml** -- zero dependency on any
   other CORTEX service module.
3. **The ArchUnit layered contract** on the monitoring side
   (`io.cortex.monitoring.probe..` is self-contained) + the
   `dependencyConvergence` enforcer at the parent.

---

## 2. The SPI shape (locked in P8.0, ADR-0044)

| Element                            | Detail                                                                                          |
|------------------------------------|-------------------------------------------------------------------------------------------------|
| Package                            | `io.cortex.monitoring.probe`                                                                    |
| Interface                          | `ServiceHealthProbe`                                                                            |
| Methods                            | `HealthSnapshot probe(ProbeRequest)`, `String backendId()`                                       |
| Verdict record                     | `HealthSnapshot(String backend, String outcome, String reason, String detail)`                  |
| Input record                       | `ProbeRequest(String serviceId, String instanceId)` (canonical-constructor validates `serviceId` against null + blank; `instanceId` nullable for fan-out probes) |
| Default impl                       | `NoopServiceHealthProbe` (gated `cortex.monitoring.probe.backend=noop`, `matchIfMissing=true`)  |
| Binder gate                        | `cortex.monitoring.probe.backend` resolved by `@ConditionalOnProperty` on each backend impl     |
| Throws contract                    | MUST NOT throw on transient downstream failure; MUST return `OUTCOME_TRANSIENT_FAILURE` / `OUTCOME_UNREACHABLE` verdict |

`HealthSnapshot` bounded outcome surface:

- `OUTCOME_NOOP` -- noop probe returned without action (P8.0 default).
- `OUTCOME_HEALTHY` -- target reported `UP` on `/actuator/health`.
- `OUTCOME_DEGRADED` -- target reported a partially-degraded status (e.g. one indicator `DOWN` but aggregate still serves).
- `OUTCOME_UNHEALTHY` -- target reported `DOWN` on `/actuator/health`.
- `OUTCOME_UNREACHABLE` -- target instance could not be reached (connect-refused, DNS failure, instance missing from registry).
- `OUTCOME_TRANSIENT_FAILURE` -- downstream timed out or returned 5xx / 429.
- `OUTCOME_PERMANENT_FAILURE` -- downstream returned non-retriable 4xx (e.g. 401, 404 actuator not exposed).

`HealthSnapshot` bounded backend surface:

- `BACKEND_NOOP` -- noop probe (P8.0).
- `BACKEND_EUREKA_ACTUATOR` -- real Eureka-discovery REST probe (P8.1+).

---

## 3. Metric contract (locked in P8.0, ADR-0044)

| Metric                                  | Tags                            | Description                                                                                    |
|-----------------------------------------|---------------------------------|------------------------------------------------------------------------------------------------|
| `cortex.monitoring.probe_total`         | `backend, outcome, service_id`  | Health probes handled by the active `ServiceHealthProbe` per backend per outcome per Eureka service id |

Bootstrap-registered at `@PostConstruct` per LD106 + LD112 +
LD125 with all-`unknown` placeholder values so the
`/actuator/prometheus` scrape exposes the family on the first
scrape. The OCP-flipped loop in
`MonitoringMetrics.bootstrapMeters()` walks the injected
`List<ServiceHealthProbe>` and bootstraps
`{healthy, degraded, unhealthy, unreachable, transient_failure,
permanent_failure}` for each `backendId()`. Adding a new probe
backend in P8.1+ ships ZERO edits in `MonitoringMetrics`.

P8.2 will add SLO gauges on the same Part 17 allowlist:

| Metric (P8.2)                                        | Tags                            | Description                                                                                    |
|------------------------------------------------------|---------------------------------|------------------------------------------------------------------------------------------------|
| `cortex_monitoring_slo_budget_remaining`             | `service_id`                    | Remaining error budget for the rolling SLO window, as a 0..1 fraction                          |
| `cortex_monitoring_slo_burn_rate`                    | `service_id, window`            | Burn rate of the error budget over the named window (`fast`, `slow`)                           |

---

## 4. Health contract (locked in P8.0, ADR-0044)

| Endpoint                                | P8.0 behaviour                                          | P8.1+ behaviour                                |
|-----------------------------------------|---------------------------------------------------------|------------------------------------------------|
| `GET /actuator/health/monitoring`       | `UP` with detail `{"backend":"noop"}`                   | Aggregates the latest `HealthSnapshot` per registered service + flips `DOWN` when any downstream is reporting `unhealthy` or `unreachable` |

Aggregated into `/actuator/health` by Spring Boot's default
aggregator + exposed to Kubernetes via
`/actuator/health/readiness` so a real outage lifts the
monitoring pod out of the K8s readiness pool (Kubernetes routes
scrape traffic to the healthy replica instead).

---

## 5. Port + Eureka coordinates

| Field                | Value                                                                       |
|----------------------|-----------------------------------------------------------------------------|
| Port                 | `:8098` (LD92: next free after `:8090` gateway, `:8092` ingest, `:8093` echo, `:8094` WireMock, `:8095` processor, `:8096` remediation, `:8097` indexer) |
| Eureka application-id| `log-monitoring-service`                                                    |
| Eureka registry      | `http://localhost:8761/eureka/` (local-dev default)                         |

---

## 6. What the consumed services must keep doing

- Keep exposing the standard `/actuator/health` surface with
  the existing `management.endpoints.web.exposure.include` block
  (`health,info,metrics,prometheus,beans` is the platform
  norm).
- Keep the per-service liveness + readiness probes wired
  (`management.endpoint.health.probes.enabled=true`); the P8.1
  probe adapter will read those via the standard groups.
- DO NOT take a dependency on `io.cortex.monitoring.probe..`.
  Per-service health is the **source of truth**; the monitoring
  service is the **subscriber**. A consumer dependency would
  invert the data flow.
- DO NOT remove or rename the existing `/actuator/health` URI
  segment. The P8.1 binding hard-codes the path; a renamed
  health URI is a P8-breaking change.

---

## 7. Sub-phase roadmap

- **P8.0** -- scaffold (#111, ADR-0044). SHIPPED.
- **P8.1** -- real `EurekaActuatorHealthProbe` HTTP client
  against per-service `/actuator/health` via Eureka discovery.
  HTTP/1.1 pin via `JdkClientHttpRequestFactory` (LD42); dual
  connect+read timeout (LD121);
  `Fault.CONNECTION_RESET_BY_PEER` for the WireMock IT
  transport-fault leg (LD120). DEFERRED.
- **P8.2** -- SLO budget engine
  (`cortex_monitoring_slo_budget_remaining` +
  `cortex_monitoring_slo_burn_rate` gauges + Alertmanager rule
  files under `infra/local/alerts/`). DEFERRED.
- **P8.1a** -- cross-phase closer (Failsafe IT singleton
  WireMock per-instance actuator stubs + smoke + Postman per
  LD104). DEFERRED.

Grafana dashboards stay scheduled for P17, NOT P8. The P8
counter + gauge surface is the **schema** Grafana will
subscribe to; the actual JSON dashboard files + alert YAML do
not ship in any P8 phase.
