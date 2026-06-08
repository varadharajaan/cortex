# ADR-0048: log-monitoring-service P8.1a probe-only cross-phase closer

- **Status**: Accepted
- **Date**: 2026-06-08
- **Deciders**: @varadharajaan (operator)
- **Tags**: monitoring, closer, cross-phase, probe, http-surface, no-slo,
  LD104

## Context

ADR-0046 amendment 2026-06-08 (the P8.2 roadmap honesty pass shipped in
PR #118) explicitly names **two** P8-blocking closer sub-phases for the
log-monitoring-service:

1. **P8.2a** -- mechanical closer for the P8.0 + P8.1 + P8.2 ring with
   BOTH binder gates flipped + a real Prometheus container.
2. **P8.1a** -- mechanical closer for the P8.0 + P8.1 ring ONLY
   (probe surface in isolation, no SLO).

P8.2a shipped in PR #121 (merge `cc4572a`, 2026-06-08T04:30:17Z,
closes #119) with ADR-0047. ADR-0047 captures the both-gates-flipped
shape but its cross-phase IT
(`MonitoringProbeAndSloPipelineIT`) carries two IT-only
properties that are NOT representative of a production-shaped
operator deployment:

1. **`cortex.monitoring.slo.evaluation-interval=3600000`** (numeric
   millis instead of the documented `1h` / `30s` `Duration` string) --
   the LD137 / issue #120 workaround. Spring's
   `ScheduledAnnotationBeanPostProcessor.fixedRateString` calls
   `Long.parseLong(value)` directly with no `Duration.parse` fallback,
   so the prod `SloEvaluator.@Scheduled(fixedRateString="${cortex
   .monitoring.slo.evaluation-interval:30s}")` annotation is broken
   whenever `slo.enabled=true`. Masked in prod by the
   `slo.enabled=false` default `@ConditionalOnProperty` gate.
2. **`cortex.monitoring.slo.enabled=true`** -- flips a code path that
   production deployments default OFF until per-service SLO
   definitions are queued (the deferred P8.2b sub-phase).

Additionally, the P8.2a IT uses
`@SpringBootTest(webEnvironment = WebEnvironment.MOCK)` and therefore
does NOT prove the actuator-bound aggregation surface end-to-end --
specifically, it does NOT prove that
`/actuator/health/monitoring` surfaces
`details.backend=eureka-actuator` over real HTTP via embedded Tomcat
when only the probe binder gate is flipped.

Per LD138 (recorded 2026-06-08 after the stray-`Closes #9` mistake on
PR #121), the P8 epic close ONLY happens on the PR that ships the
last P8-blocking sub-phase. Once P8.1a is merged, every remaining
P8.x sub-phase (P8.2b..P8.7+) is non-blocking per the ADR-0046
amendment -- so the P8.1a closer PR carries `Closes #122` AND
`Closes #9` in its body.

## Decision

Ship a **probe-only cross-phase closer** under
`io.cortex.monitoring.closer` that mirrors the P8.2a closer pattern
structurally but covers the production-shaped probe-only deployment
shape WITHOUT depending on the LD137 workaround.

### D1. Single Failsafe IT: `MonitoringProbeAndHealthIndicatorIT`

New cross-phase Failsafe IT
`log-monitoring-service/src/test/java/io/cortex/monitoring/
closer/MonitoringProbeAndHealthIndicatorIT.java`.

Class-level annotations:

- `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, ...)`
  -- intentionally NOT `MOCK` (see D2).
- `@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)`
  -- mirrors ADR-0047 D3 / Checkstyle Rule 14.1 `private final`
  ctor-injection pattern.

Properties block sets ONLY:

- `cortex.monitoring.probe.backend=eureka-actuator` (probe binder
  gate flipped).
- `cortex.monitoring.eureka.request-timeout=30s` (LD123-style
  cold-start tolerance).
- `cortex.monitoring.eureka.actuator-path=/actuator/health`
  (matches the WireMock stub).
- `eureka.client.{enabled,register-with-eureka,fetch-registry}=false`
  (kills the real Eureka client; the stub `DiscoveryClient` is the
  sole candidate).
- `management.endpoint.health.show-details=always` +
  `management.endpoint.health.show-components=always` -- required
  for the `details.backend` assertion in D2 to see the
  per-indicator details map over the HTTP surface.
- `spring.autoconfigure.exclude=`
  `EurekaClientAutoConfiguration` +
  `CompositeDiscoveryClientAutoConfiguration` +
  `SimpleDiscoveryClientAutoConfiguration` (re-applies ADR-0047 D2a
  -- without these excludes Spring Cloud Commons publishes two
  competing `@Primary` `DiscoveryClient` candidates alongside our
  stub yielding `NoUniqueBeanDefinitionException`).

Notable **absences** vs ADR-0047 `properties=` block:

- `cortex.monitoring.slo.enabled` -- defaulted to `false` (the
  `application.yml` default + LD100 shadow). The
  `SloEvaluator @Bean` is therefore NOT instantiated.
- `cortex.monitoring.slo.backend` -- defaulted to `noop` (the
  `application.yml` default). The `NoopSloBudgetEngine
  matchIfMissing=true` bean is therefore the SOLE engine.
- `cortex.monitoring.slo.evaluation-interval` -- not set; the
  broken `fixedRateString` annotation is never reached at bean
  creation time because the evaluator bean is gated off.

### D2. `WebEnvironment.RANDOM_PORT` and `TestRestTemplate` for the HTTP indicator surface

The P8.2a IT uses `WebEnvironment.MOCK` because all its assertions
hit autowired beans directly (`MonitoringMetrics`,
`SloBudgetEngine`, `ServiceHealthProbe`). The probe-only closer
needs `RANDOM_PORT` because its distinguishing assertion is that
`/actuator/health/monitoring` returns `200 UP` with
`details.backend=eureka-actuator` over real HTTP via embedded
Tomcat.

- `@LocalServerPort private int port` captures the randomly-assigned
  embedded Tomcat port.
- `TestRestTemplate` (autowired through `@TestConstructor.ALL`)
  fires the HTTP request to
  `http://localhost:{port}/actuator/health/monitoring`.
- Body parsed via `ParameterizedTypeReference<Map<String,Object>>`
  (raw `Map.class` triggers `-Werror` per the standing compiler
  flags -- captured in repo memory).
- Assertion: `body.get("status") == "UP"` AND
  `body.get("details").get("backend") == "eureka-actuator"`.

The P8.2a `MOCK` env structurally cannot make this assertion --
no embedded Tomcat means no
`WebMvcEndpointHandlerMapping` for `/actuator/health/monitoring`.

### D3. No LD137 workaround

Because the SLO scheduler bean is gated off (D1), the broken
`SloEvaluator.@Scheduled(fixedRateString=
"${cortex.monitoring.slo.evaluation-interval:30s}")` annotation
is never exercised. The probe-only IT therefore boots the Spring
context using ONLY operator-documented configuration shapes
(no numeric-millis hack). This is the canonical proof that the
probe surface works in a configuration that does NOT depend on
the deferred issue #120 prod fix.

### D4. Re-use the existing P8.2a Postman collection

`postman/log-monitoring.postman_collection.json` shipped by P8.2a
covers the probe-only surface as well: the `Health`,
`Eureka-Probe-Contract`, and `Metrics-Baseline` folders all
assert against the binder-gate-flipped probe surface; the
`Prometheus` + `Metrics-After` folders gate themselves via
`pm.execution.skipRequest()` on `prometheus_base_url` per LD116,
so they skip cleanly when the probe-only smoke does not bring up
the Prometheus container.

No new Postman collection JSON file is needed. `postman/README.md`
gains a clarifying note on the probe-only flow but the row count
stays the same.

### D5. Standalone smoke without Prometheus

New `scripts/smoke-p8-1a.ps1` (LOCAL-ONLY, gitignored under
`/scripts/` per `.gitignore` line 129). Boots ONLY the service
JAR on `:8098` with env bag:

- `CORTEX_MONITORING_PROBE_BACKEND=eureka-actuator`
- `EUREKA_CLIENT_ENABLED=false`
- NO `CORTEX_MONITORING_SLO_*` env vars (production-shaped default).

Asserts:

1. `/actuator/health/monitoring` returns `UP` with
   `details.backend=eureka-actuator`.
2. `/actuator/prometheus` exposes the
   `cortex_monitoring_probe_total` family with `# HELP` + `# TYPE`
   header lines + Part 17 tag keys
   (`backend, outcome, service_id`).

No Prometheus container is brought up -- the P8.2a smoke covers
that surface; the P8.1a smoke proves the probe-only surface boots
green in a production-shaped configuration WITHOUT Prometheus
infrastructure.

### D6. PR body carries the epic close

Per LD138, the P8.1a closer PR (closing issue #122) is the LAST
P8-blocking sub-phase per the ADR-0046 amendment 2026-06-08, so
its PR body includes BOTH `Closes #122` AND `Closes #9` (the P8
epic). All remaining P8.x sub-phases (P8.2b..P8.7+) are
queued-but-non-blocking per the amendment.

## Considered alternatives

### Option 1 -- Roll the probe-only assertions into the P8.2a IT

**Rejected.** The P8.2a IT's `properties=` block flips
`cortex.monitoring.slo.enabled=true` and carries the LD137 workaround
`cortex.monitoring.slo.evaluation-interval=3600000`. Adding
RANDOM_PORT + the HTTP-surface assertion there would couple the
HTTP-indicator regression to BOTH the SLO scheduling code path AND
the LD137 workaround -- if the LD137 workaround ever needs to
change (e.g. when issue #120 lands and the prod fix lets us drop
the numeric-millis hack), the HTTP-indicator regression would
ride along incidentally. LD104 closer-separation discipline
forbids this kind of cross-coupling.

### Option 2 -- Deferred to P8.2b alongside multi-target probe

**Rejected.** P8.2b is itself blocked on an operator-supplied
`cortex.monitoring.slo.definitions:` block per the ADR-0046
amendment. Bundling the probe-only HTTP-surface regression into
P8.2b would push it behind that operator gate and block the P8
epic close on something the closer-pattern can resolve TODAY.

### Option 3 -- Add a `@WebMvcTest` slice for the indicator only

**Rejected.** `@WebMvcTest` slices do not boot the full
auto-configuration tree -- specifically, they do not exercise the
`@ConditionalOnProperty` binder gate the production
`EurekaActuatorHealthProbe @Component` is gated on. The closer's
job is to prove the FULL Spring autowiring contract for the
binder-gate-flipped configuration, which only a `@SpringBootTest`
delivers.

## Consequences

### Positive

- The probe-only operator deployment shape now has a
  CI-protected regression gate that boots the production-shaped
  Spring context, hits `/actuator/health/monitoring` over real
  HTTP, and asserts `details.backend=eureka-actuator`.
- The new IT does NOT depend on the LD137 workaround. When
  issue #120 lands and the prod `SloEvaluator.fixedRateString`
  is fixed, the P8.2a IT will drop the numeric-millis hack but
  the P8.1a IT will be unaffected -- exactly the LD104
  closer-separation invariant.
- The P8 epic #9 closes on this PR per the LD138 single-close
  rule. P8.2b..P8.7+ become independent non-blocking ships.

### Negative

- Two cross-phase ITs in the same `io.cortex.monitoring.closer`
  package both spin up Spring contexts -- adds ~25s to the
  Failsafe phase for the second context. Mitigated because the
  WireMock + DiscoveryClient stub patterns are identical; both
  contexts cache cleanly.
- Slight duplication between the two ITs' `properties=` blocks
  (the autoconfig-exclude triplet is repeated). Acceptable per
  LD104 -- closer ITs intentionally keep their context startup
  surface self-contained so a future change to either context's
  property set does not cascade.

### Neutral

- The existing P8.2a Postman collection is re-used; no new JSON
  files in `postman/`.
- The `application.yml` test shadow under
  `src/test/resources/application.yml` is unchanged -- the new
  IT's `properties=` block fully overrides the relevant keys.

## References

- ADR-0044 -- P8.0 scaffold + `ServiceHealthProbe` SPI + per-backend
  selection contract.
- ADR-0045 -- P8.1 `EurekaActuatorHealthProbe` HTTP probe adapter.
- ADR-0046 amendment 2026-06-08 -- names BOTH P8.1a + P8.2a as
  P8-blocking sub-phases.
- ADR-0047 -- P8.2a cross-phase closer (the pattern this ADR
  mirrors).
- LD42 + LD121 -- HTTP/1.1 dual-timeout pin on the probe
  `RestClient`.
- LD73 -- 5-leg triangle gate (closer IT is Leg D for the closer
  pattern).
- LD100 -- `src/test/resources/application.yml` fully shadows
  the main yml in `@SpringBootTest`.
- LD104 -- closer-separation discipline (closers ship one
  sub-phase, not the epic).
- LD116 -- Postman environment-gated folders via
  `pm.execution.skipRequest()`.
- LD131 -- `@Lazy MonitoringMetrics` cycle break on
  `EurekaActuatorHealthProbe`.
- LD132 -- ADR INDEX per-phase pipeline section discipline.
- LD134 -- commitlint <=100-char header + lowercase/kebab-case
  subject.
- LD137 -- Spring `@Scheduled fixedRateString` does NOT accept
  `Duration` strings (the trap this IT explicitly avoids by
  not flipping `slo.enabled=true`).
- LD138 -- never put `Closes #<epic>` in a closer-PR body if any
  sibling sub-phase remains queued (this IS the PR that closes
  the epic per the ADR-0046 amendment).
- GitHub issue #9 -- P8 epic (CLOSED by this PR per D6).
- GitHub issue #120 -- prod fix for `SloEvaluator.fixedRateString`
  (deferred; this IT is intentionally independent of that fix).
- GitHub issue #122 -- P8.1a sub-phase tracking issue (CLOSED by
  this PR).
