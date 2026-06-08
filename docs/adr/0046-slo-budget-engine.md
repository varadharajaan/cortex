# 0046. log-monitoring-service P8.2 SLO budget engine + Micrometer-derivation backend + multi-window burn-rate alert rules

- Status: accepted
- Date: 2026-06-07
- Deciders: @varadharajaan
- Tags: monitoring, observability, slo, error-budget, burn-rate, prometheus, alerting

## Context and problem statement

P8.0 (ADR-0044) landed the `log-monitoring-service` scaffold with
the `ServiceHealthProbe` SPI, the bounded `HealthSnapshot`
verdict record, and the
`cortex.monitoring.probe_total{backend, outcome, service_id}`
counter family. P8.1 (ADR-0045) wired the
`EurekaActuatorHealthProbe` adapter that actually populates the
counters with real outcomes from
`<instance>/actuator/health`. The probe family now produces a
time-series stream of `{healthy, degraded, unhealthy,
unreachable, transient_failure, permanent_failure}` outcomes per
service, ticked at the EurekaActuatorHealthProbe cadence per
ADR-0045 D7.

What is still missing is the **operator-facing SLO surface**:
operators don't reason in raw counter samples, they reason in
**error budgets** ("svc-foo has 73 % of its 30-day availability
budget left") and **burn rates** ("svc-bar is burning the budget
at 14.4x the allowed rate"). The two industry-standard gauges
needed are:

- `cortex_monitoring_slo_budget_remaining{service_id, slo_name}`
  -- fraction of error budget remaining in `[-1.0, 1.0]`. 1.0 =
  full budget; 0.0 = exhausted; negative = over-burned (SLO
  violated).
- `cortex_monitoring_slo_burn_rate{service_id, slo_name}` --
  actual error-rate divided by the allowed error-rate. 0.0 = no
  errors; 1.0 = burning at exactly the SLO target rate;
  >1.0 burning faster than allowed.

These two gauges (defined exactly in P8 epic #9 +
`plan.md` section 9 row 8) are the input to Grafana SLO
dashboards (P17) and to multi-window burn-rate alert rules
(SRE workbook chapter 5) that page on fast burns and ticket
slow burns.

The open questions this ADR settles are:
(1) HOW does the engine surface itself -- one SPI? two? a
strategy registry? -- given that future iterations want to swap
in a Prometheus-recording-rule-backed engine and an OpenTelemetry
SpanMetrics-backed engine without rewiring callers;
(2) WHERE the per-tick evaluation loop lives -- on
`MonitoringMetrics`? a dedicated `@Scheduled` bean? -- so the
existing probe loop, the metrics surface, and the SLO loop
don't fight over the registry;
(3) WHAT the first concrete backend looks like
(`MicrometerSloBudgetEngine`) so the gauges produce real values
from the P8.1 counter family without standing up new
infrastructure;
(4) WHICH outcome tags from `HealthSnapshot.OUTCOME_*` count as
"success" vs "failure" vs "ignore" for SLO purposes;
(5) HOW the budget remaining gets clamped against the divide-by-
zero (`targetSuccessRatio == 1.0`) and over-burn
(`raw budgetRemaining < -1.0`) edges so dashboards never NaN;
(6) WHICH alert rule shape ships in
`infra/local/alerts/` so an operator running the local Compose
stack gets paging-grade alerts on day one;
(7) HOW the noop-by-default discipline (ADR-0044 D5 +
ADR-0045 D5) carries forward so the cold-start operator sees
"all clear" gauges, not "unknown" / NaN / missing time series.

## Decisions

### D1. Two-record DTO surface plus an SPI, mirroring ADR-0044 D2/D5

The engine surface is exactly three Java types under
`io.cortex.monitoring.slo.*`:

- `SloDefinition(serviceId, sloName, targetSuccessRatio, window)`
  -- the operator-supplied **input** describing WHICH SLO to
  evaluate. Validated in the compact ctor: `serviceId` and
  `sloName` reject null/blank via `StringUtils.isBlank`,
  `targetSuccessRatio` rejects anything outside
  `(0.0, 1.0)` exclusive, `window` rejects null / zero /
  negative `Duration`. The compact-ctor rejection lands at
  property-binding time so the operator sees a fast-fail at
  application startup instead of a `0.0` division at the
  first tick.

- `SloSnapshot(backend, serviceId, sloName, outcome,
  budgetRemainingRatio, burnRate, reason)` -- the engine's
  **output**. `backend` is one of `BACKEND_NOOP` or
  `BACKEND_MICROMETER_DERIVATION`; `outcome` is one of the
  seven `OUTCOME_*` constants (`NOOP / HEALTHY / AT_RISK /
  EXHAUSTED / UNKNOWN / TRANSIENT_FAILURE /
  PERMANENT_FAILURE`); `reason` is empty string for the
  banded happy paths and a `<backend>:<token>` string for
  unknown / failure paths (this asymmetry is intentional;
  see D5 + LD133-mirror).

- `SloBudgetEngine` -- a single-method SPI:
  `String backendId(); SloSnapshot evaluate(SloDefinition)`.
  The contract states `evaluate` MUST NOT throw -- engines
  catch their own internal exceptions and return
  `SloSnapshot.transientFailure(backend, def, reason)` so the
  scheduled loop never has to swallow a stray throwable. This
  mirrors `ServiceHealthProbe` (ADR-0044 D2),
  `QuickwitIndexAdmin` (ADR-0041), and `RemediationDispatcher`
  (ADR-0032 / ADR-0036).

The factory methods on `SloSnapshot` are deliberately strict:
`noop(def)` / `unknown(backend, def, reason)` /
`banded(backend, def, budget, burn)` /
`transientFailure(backend, def, reason)` /
`permanentFailure(backend, def, reason)`. The `banded` factory
calls `classifyBand(budget)` to bucket the verdict into
`OUTCOME_HEALTHY` (>0.5), `OUTCOME_AT_RISK` (>0.1, <=0.5), or
`OUTCOME_EXHAUSTED` (<=0.1) per the SRE workbook bands. The
`unknown` factory pegs gauges to
`UNKNOWN_BUDGET_REMAINING = 1.0` and `UNKNOWN_BURN_RATE = 0.0`
so the cold-start dashboard shows "all clear" -- the same
noop-by-default discipline ADR-0044 D5 established.

**Rejected**: a single mutable `SloEvaluation` POJO with setters
(loses immutability, fights `@ConfigurationProperties` binding);
a sealed-interface `SloVerdict` hierarchy (more types to learn
for callers, no benefit since `outcome` is already a bounded
string-constant allowlist).

### D2. Properties + scheduler split: `SloProperties` + `SloEvaluator` + `SloEngineConfig`

The runtime is **three** beans, deliberately separated:

- `SloProperties` -- `@ConfigurationProperties("cortex.monitoring.slo")`
  immutable record:
  `(boolean enabled, String backend, Duration evaluationInterval,
  List<SloDefinition> definitions)`. Compact ctor defaults
  blank `backend` to `BACKEND_NOOP`, null / zero / negative
  `evaluationInterval` to `Duration.ofSeconds(30)`, and null
  `definitions` to `Collections.emptyList()` (else
  `List.copyOf(definitions)` for defensive copy). This means a
  partially-filled or commented-out operator yml still wires
  rather than failing context refresh -- the same defensive-
  default discipline `EurekaActuatorProperties` established
  per ADR-0045 D2.

- `SloEvaluator` -- `@Component` gated by
  `@ConditionalOnProperty(prefix = "cortex.monitoring.slo",
  name = "enabled", havingValue = "true", matchIfMissing =
  false)`. Holds an explicit single-arg constructor
  `(SloProperties, List<SloBudgetEngine>, MonitoringMetrics)`
  per Checkstyle Rule 14.1 + LD110. The Spring scheduler
  fires `evaluateAll()` every
  `${cortex.monitoring.slo.evaluation-interval:30s}` via
  `@Scheduled(fixedRateString = "...")`; `evaluateAll`
  delegates to package-visible `evaluateOnce()` so tests can
  drive ticks without waiting on the scheduler clock. The
  loop body `tickOne(engine, def)` try-catches
  `engine.evaluate(def)` -- guard despite the SPI contract
  because the JVM cannot enforce "MUST NOT throw" -- WARN-
  logs the throwable, and continues with the next pair so a
  single bad engine cannot stall the rest.

- `SloEngineConfig` -- `@Configuration(proxyBeanMethods = false)`
  `@EnableConfigurationProperties(SloProperties.class)`.
  NOT gated by `ConditionalOnProperty` -- the properties bean
  must exist even when the engine is disabled so the
  noop / micrometer-derivation engines can still be wired (for
  unit testing) without flipping the master switch. This
  mirrors P8.1's `EurekaActuatorHttpConfig` pattern --
  scheduled / runtime beans gate themselves; configuration
  classes do not.

The split is deliberate: the properties bean is loaded with the
context regardless of `enabled`, the engine beans
self-gate on `backend=`, and the scheduler self-gates on
`enabled=true`. An operator can preview the engine wiring by
loading the context without firing the scheduled tick (set
`enabled=false` + `backend=micrometer-derivation` to see beans
in the actuator `/beans` endpoint without burning gauge cycles).

**Rejected**: a single `SloComponent` god-bean (couples three
concerns; impossible to unit-test the loop without the
scheduler); putting `@Scheduled` directly on `MonitoringMetrics`
(violates the metrics-bean-is-just-a-surface invariant
established by ADR-0044 D3 and confirmed by LD131).

### D3. `MicrometerSloBudgetEngine` -- registry-introspecting Leg A backend

The first real backend reads the existing
`cortex.monitoring.probe_total{backend, outcome, service_id}`
counter family (ADR-0044 D3 + LD33 envelope discipline) directly
from the in-process `MeterRegistry`, sums successes vs failures
by outcome tag, and derives budget + burn:

```
errorBudget       = 1.0 - targetSuccessRatio          // e.g. 0.01 for 99 % SLO
errorRate         = failureCount / (successCount + failureCount)
burnRate          = errorRate / errorBudget           // uncapped on the high side
rawBudgetRemaining = (errorBudget - errorRate) / errorBudget
budgetRemaining   = clamp(rawBudgetRemaining, -1.0, +1.0)
```

The outcome classification is explicit (anchored on the bounded
`HealthSnapshot.OUTCOME_*` constants, NOT magic strings):

| Outcome constant            | Counted as | Rationale                              |
|-----------------------------|------------|----------------------------------------|
| `OUTCOME_HEALTHY`           | success    | Probe target was up + responsive       |
| `OUTCOME_DEGRADED`          | success    | Per ADR-0044 D3: still serving traffic |
| `OUTCOME_UNHEALTHY`         | failure    | Target reported `DOWN`                 |
| `OUTCOME_UNREACHABLE`       | failure    | Network / 4xx connectivity error       |
| `OUTCOME_TRANSIENT_FAILURE` | failure    | Timeout / 5xx                          |
| `OUTCOME_PERMANENT_FAILURE` | failure    | Hard classification failure            |
| `OUTCOME_NOOP`              | ignored    | Probe didn't actually evaluate         |
| `OUTCOME_UNKNOWN`           | ignored    | Defensive default; no info             |

If no counters exist for the target `serviceId` (cold start, no
ticks yet) the engine returns
`SloSnapshot.unknown(backend, def, "micrometer-derivation:no-data")`
which pegs the gauges to the "all clear" defaults per D1 above.
Any thrown `RuntimeException` is caught and mapped to
`SloSnapshot.transientFailure(backend, def,
"micrometer-derivation:exception:<simpleClassName>")` so the
loop never propagates a registry-internal error.

The engine is `@Component`-gated by
`@ConditionalOnProperty(prefix = "cortex.monitoring.slo",
name = "backend", havingValue = "micrometer-derivation")` -- the
default `noop` backend matches via `matchIfMissing = true` in
`NoopSloBudgetEngine`. This is the same conditional pattern
ADR-0044 D5 + ADR-0045 D5 established.

**Rejected**: per-instance time-series with an `instance_id` tag
(cardinality explosion -- a 100-pod service produces 100 series
per gauge per SLO); a Prometheus recording-rule-only approach
(pushes the math off the JVM but couples dashboards to external
infra and slows iteration); a push-model engine to a
Pushgateway (at-most-once delivery + cardinality leak); an
OpenTelemetry SpanMetrics aggregator (wrong abstraction --
SLOs are not span-derived); an in-process SQLite WAL for
sliding-window aggregation (boring beats clever -- the
Micrometer counter already does this in memory).

### D4. `MonitoringMetrics.recordSlo(SloSnapshot)` -- the gauge surface

The two gauges are owned by `MonitoringMetrics` (the existing
metrics surface bean per ADR-0044 D3), not by `SloEvaluator` or
by the engine. The evaluator calls `metrics.recordSlo(snapshot)`
after every successful `tickOne` and `MonitoringMetrics` handles
the gauge registration + value update:

- First-contact for a `(serviceId, sloName)` key:
  `recordSlo` registers a `Gauge.builder` for
  `METRIC_SLO_BUDGET_REMAINING` and another for
  `METRIC_SLO_BURN_RATE`, both tagged
  `{service_id = <id>, slo_name = <name>}`, both backed by
  an `AtomicReference<SloSnapshot>` holder. The gauge
  function dereferences `holder.get()` and returns
  `snapshot.budgetRemainingRatio()` / `snapshot.burnRate()`.
  If the holder is null (impossible by construction but
  defensive), a `snapshotOrZero(holder)` helper returns a
  stub `OUTCOME_UNKNOWN` snapshot pegged to the noop
  defaults so the gauge never NaNs.
- Subsequent contacts for the same key:
  `computeIfAbsent` finds the existing holder and the
  `AtomicReference.set(newSnap)` swap flips the value the
  gauge function dereferences. No gauge re-registration --
  exactly one time-series per `(serviceId, sloName)` key
  for the lifetime of the JVM. This idempotency is
  asserted by `MonitoringMetricsTest`.

Tag coercion mirrors `incProbe`: blank `serviceId` / `sloName`
get coerced to the `MonitoringMetrics.UNKNOWN` constant so a
misconfigured engine doesn't produce un-tagged gauges that fail
the Part 17 allowlist. `recordSlo(null)` throws
`NullPointerException` via `Objects.requireNonNull` -- the
evaluator's `tickOne` already null-checks the snapshot, so a
null reaching this surface is a real bug.

The `sloCache : ConcurrentMap<SloKey, AtomicReference<SloSnapshot>>`
field is `private final` and initialized inline (which excludes
it from Lombok's `@RequiredArgsConstructor` since Lombok only
generates ctor params for `final` fields **without** an
initializer). This is the same Lombok-exclusion technique
`MonitoringMetrics` uses for the bootstrap-cache fields.

**Rejected**: separate `SloMetrics` bean (creates a second
metrics surface that breaks the "one metrics bean per service"
invariant); have `SloEvaluator` register gauges directly
(circular -- engines would need a back-pointer to the registry,
breaking the SPI contract); use `MultiGauge` (cardinality
operator-controlled at write-time which makes the Part 17
allowlist invariant impossible to enforce statically).

### D5. Factory naming asymmetry mirroring LD133

The same parameter-naming asymmetry P8.1's `HealthSnapshot`
factories established (LD133) carries forward intentionally:

- `SloSnapshot.banded(backend, def, budget, burn)` -- happy
  path, `reason = ""`. The classification already conveys the
  meaning (band + numeric values); a reason field on the
  happy path is noise.
- `SloSnapshot.unknown(backend, def, reason)` -- diagnostic
  path, `reason` is `<backend>:<token>` where token is
  `no-data` for cold start, `disabled` for circuit-broken,
  etc.
- `SloSnapshot.transientFailure(backend, def, reason)` and
  `permanentFailure(backend, def, reason)` -- failure paths,
  `reason` is `<backend>:<token>` or
  `<backend>:exception:<simpleClassName>` per the
  engine-throws path.
- `SloSnapshot.noop(def)` -- shorthand; backend is forced to
  `BACKEND_NOOP`, outcome to `OUTCOME_NOOP`, reason to a
  canonical "noop engine: gauges defaulted to all-clear" so
  dashboards looking at the reason label can distinguish
  noop from `unknown:no-data`.

Tests that assert against the happy-path gauges check numeric
values; tests against the failure / unknown paths check the
reason string. Asserting against the wrong field is the same
mistake LD133 captured for `HealthSnapshot` and will fail with
`expected: "<token>" but was: ""` -- a deliberate signal, not
a bug.

### D6. Multi-window burn-rate alert rules under `infra/local/alerts/`

Three alert rules ship in
`infra/local/alerts/slo-burn-rate.rules.yml`:

- `CortexSloFastBurn` -- fires when **both** the 5 m and 1 h
  burn-rate `max_over_time` windows exceed **14.4x** for 2 m
  (severity: `page`). 14.4x is the SRE workbook's "consume
  2 % of the 30-day budget in 1 h" threshold.
- `CortexSloSlowBurn` -- fires when **both** the 1 h and 6 h
  burn-rate windows exceed **6x** for 15 m (severity:
  `ticket`). 6x is the workbook's "consume 5 % of the
  30-day budget in 6 h" threshold.
- `CortexSloBudgetExhausted` -- fires when
  `budget_remaining` goes **negative** for 5 m
  (severity: `ticket`). Backstop for incidents shorter than
  the burn-rate windows.

The multi-window pattern (fast 5m + slow 1h) suppresses
flapping: a single brief burst raises the fast window only and
never trips the alert; a genuine 1-hour-long incident raises
both windows. This is the SRE-blessed default for SLO alerting
and matches the Grafana SLO plugin conventions.

The file is checked in under `infra/local/alerts/` (Compose
mounts it as a Prometheus rule_file). The operator runbook
header at the top of the file documents the mount path so a
fresh clone is alert-ready in one Compose restart.

**Rejected**: shipping the rules in a separate Helm chart
(out of scope -- this repo is local-Compose-first;
Helm/Kustomize land in P19); shipping a single-window alert
(flaps); fold the alert into the engine bean (alerting is a
Prometheus concern, not an in-process JVM concern).

### D7. Noop-by-default + master `enabled` switch

The cold-start cortex operator runs with:

```yaml
cortex:
  monitoring:
    slo:
      enabled: false              # master switch
      backend: noop               # default if enabled
      evaluation-interval: 30s
      definitions: []             # operator-supplied
```

`enabled=false` means `SloEvaluator` doesn't even get
constructed (`@ConditionalOnProperty matchIfMissing=false`).
The `NoopSloBudgetEngine` IS wired (its
`@ConditionalOnProperty matchIfMissing=true` keeps it as the
default backend), so operator-side unit tests that instantiate
the engine directly still work without flipping the master.

Flipping `enabled=true` with no `definitions:` produces zero
gauge writes per tick (empty loop body) -- no spam, no
NaN, no missing time-series. Adding a `definitions:` entry
flips the gauges live within one
`evaluation-interval` tick.

The two env vars `CORTEX_MONITORING_SLO_ENABLED` +
`CORTEX_MONITORING_SLO_BACKEND` are 12-factor escape hatches
in line with all other cortex `cortex.*` properties.

**Rejected**: ship enabled-by-default (every cortex service that
adds the monitoring dep would suddenly get a scheduled tick --
violates ADR-0044 D5 noop-by-default discipline); auto-discover
SLO definitions from a database (out of scope for P8;
definitions are operator-supplied yml until P17 ships a control
plane).

## Consequences

### Positive

- Two gauges (`cortex_monitoring_slo_budget_remaining` +
  `cortex_monitoring_slo_burn_rate`) ship from the monitoring
  service with realistic values derived from the P8.1 probe
  counter family -- no new infra needed, no external recording
  rules required.
- Operators get paging-grade fast-burn alerts + ticket-grade
  slow-burn alerts in `infra/local/alerts/` on day one --
  Prometheus restart away.
- The SPI surface (`SloBudgetEngine`) is forward-compatible
  with the deferred backends (Prometheus recording rules,
  OTel SpanMetrics, push-model) without rewriting the
  evaluator loop or the gauge surface.
- All-clear cold-start posture preserved: noop default + master
  `enabled=false` + `unknown` defaults to `1.0 / 0.0` so a
  freshly-cloned cortex never sees red gauges.
- Verification triangle Leg A green: Surefire 104 (50 prior +
  54 new across `slo.*` + `MonitoringMetricsTest` extension) +
  Failsafe 9 unchanged + Checkstyle 0 + SpotBugs 0 + JaCoCo
  BUNDLE 0.80 / 0.80 met + ArchUnit 0 violations after adding
  `Slo` layer (allowed access: `App` + `Metrics`).

### Negative

- `MicrometerSloBudgetEngine` derives over the **lifetime**
  of the counter family (counters are monotonic-since-process-
  start). The first iteration does NOT sliding-window the
  derivation -- `window` on `SloDefinition` is carried for
  forward compatibility but the Micrometer backend currently
  ignores it. A genuine sliding-window engine ships in a
  later sub-phase (likely P8.3 or P8.4) when Prometheus
  recording rules can supply the windowed inputs.
- The `recordSlo` gauge surface uses one
  `AtomicReference` holder per `(serviceId, sloName)` key.
  At very high cardinality (thousands of services x dozens
  of SLOs) the `ConcurrentHashMap` will dominate the bean's
  heap. Acceptable for P8 (single-digit services); flag as
  a P17 follow-up if cardinality grows.
- The scheduled tick is single-threaded by default
  (`@Scheduled` uses the shared task scheduler). A slow
  engine x N definitions x 30 s interval can stretch the tick
  past the interval. The loop body is fast (sums + math) so
  this is unlikely; the deferred sliding-window engine will
  need its own thread pool.

### Risks and mitigations

- **Cardinality leak via engine bug**: a misbehaving engine
  could emit per-instance `service_id` tags. Mitigated by
  the bounded `HealthSnapshot.OUTCOME_*` allowlist on the
  counter source side -- the engine reads pre-allowlisted
  tags. The `recordSlo` surface tag-coerces blanks to
  `UNKNOWN` rather than passing them through.
- **NaN gauges on cold start**: if the engine returned a
  literal `Double.NaN` the gauge would silently break
  dashboards. Mitigated by the `unknown` factory pegging
  gauges to `1.0 / 0.0` and the `snapshotOrZero` defensive
  default in the gauge function.
- **Scheduler / Spring context cycle**: `MonitoringMetrics`
  receives `SloSnapshot` writes; `SloEvaluator` injects
  `MonitoringMetrics`. No cycle because `MonitoringMetrics`
  does NOT inject `List<SloBudgetEngine>` -- it only
  exposes `recordSlo`. (LD131 -- which mandates `@Lazy`
  on the metrics-ctor param when the metrics bean injects
  the SPI list -- is NA here for the same reason.)
- **`evaluateOnce()` package visibility for tests**: the
  test hook is package-private (the tests live in
  `io.cortex.monitoring.slo`, same package). External
  callers must use `evaluateAll()` which is the public
  surface invoked by `@Scheduled`.

## Test plan

- `SloDefinitionTest`: 11 tests covering the compact-ctor
  rejection matrix (null/blank serviceId/sloName,
  target <= 0, target >= 1, null/zero/negative window).
- `SloSnapshotTest`: 13 tests covering all factories
  (`noop`, `unknown`, `banded`, `transientFailure`,
  `permanentFailure`), the `classifyBand` boundaries
  (0.501 / 0.5 / 0.11 / 0.1), and null-coercion behavior on
  serviceId / sloName.
- `SloPropertiesTest`: 8 tests covering defensive defaults
  (blank backend, null/zero/negative interval, null
  definitions) + defensive `List.copyOf` on definitions.
- `NoopSloBudgetEngineTest`: 3 tests confirming `backendId`
  constant, always-noop verdict, and gauge defaults.
- `MicrometerSloBudgetEngineTest`: 10 tests seeding a
  `SimpleMeterRegistry` via the `MonitoringMetrics.incProbe`
  surface and verifying band classification (healthy /
  at_risk / exhausted), clamp at `-1.0`, no-data path,
  noop-outcome ignored, degraded counts as success, and
  cross-service counter isolation.
- `SloEvaluatorTest`: 5 tests with hand-rolled
  `SloBudgetEngine` test doubles covering empty definitions
  (no-op), happy-path engine x N definitions, throwing
  engine doesn't stall the loop, null-snapshot is
  swallowed + logged, snapshot reaches the metrics surface.
- `MonitoringMetricsTest` (extended): 4 new tests covering
  `recordSlo` gauge registration, idempotency (no
  re-registration on second call), null rejection, blank tag
  coercion to `UNKNOWN`.
- `ArchitectureTest`: updated to add `Slo` layer
  (`io.cortex.monitoring.slo..`) with allowed access from
  `App` + `Metrics`; `Metrics` layer updated to allow access
  from `Slo`.

Verification triangle Leg A only (per LD104): Legs B (boot
smoke), C (Postman + Newman), D (cross-phase SpringBootTest IT),
E (per-module README runbook smoke) deferred to the P8.1a +
P8.2a closer once a real evaluator wiring exists end-to-end with
a Compose Prometheus that actually scrapes the gauges and fires
the alert rules.

## References

- ADR-0044 -- P8.0 scaffold (this builds on it: `ServiceHealthProbe`
  SPI + `MonitoringMetrics` surface + noop-by-default).
- ADR-0045 -- P8.1 EurekaActuatorHealthProbe (this consumes the
  counter family it ticks).
- ADR-0032 -- P6.0 RemediationDispatcher SPI (same
  "one SPI per backend, gated by ConditionalOnProperty"
  pattern).
- ADR-0041 -- P7.0 QuickwitIndexAdmin SPI (same noop-default
  pattern).
- SRE workbook chapter 5 ("Alerting on SLOs"):
  https://sre.google/workbook/alerting-on-slos/ -- 14.4x +
  6x multi-window thresholds; classification of fast vs
  slow burn.
- Issue #115 -- P8.2 acceptance bar.
- Issue #9 -- P8 epic (parent).

---

## Amendment 2026-06-08 -- single-backend / single-SLI deliberate scope at P8.2 + deferred backends + sub-phases roadmap

**Trigger**: operator scoping question on 2026-06-08:

> "For the SLO, are these the only SLOs needed or u have plans
> for any more future SLOs or the past services SLOs?"

The answer is **no, P8.2 does NOT cover the full SLO matrix** --
by design. P8.2 ships the generic engine + exactly ONE backend
(`MicrometerSloBudgetEngine`) that reads exactly ONE counter
family (`cortex.monitoring.probe_total{backend, outcome,
service_id}`) and therefore can derive exactly ONE SLI
dimension: **dependency availability**, and only for services
where an `EurekaActuatorHealthProbe` is actively pointed at
them. The production `definitions:` list ships empty.

The past services P3..P7 have ZERO SLO coverage today. The full
SLO matrix is decomposed below; each row ships in its own
dedicated sub-phase with its own issue + PR + acceptance bar.
This is the **deferred-backends roadmap**, recorded here so the
autopilot does not silently fan out scope creep into the
original P8.2a closer.

### Per-service SLI candidates (NOT covered today)

| Service | SLI candidates |
| -- | -- |
| `log-gateway` (P3) | JWT-validation success; rate-limit decision correctness; NL->LogQL translate success; per-route 5xx; per-route p95/p99 latency. |
| `log-ingest` (P4) | Ingest endpoint success (validate+dedupe+enqueue chain); Postgres write success; Kafka publish success; end-to-end ingest latency p95/p99. |
| `log-processor` (P5) | Parse success; schema-validate success; DLQ rate (inverse); AI anomaly classifier success; Loki + Quickwit fan-out per-sink success (ADR-0030); `cortex.anomalies.v1` publish success; consumer-lag p95. |
| `log-remediation` (P6) | Slack / PagerDuty / Jira dispatch success (ADR-0033 / ADR-0034 / P6.3); playbook execution success; `cortex.anomalies.v1` consumer lag. |
| `log-indexer` (P7) | `ensureIndex` success; `dropIndex` success; retention sweep completion latency; admin API p95 (ADR-0036 / ADR-0039). |
| `log-monitoring` (P8 self-SLO) | Probe loop success per backend; SLO evaluation loop tick latency; `/actuator/prometheus` scrape success. |
| `log-agent-lib` (cross-cutting) | Correlation-ID injection success -- typically rolled into the gateway SLO, not standalone. |
| **System-level composites** | End-to-end log latency (ingest -> search-ready in Quickwit); end-to-end anomaly latency (processor -> remediation alert delivered); worst-of-N availability; weighted-availability. |

### Deferred backends + sub-phase queue

| Sub-phase | What ships | Why deferred | Acceptance bar (one-line) |
| -- | -- | -- | -- |
| **P8.2a** | Mechanical closer for the CURRENT surface: Legs B (`scripts/smoke-p8-2.ps1` + real Prometheus container) + C (Postman + Newman covers `/actuator/prometheus` evidence) + D (cross-phase SpringBootTest IT flipping `probe.backend=eureka-actuator` + `slo.{enabled=true, backend=micrometer-derivation}` end-to-end) + E (README runbook). | Validates ONLY what P8.2 actually shipped -- no new backends, no new SLI types. Already queued in `checkpoint.md` Next Action. | Smoke + Postman + IT + README runbook all green; closes the P8 epic #9 only IF P8.1a also ships. |
| **P8.2b** | Extend `EurekaActuatorHealthProbe` to multi-target (one probe instance per cortex service-id, or a fan-out variant) + ship default `cortex.monitoring.slo.definitions:` block in `application-local.yml` with `availability` SLOs for every P3..P7 service. | No new backend needed -- re-uses P8.1 + P8.2 verbatim. Cheapest path to "every cortex service has at least one SLO". | All 5 of `log-gateway`/`log-ingest`/`log-processor`/`log-remediation`/`log-indexer` have an `availability` SLO snapshot visible in `/actuator/prometheus` under the local profile. |
| **P8.3** | New backend `CounterFamilySloBudgetEngine` + `SloDefinition` schema extension carrying `(metricName, successTagPredicate, failureTagPredicate)`. | Schema bump is breaking-change-shaped (yml binding) -- needs its own deliberate ship + ADR for the schema migration. Unlocks parse-success / dispatch-success / fan-out-success / publish-success SLIs per P5/P6. | At least one P5 SLO (`anomaly-publish-success` against `cortex.processor.anomalies_total`) and one P6 SLO (`slack-dispatch-success` against `cortex.remediation.dispatch_total`) demonstrate the backend end-to-end. |
| **P8.4** | New backend `TimerPercentileSloBudgetEngine` reading Micrometer `Timer` series for latency SLOs ("p95 <= threshold for X% of requests"). | Latency-target math differs from ratio-target -- needs its own SPI extension or contract clarification (`SloDefinition` may carry a `targetType` enum). | At least one P3 SLO (`gateway-route-latency-p95`) demonstrates the backend end-to-end. |
| **P8.5** | New backend `PromQlSloBudgetEngine` calling Prometheus `/api/v1/query` for SLIs whose source is NOT an in-process Micrometer series (Loki / Quickwit query counts, blackbox-exporter probes, etc.). | Adds outbound HTTP dependency to the monitoring service itself -- needs LD42 + LD121 dual-timeout pin + its own ADR for the auth / TLS / failover posture. | At least one external-source SLO (`quickwit-search-success` derived from a Prometheus query against the indexer's metrics) demonstrates the backend end-to-end. |
| **P8.6** | New backend `CompositeSloBudgetEngine` that reads other engines' snapshot cache and aggregates (worst-of-N, weighted-average). | Pure composition over the other backends -- only meaningful once P8.2b + P8.3 + P8.4 (and ideally P8.5) have shipped. | At least one composite SLO (`cortex-system-availability` = worst-of of every per-service availability SLO) demonstrates the backend end-to-end. |
| **P8.7+** | OTel / tracing-based SLOs (span-derived latency, error-span ratio). | Gated on OTel infra not yet in the cortex plan -- depends on a separate transport decision (collector vs SDK direct) outside P8 scope. | Deferred until OTel transport ADR lands. |

### Decision

P8.2 keeps its single-backend / single-SLI deliberate scope.
The operator pipeline is now:

1. **P8.2a** (mechanical closer for the existing surface) --
   queued first because it does NOT depend on any scope
   expansion.
2. **P8.2b..P8.6** (gap-filling sub-phases) -- queued in
   priority order; each ships independently with its own
   issue + PR + ADR delta. None of them block P8.2a.

This amendment makes the deferred work visible in the same
place the original decision lives. ADR count stays at 46 (this
is an amendment, not a new ADR -- mirrors the ADR-mechanic
precedent in this repo).

### Cross-ref

- Issue #117 -- P8.2 roadmap honesty pass (this amendment).
- LD136 -- operator-flagged SLO scope gap captured in
  `memory.md` as a standing decision so future autopilot
  prompts know the gap is intentional + tracked.

## Amendment 2026-06-08 -- `SloEvaluator @Scheduled fixedRateString` SpEL bean reference (issue #120 / LD137 prod fix)

### Background

The P8.2a cross-phase closer IT
(`MonitoringProbeAndSloPipelineIT`, ADR-0047) was the first
Spring context in the project that booted with
`cortex.monitoring.slo.enabled=true`, which is the gate that
causes Spring to instantiate the `SloEvaluator` bean and
process its `@Scheduled` declaration. Bean creation failed
immediately with:

```
org.springframework.beans.factory.BeanCreationException:
  Error creating bean with name 'sloEvaluator' ...
  Encountered invalid @Scheduled method 'evaluateAll':
  Invalid fixedRateString value "30s";
  java.lang.NumberFormatException: For input string: "30s"
```

The original declaration was:

```java
@Scheduled(fixedRateString =
        "${cortex.monitoring.slo.evaluation-interval:30s}")
public void evaluateAll() { ... }
```

Spring's `ScheduledAnnotationBeanPostProcessor.parseFixedRate`
resolves `fixedRateString` via `Long.parseLong(...)` directly
in this Boot version -- there is no `Duration.parse` fallback.
The operator-friendly `30s` / `1h` / `PT30S` forms documented
in `application.yml` and accepted by
`SloProperties.evaluationInterval()` (a `java.time.Duration`)
therefore failed at bean-creation time.

The P8.2a closer IT shipped with a numeric-millis pin (
`cortex.monitoring.slo.evaluation-interval=3600000`) as an
IT-only workaround so the closer could ship without blocking
the P8 epic. The workaround was captured as LD137 + GitHub
issue #120 with three candidate fixes; the closer respected
LD104 closer-separation and deferred the prod fix to its own
PR. The P8.1a closer (#122 / ADR-0048) intentionally left
`slo.enabled=false` and therefore did not touch the bug.

### Decision

Adopt option (1) from issue #120: route the cadence through an
adapter bean.

1. Add `@Bean(name="sloEvaluationIntervalMillis") Long
   sloEvaluationIntervalMillis(SloProperties properties)` to
   `SloEngineConfig`, returning
   `properties.evaluationInterval().toMillis()`. Pin the bean
   name via a `public static final String
   SLO_EVALUATION_INTERVAL_MILLIS_BEAN` constant on the same
   class so the SpEL string in the `@Scheduled` annotation
   and the bean cannot drift independently (a typo on either
   side now degrades to a boot-time bean-creation failure
   rather than silent breakage).
2. Change the declaration to
   `@Scheduled(fixedRateString = "#{@sloEvaluationIntervalMillis}")`.
   The SpEL bean reference resolves to a `Long`, which
   `Long.toString` renders as a plain integer literal --
   exactly the form `Long.parseLong` accepts.
3. Revert the P8.2a IT's numeric-millis override; the IT now
   uses the operator-friendly `1h` form to keep the
   scheduler quiet during the test window.
4. Add `SloEvaluatorScheduledBootIT` (narrowest-possible
   Spring-bootstrap IT under `io.cortex.monitoring.closer`)
   that boots with `slo.enabled=true` AND
   `evaluation-interval=30s` (the operator-friendly test
   default) so a future regression in the cadence wiring
   surfaces as an immediate failure of a test named after
   the path it protects.
5. Update operator docs (`application.yml` comment, service
   README env-var table, ADR-0048 P8.1a IT javadoc, P8.2a
   IT class javadoc) to remove the LD137 caveat and replace
   it with a reference to this amendment.

### Considered options

1. **(chosen) Adapter bean + SpEL bean reference.** Keeps
   the operator-facing `Duration` syntax, keeps the
   `SloProperties` compact-ctor validation, and pins the
   bean-name <-> SpEL link via a constant. One new bean,
   one annotation edit.
2. **Switch the operator contract to numeric millis.** A
   one-line change but breaks LD90 / LD126
   operator-friendliness norms and forces every operator
   to learn that `30s` means `30000`. Rejected.
3. **Use ISO-8601 (`PT30S`) and trust a future Spring
   upgrade.** This Boot version's processor does not fall
   back to `Duration.parse`, so the bug repros with
   `PT30S` too. Moot. Rejected.

### Consequences

- `SloEvaluator` boots cleanly under `slo.enabled=true`
  with the operator-friendly Duration syntax. Verified by
  the new `SloEvaluatorScheduledBootIT` (2 tests; one
  asserts the evaluator bean is created, one asserts the
  cadence-adapter bean is published under the pinned
  canonical name and resolves to the expected millis).
- The P8.2a closer IT no longer carries the LD137
  numeric-millis workaround; the operator-friendly
  `evaluation-interval=1h` form is now safe under
  `slo.enabled=true`.
- The P8.1a closer IT was never sensitive to LD137 (it
  leaves SLO at the noop default) -- the only doc-level
  cost is rewording the package javadoc + class javadoc
  to describe the post-fix state.
- The SpEL bean reference makes the cadence wiring an
  explicit two-hop chain: `application.yml` ->
  `SloProperties.evaluationInterval()` (Duration) ->
  `sloEvaluationIntervalMillis` bean (Long millis) ->
  `@Scheduled(fixedRateString="#{@...}")`. This is more
  ceremony than a direct placeholder lookup, but the
  ceremony is necessary because Spring's processor only
  accepts the long-as-string form. The
  `SLO_EVALUATION_INTERVAL_MILLIS_BEAN` constant keeps the
  link discoverable from either end.
- Future SLO-related `@Scheduled` cadences in this module
  (e.g. P8.3 / P8.4 backends) should follow the same
  adapter-bean pattern rather than reaching for
  placeholder expansion on `fixedRateString`. Captured as
  a standing rule in `memory.md` LD141.

### Scope

ADR count stays at 46 -- this is an amendment to the
existing SLO budget engine surface decision, not a new
ADR. Mirrors the ADR-mechanic precedent for the 2026-06-08
honesty-pass amendment higher in this same file.

### Cross-ref

- Issue #120 -- the bug; this amendment is the fix.
- LD137 -- the original capture (now resolved; the entry
  stays in `memory.md` as a historical record).
- LD141 -- new standing rule: do not use direct
  `${...}` placeholders on `@Scheduled(fixedRateString=...)`;
  always route through a `Long` adapter bean + SpEL bean
  reference.
- ADR-0047 D2b -- the closer-side workaround this
  amendment supersedes.
- ADR-0048 -- the P8.1a closer (probe-only), which was
  insensitive to LD137 because it leaves SLO at the noop
  default and is therefore unaffected by this amendment.
