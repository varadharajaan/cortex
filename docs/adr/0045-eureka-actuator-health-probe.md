# 0045. log-monitoring-service P8.1 `EurekaActuatorHealthProbe` HTTP probe adapter

- Status: accepted
- Date: 2026-06-07
- Deciders: @varadharajaan
- Tags: monitoring, observability, slo, http-client, eureka

## Context and problem statement

P8.0 (ADR-0044) landed the `log-monitoring-service` scaffold with
the `ServiceHealthProbe` SPI, an immutable `HealthSnapshot`
verdict record, an immutable `ProbeRequest` input record, the
`cortex.monitoring.probe_total{backend, outcome, service_id}`
counter family bootstrap-registered via the OCP-flipped loop in
`MonitoringMetrics`, and a default `NoopServiceHealthProbe` gated
`matchIfMissing=true` so the scaffold boots green with zero
outbound HTTP. The SPI contract is fixed; what's missing is a
**real** backend that actually scrapes the per-service
`/actuator/health` endpoints and produces meaningful verdicts.

P8.1 ships that backend: `EurekaActuatorHealthProbe`, a Spring
Cloud `DiscoveryClient`-driven adapter that resolves each
`ProbeRequest.serviceId()` to one or more registered instances
via the Eureka registry, picks one (matching
`ProbeRequest.instanceId()` if supplied, otherwise the first
entry), issues a single HTTP GET to
`<instance.uri>/actuator/health` via an HTTP/1.1-pinned (LD42)
`RestClient` with a dual connect+read timeout (LD121, default
5 s), parses the JSON `status` field, and maps the result onto
the bounded `HealthSnapshot.OUTCOME_*` allowlist. Activation is
gated by `cortex.monitoring.probe.backend=eureka-actuator` per
the ADR-0044 D5 binder pattern; the noop and eureka-actuator
beans are mutually exclusive.

The open questions this ADR settles are: (1) HOW does the
adapter wire its `RestClient` (shared with the Eureka discovery
client? own bean? what factory? what timeouts?); (2) WHAT
classification rules govern the HTTP outcome -> `HealthSnapshot`
mapping (and how do those rules stay symmetric with the P6.0a
`RestDispatchTemplate` and P7.1 `RestAdminTemplate` precedents);
(3) WHAT does the SPI do when Eureka returns zero instances for
a queried `serviceId` (unreachable? transient? permanent?); and
(4) WHAT happens when the actuator body is missing the
`status` field or carries a status string the adapter has never
seen before.

## Decision drivers

- D1 -- the adapter MUST honour the ADR-0044 D7 SPI exception
  contract: `probe()` MUST NOT throw. Every failure path
  (Discovery returning null/empty, HTTP 4xx/5xx, transport
  fault, body parse error, unexpected `RuntimeException`) MUST
  funnel into a verdict envelope so the caller's
  `@Scheduled` loop / REST controller never has to wrap the
  call in its own try/catch. The four catch arms in `scrape()`
  enforce this structurally.
- D2 -- configuration MUST live in a typed
  `@ConfigurationProperties` record (`EurekaActuatorProperties`)
  bound to prefix `cortex.monitoring.eureka` so the production
  yml has exactly one place that maps env-vars to runtime knobs.
  The compact-ctor MUST defend against null/zero/negative
  `requestTimeout` and null/blank `actuatorPath` so a
  partially-filled yml still wires (mirror of
  `QuickwitProperties` P7.1).
- D3 -- the HTTP outcome -> `HealthSnapshot` mapping MUST reuse
  the EXACT same classification table the P7.1 indexer and P6.0a
  remediation modules use:
  - HTTP {`429`} -> `transient_failure / <backend>:429`
  - HTTP {`5xx`} -> `transient_failure / <backend>:5xx:<n>`
  - HTTP other {`4xx`} -> `permanent_failure / <backend>:4xx:<n>`
  - {`HttpTimeoutException`}/{`TimeoutException`} cause ->
    `transient_failure / <backend>:timeout`
  - other transport -> `transient_failure / <backend>:transport`
  - unexpected `RuntimeException` ->
    `transient_failure / <backend>:unknown`

  A new channel-agnostic helper `RestProbeTemplate` carries
  these rules; the caller invokes `template.classifyHttp(ex)`,
  `template.classifyTransport(ex)`, `template.classifyUnknown(ex)`
  in the three catch arms. Effective Java item 18: composition,
  not inheritance.
- D4 -- the happy-path 2xx response MUST be parsed at the
  adapter (not in the template) since the JSON `status` field's
  value determines whether the verdict is `healthy` (`UP`),
  `degraded` (`OUT_OF_SERVICE`), `unhealthy` (`DOWN`), or
  `degraded` with detail `unknown:<raw>` (any other value, e.g.
  custom statuses some teams emit) / `degraded` with detail
  `unknown:no-status-field` (the field is missing). Pulling the
  body parse into the template would force every future probe
  backend to accept the same JSON shape, which is wrong (a
  hypothetical K8s native readiness probe would not parse JSON
  at all).
- D5 -- Eureka returning null or an empty instance list, OR the
  requested `instanceId` matching no candidate, MUST map to
  `unreachable / eureka-actuator:no-instance` (NOT permanent or
  transient). `unreachable` is the bounded enum value reserved
  for "we never got off the box" per ADR-0044 D3. The operator
  reading the `cortex.monitoring.probe_total{outcome=unreachable}`
  series sees this as a Eureka-registry state issue, not an
  HTTP outcome.
- D6 -- a null `ProbeRequest` MUST map to
  `permanent_failure / eureka-actuator:null-request` so a buggy
  caller surfaces immediately in metrics + logs (rather than
  silently swallowing). This is the only "caller is wrong"
  contract violation the adapter handles via the verdict
  envelope per D1; lower-level argument validation
  (`new ProbeRequest(null, ...)`) is rejected by the record's
  compact constructor.
- D7 -- the adapter MUST tick the
  `MonitoringMetrics.incProbe(backend, outcome, serviceId)`
  counter on EVERY exit (happy and sad paths alike) so the
  bootstrap-registered counter family produces a continuous
  signal at the `/actuator/prometheus` scrape surface. The
  `tick(...)` helper at the bottom of the adapter centralises
  this; every return statement in `probe()` is preceded by a
  matching tick call.

## Considered options

1. **Synchronous `RestClient` + JDK HTTP/1.1 + dual timeout +
   composition-based `RestProbeTemplate` for outcome
   classification + DiscoveryClient lookup per call.** -- accepted.
2. **Reactive `WebClient` instead of `RestClient`.** --
   rejected. The aggregator's caller is a `@Scheduled` loop
   already running on a single worker thread (P8.2+); reactive
   `Mono<HealthSnapshot>` would force `block()` calls at every
   call site, which is the worst of both worlds (reactive
   plumbing complexity with zero throughput win since one probe
   call's I/O does not interleave with another in the
   aggregator's sequential per-service loop). `RestClient` is
   the synchronous Spring 6.1+ replacement for `RestTemplate`
   and is what every other CORTEX outbound HTTP path uses
   (P6.0a `RestDispatchTemplate`, P7.1 `RestAdminTemplate`,
   P7.4 `QuickwitHttpSearch`); using `WebClient` here would
   break the platform's posture symmetry.
3. **Single shared timeout (connect=read) for simplicity.** --
   rejected per LD121. A single timeout conflates two distinct
   failure modes -- connect failure (registry pointed at a dead
   IP) vs read stall (target service alive but slow). Operators
   reading the
   `cortex.monitoring.probe_total{outcome=transient_failure,
   service_id=...}` series with a single timeout cannot
   distinguish "service unreachable" (operations team should
   investigate connectivity) from "service slow" (capacity team
   should investigate load). Two distinct timeouts on the same
   `Duration` value preserve the operational distinction in the
   transport-layer exception cause chain (`HttpTimeoutException`
   vs `ConnectException`).
4. **In-adapter retry on 429 with exponential backoff.** --
   rejected. The probe is one HTTP request per scheduled tick;
   if the target is rate-limiting actuator scrapes, the SLO
   sweeper (P8.2) is the right place to back off, not the
   adapter. In-adapter retry would also break the
   "one probe call -> one counter tick" invariant from D7 and
   make the `probe_total` series uninterpretable (a retried 429
   that eventually succeeds is two probe calls but only one
   service health verdict). Same posture as ADR-0033 D5 + ADR-
   0034 D5 + ADR-0035 D6 for the remediation adapters and
   ADR-0039 D7 for the indexer admin client.
5. **Fan-out across all Eureka instances of the target service
   in a single `probe()` call** (return a worst-of verdict). --
   rejected. The SPI contract is one verdict per call (ADR-0044
   D2 `ProbeRequest.instanceId()` exists exactly for callers
   that want per-instance granularity). Fan-out belongs in the
   caller's `@Scheduled` loop (it can iterate over
   `DiscoveryClient.getInstances(serviceId)` and call
   `probe(new ProbeRequest(svc, inst.getInstanceId()))` once
   per instance). This keeps the adapter stateless + makes the
   counter cardinality predictable
   (`backend x outcome x service_id` is bounded, while
   `backend x outcome x service_id x instance_id` would
   explode at high instance counts).
6. **gRPC-based probe (use the gRPC Health Checking Protocol
   v1).** -- rejected. Every CORTEX service exposes Spring Boot
   Actuator over HTTP/1.1; none expose a gRPC health endpoint.
   Adding gRPC server scaffolding to seven services just to
   feed one aggregator would invert the cost/benefit, and the
   Spring AOT footprint of `grpc-java` is non-trivial.

## Decision outcome

We pick option 1: a synchronous `EurekaActuatorHealthProbe`
adapter that consumes the Spring Cloud `DiscoveryClient` for
registry lookup + a dedicated `RestClient` bean built by
`EurekaActuatorHttpConfig#eurekaActuatorRestClient` pinned to
HTTP/1.1 via `JdkClientHttpRequestFactory` (LD42) with dual
connect+read timeout drawn from `EurekaActuatorProperties#requestTimeout`
(LD121, default 5 s). The configuration class is gated by
`@ConditionalOnProperty("cortex.monitoring.probe.backend"="eureka-actuator")`
so a `noop` deployment never allocates the JDK `HttpClient` or
the `RestClient` pool. The adapter itself is gated by the SAME
property so the noop and eureka-actuator beans are mutually
exclusive (ADR-0044 D5 binder pattern).

The HTTP outcome -> `HealthSnapshot` classification table from
D3 lives in a new `RestProbeTemplate` channel-agnostic helper
(mirror of P7.1 `RestAdminTemplate`); the adapter holds a single
private `template` field instantiated with
`HealthSnapshot.BACKEND_EUREKA_ACTUATOR`. The three catch arms
in `scrape(URI)` delegate to `template.classifyHttp(ex)` /
`template.classifyTransport(ex)` / `template.classifyUnknown(ex)`
so the rules stay symmetric with the indexer and remediation
adapters; when a future probe backend lands (e.g. K8s native
readiness) it reuses the same template by passing its own
`backendId()`.

The 2xx happy-path parses the JSON `status` field at the adapter
(D4): `UP` -> `HealthSnapshot.healthy("UP")`, `OUT_OF_SERVICE`
-> `HealthSnapshot.degraded("OUT_OF_SERVICE")`, `DOWN` ->
`HealthSnapshot.unhealthy("DOWN")`, any other value ->
`HealthSnapshot.degraded("unknown:<raw>")`, missing/non-textual
field -> `HealthSnapshot.degraded("unknown:no-status-field")`,
empty body -> `HealthSnapshot.degraded("unknown:empty-body")`,
JSON parse error -> `HealthSnapshot.degraded("unknown:parse-error")`.
The Eureka registry guard rails from D5 return
`HealthSnapshot.unreachable("eureka-actuator:no-instance")` when
`DiscoveryClient.getInstances(serviceId)` is null/empty OR when
a requested `instanceId` does not match any candidate. The null-
`ProbeRequest` guard from D6 returns
`HealthSnapshot.permanentFailure("eureka-actuator:null-request")`.

Every exit ticks the
`MonitoringMetrics.incProbe(backend, outcome, serviceId)` counter
per D7 via the private `tick(result, serviceId)` helper. The
ctor parameter `metrics` is annotated `@Lazy` per LD131 to break
the `MonitoringMetrics -> EurekaActuatorHealthProbe ->
MonitoringMetrics` cycle that would otherwise trip
`BeanCurrentlyInCreationException` the moment the binder gate is
flipped (`MonitoringMetrics` injects `List<ServiceHealthProbe>`
for the bootstrap loop). This matches the P7.1a fix captured by
ADR-0043 D2.

## Consequences

- One new adapter (`EurekaActuatorHealthProbe`) + one new
  config class (`EurekaActuatorHttpConfig`) + one new typed
  properties record (`EurekaActuatorProperties`) + one new
  classification helper (`RestProbeTemplate`) + one new
  constants holder (`MonitoringHttp`). 5 new production java
  files in total (+ 2 `package-info.java`); 4 new test classes
  (1 unit per main class + 1 WireMock IT).
- The ArchUnit layered contract gains the new
  `io.cortex.monitoring.constants` layer + the
  `Probe -> Metrics` back-edge (the adapter ticks the counter
  on every exit). Both edges are explicitly named in the
  `whereLayer(...).mayOnlyBeAccessedByLayers(...)` clauses so
  any future violation surfaces as an ArchUnit failure.
- Per LD104, the P8.1 phase ships Leg A (`mvn verify`) +
  the per-adapter Leg D slice (`EurekaActuatorHealthProbeWireMockIT`
  exercising the full ADR-0045 D3 outcome table including
  LD120 `Fault.CONNECTION_RESET_BY_PEER` transport-fault
  injection). Legs B (full-stack smoke), C (Postman + Newman),
  D-cross-phase (`@SpringBootTest` cross-phase IT), and E
  (per-module README runbook smoke) stay deferred to the P8.1a
  closer per LD104.
- The Part 17 tag-key allowlist holds at the existing 3 keys
  (`backend`, `outcome`, `service_id`) -- no new tag keys, no
  new outcomes (the seven existing `OUTCOME_*` constants
  cover every classification rule). The counter cardinality
  remains bounded.
- The WireMock IT in this phase mirrors
  `QuickwitHttpAdminWireMockIT` from P7.1: dynamic port,
  per-test `wireMock.resetAll()`, IT-local 30 s read-timeout
  per LD123 cold-start bump, `adapter()` helper that builds a
  fresh `EurekaActuatorProperties` + `RestClient` +
  `MonitoringMetrics` + `EurekaActuatorHealthProbe` per call
  with a hand-rolled `DiscoveryClient` stub pointing at the
  WireMock base URL.
- Future probe backends (K8s native readiness, gRPC health,
  push-model agent) reuse `RestProbeTemplate` + `HealthSnapshot`
  + `ProbeRequest` + the existing counter family with zero
  edits to `MonitoringMetrics` (OCP-flipped bootstrap loop).

## Related decisions / pointers

- **ADR-0044** -- the P8.0 scaffold this ADR builds on:
  `ServiceHealthProbe` SPI, `HealthSnapshot` verdict record,
  `ProbeRequest` input record, `MonitoringMetrics` bootstrap
  loop, `cortex.monitoring.probe.backend` binder gate, default
  `NoopServiceHealthProbe`. **Section 4 future-work pointer
  to the Eureka-discovery REST probe IS this ADR.**
- **ADR-0039** -- the P7.1 `QuickwitHttpAdmin` HTTP client
  whose `RestAdminTemplate` outcome classification table this
  ADR replicates in `RestProbeTemplate`.
- **ADR-0036** -- the P6.0a `RestDispatchTemplate` composition
  pattern + the OCP-flipped `RemediationMetrics` bootstrap
  loop both this ADR and ADR-0044 mirror.
- **ADR-0043 D2** -- the `@Lazy` bean-cycle fix this ADR
  applies preemptively to the `metrics` ctor parameter
  (LD131).
- **LD42** -- HTTP/1.1 pin via `JdkClientHttpRequestFactory`.
- **LD121** -- dual connect+read timeout.
- **LD120** -- WireMock `Fault.CONNECTION_RESET_BY_PEER`
  injection for the deterministic transport-fault IT case.
- **LD123** -- 30 s IT-local read-timeout bump (cold-start
  WireMock IT).
- **LD104** -- scaffold-phase ships Leg A + per-adapter Leg D
  slice only; closer ships Legs B/C/D-cross-phase/E.
- **LD110** -- `@Autowired` on ctor signature line.
- **LD131** -- `@Lazy` on `MonitoringMetrics` ctor parameter
  of every adapter where the metrics class injects
  `List<ServiceHealthProbe>` AND the adapter injects the
  metrics class.
- **LD132** -- ADR INDEX per-phase pipeline section --
  ADR-0045 row goes in the EXISTING `## Monitoring pipeline
  (P8)` section, NOT a new section.
