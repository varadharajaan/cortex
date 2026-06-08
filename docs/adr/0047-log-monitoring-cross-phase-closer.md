# 0047. log-monitoring-service P8.2a cross-phase closer (Prometheus singleton + cross-phase Failsafe IT + smoke + Postman + DiscoveryClient autoconfig-exclude + `SloEvaluator.fixedRateString` workaround)

- Status: accepted
- Date: 2026-06-08
- Deciders: @varadharajaan
- Tags: monitoring, closer, smoke, postman, cross-phase, prometheus, slo, spring-bean-cycle, autoconfig-exclude

## Context and problem statement

P8.0 (scaffold + `ServiceHealthProbe` SPI + Noop default +
`MonitoringMetrics` bootstrap loop + `MonitoringHealthIndicator`),
P8.1 (real `EurekaActuatorHealthProbe` HTTP adapter +
`RestProbeTemplate` outcome classifier), and P8.2 (SLO budget
engine + `MicrometerSloBudgetEngine` derivation backend + multi-
window burn-rate alert rules + `SloEvaluator` scheduled tick)
each shipped **Leg A only** per the LD104 closer-pattern
precedent (`B/C/D/E -- deferred to the P8.2a closer that builds
smoke + Postman + cross-phase regression ONCE for all three
P8.0..P8.2 SPI flows together`). The P8.2a closer (issue #119)
is therefore the ONLY remaining P8 deliverable before the epic
can be marked DONE.

The cross-phase regression covers a different surface than each
phase's individual unit / WireMock test:

- The per-phase tests instantiate beans directly with `new` or
  with `@Mock`, bypassing Spring entirely. The P8.1
  `EurekaActuatorHealthProbeIT` mocks `DiscoveryClient`; the
  P8.2 `SloEvaluatorTest` mocks `SloBudgetEngine`. Neither
  exercises the FULL autowired chain.
- The cross-phase regression must instead exercise the **full
  Spring autowiring path** through a `@SpringBootTest`-booted
  application with **both binder gates flipped** (`cortex.
  monitoring.probe.backend=eureka-actuator` + `cortex.
  monitoring.slo.backend=micrometer-derivation` +
  `cortex.monitoring.slo.enabled=true`), against a real
  WireMock HTTP endpoint that mimics each target service's
  `/actuator/health` surface, so the `@ConditionalOnProperty`
  selection + `MonitoringMetrics` bootstrap loop over
  `List<ServiceHealthProbe>` (LD106) + `RestClient` HTTP/1.1
  pin (LD42) + dual-timeout (LD121) + `@Lazy` cycle break on
  the probe adapter (LD131) are all wired identically to
  production AND the new SLO engine bean graph
  (`MicrometerSloBudgetEngine` + `SloEvaluator @Scheduled`)
  stands up cleanly alongside.

Additionally, the closer must ship:

1. A full-stack PowerShell **boot smoke** (`scripts/smoke-
   p8-2a.ps1`) that starts a real Prometheus 2.55.1 docker
   container scraping the actual service JAR on `:8098` and
   proves the `/actuator/health/monitoring` + Prometheus
   exposition + alert-rule-loaded contract end-to-end.
2. A **Postman collection** (`postman/log-monitoring.postman_
   collection.json`) that captures the HTTP-observable
   contract the boot smoke exercises for offline reuse / CI
   replay (`/actuator/health/monitoring` + the three metric
   families with `# HELP` + `# TYPE` + Part 17 tag allowlist
   + Prometheus `/api/v1/rules` containing the three alert
   rules + `/api/v1/targets` UP).
3. A **cross-phase Failsafe IT suite** under
   `log-monitoring-service/src/test/java/io/cortex/monitoring/closer/`
   that exercises every P8.0..P8.2 flow via autowired beans
   against an in-process WireMock + a `@TestConfiguration`
   stub `DiscoveryClient`.
4. **ADR-0047** (this doc) + INDEX bump 46 -> 47 + CHANGELOG +
   service README banner flip `P8.0+P8.1+P8.2 SHIPPED` -> `...
   + P8.2a SHIPPED`.

Two issues bit during the cross-phase IT build-out that the
per-phase tests had structurally missed:

- **`NoUniqueBeanDefinitionException: more than one 'primary'
  bean found among candidates: [stubDiscoveryClient,
  compositeDiscoveryClient, simpleDiscoveryClient]` during
  cross-phase IT context startup.** The IT publishes a
  `@TestConfiguration` `@Bean @Primary DiscoveryClient
  stubDiscoveryClient` to route the probe to the in-process
  WireMock without booting Eureka. Setting `spring.cloud.
  discovery.enabled=false` and `eureka.client.enabled=false`
  is NOT sufficient: Spring Cloud Commons' auto-configurations
  `CompositeDiscoveryClientAutoConfiguration` and
  `SimpleDiscoveryClientAutoConfiguration` still register their
  own `@Primary` `DiscoveryClient` beans by default, so the
  stub collides with two same-tier `@Primary` candidates and
  Spring refuses to choose. The per-phase
  `EurekaActuatorHealthProbeIT` missed this because it
  constructs the probe directly with `new` and mocks
  `DiscoveryClient` with Mockito, never going through
  Spring's bean factory. The closer's
  `MonitoringProbeAndSloPipelineIT` surfaced it on the first
  `@SpringBootTest` boot attempt -- the FIRST production
  consumer of the Spring-managed bean graph in this module
  with both binder gates flipped AND `eureka.client.enabled=
  false`. This is exactly the class of defect cross-phase
  closers are designed to catch per LD104 -- a real
  IT-only-environment startup-time bug.

- **`Invalid fixedRateString value "1h";
  NumberFormatException`** during cross-phase IT context
  startup once `slo.enabled=true` allowed Spring to actually
  instantiate `SloEvaluator`. Spring's
  `ScheduledAnnotationBeanPostProcessor.fixedRateString` calls
  `Long.parseLong(value)` directly with NO `Duration.parse`
  fallback, so the prod
  `@Scheduled(fixedRateString="${cortex.monitoring.slo.
  evaluation-interval:30s}")` in `SloEvaluator` is **broken
  whenever `slo.enabled=true`** -- masked in prod because
  `slo.enabled` defaults to false so the evaluator bean is
  never instantiated. The closer cannot fix this prod bug
  inside the closer PR per LD104 (`a closer fixes the bugs
  it surfaces in startup paths the closer itself wires up,
  but it MUST NOT mutate prod scheduling semantics whose
  fix has its own design tradeoff -- defer to a follow-up
  bug-fix issue + bug-fix PR`). The closer's IT instead uses
  the numeric-millis form of `cortex.monitoring.slo.
  evaluation-interval` (`3600000`) which Spring parses
  unambiguously, and files a follow-up bug issue captured as
  **LD137** for the prod fix
  (recommendation: convert `SloEvaluator` to inject a `@Bean
  Long sloEvaluationIntervalMillis(SloProperties)` and switch
  the annotation to
  `@Scheduled(fixedRateString="#{@sloEvaluationIntervalMillis}")`).

## Decision drivers

- D1 -- the cross-phase IT must run inside the normal Failsafe
  cycle (`mvn verify`) so CI catches regressions on every PR,
  not just on a manual smoke run. No standalone runner, no
  separate Maven profile.
- D2 -- the closer MUST fix any production bug it surfaces in
  the SAME PR as the closer itself, UNLESS the fix involves
  a non-trivial prod-API design tradeoff that needs its own
  ADR / its own review. The `DiscoveryClient` triplet
  `NoUniqueBeanDefinitionException` is a real IT-only-
  environment startup defect that has a one-line property
  fix and is repaired here (D2a -- accept `spring.
  autoconfigure.exclude` of the three `DiscoveryClient`
  autoconfigurations in the IT's `properties=` block). The
  `SloEvaluator.fixedRateString` defect is a real PROD bug
  but the chosen prod fix (introduce
  `@Bean Long sloEvaluationIntervalMillis(SloProperties)` +
  switch annotation to SpEL `#{@bean}` form) involves a
  prod scheduling-semantics change that benefits from its
  own design review; deferred to a follow-up bug-fix issue
  + PR (D2b). The closer's IT works around it by using the
  numeric-millis form. Captured as **LD137**.
- D3 -- the cross-phase IT must NOT use the per-phase test's
  hand-constructed adapter or mocked `DiscoveryClient`. It
  must boot the full Spring context with both
  `@ConditionalOnProperty` gates flipped so
  `EurekaActuatorHealthProbe` + `MicrometerSloBudgetEngine` +
  `SloEvaluator` beans are selected exactly the way
  production selects them, and so every autowired
  collaborator (`EurekaActuatorProperties`,
  `eurekaActuatorRestClient`, the shared `MeterRegistry`,
  `MonitoringMetrics` with its bootstrap loop, `SloProperties`)
  is wired through the bean factory.
- D4 -- the in-process WireMock must be a **singleton**
  `WireMockServer` started in a static block (NOT a per-test
  `@RegisterExtension WireMockExtension`) so the cold-start
  context boot is paid ONCE for all 9 test methods, mirroring
  the ADR-0043 D4 precedent in log-indexer-service. Stub +
  journal isolation between tests is provided by
  `@BeforeEach WIRE_MOCK.resetAll()` + re-install of the
  default `200 UP` stub.
- D5 -- the IT must assert on **observable production
  surfaces**, not on `RestClient` internals or `SloEvaluator`
  internals: the
  `cortex_monitoring_probe_total{backend, outcome, service_id}`
  counter deltas (Part 17 allowlist), the
  `cortex_monitoring_slo_budget_remaining{service_id,
  slo_name}` + `cortex_monitoring_slo_burn_rate{service_id,
  slo_name}` gauge values, the wire shape of every GET probe
  (via WireMock `verify()`), and the SPI return envelope
  (`HealthSnapshot` + `SloSnapshot` outcome / reason fields
  per ADR-0044 D7 + ADR-0046 D5).
- D6 -- the IT MUST exercise every public SPI flow of every
  P8.0..P8.2 phase in one suite so a single regression
  surfaces all affected phases at once. 9 test methods
  organised as: 1 binder-gate proof + 1 metrics-bootstrap
  proof (probe-backend bean class + counter family present
  with all 6 outcome series); 3 P8.1 probe tests (healthy
  200 / degraded 503 / no-instance) + 1 wire-shape verify
  test; 2 P8.2 SLO tests (banded derivation tick gauges /
  no-data unknown) + 1 evaluator-as-bean proof.
- D7 -- credentials MUST be neutral per LD123. The Eureka
  service ID is `log-echo-service` (the IT pairs it with
  instance ID `log-echo-it`); a SECOND stubbed service ID
  `log-echo-slo-derive` (instance `log-echo-slo-derive-i1`)
  is used ONLY by the SLO budget-derivation test so its
  `budget=1.0 / burnRate=0.0` assertions are independent of
  any failure ticks the probe tests pile onto the primary
  service ID's counter family -- JUnit 5 method order is
  non-deterministic, so test-order independence requires
  each test that derives from the counter family to own its
  own service-tagged slice. The stub `DiscoveryClient`
  routes BOTH IDs to the same WireMock (mirrors a realistic
  operator deployment where multiple Eureka service IDs
  point at the same actuator surface). The WireMock base
  URL is dynamic.
- D8 -- the Postman collection MUST mirror the boot smoke's
  on-the-wire calls 1:1 (Health + Metrics-Baseline +
  Eureka-Probe wire contract + Prometheus rules/targets
  reachability + Metrics-After non-decreasing) so an
  operator can re-run the smoke contract from Newman without
  booting the PowerShell script.
- D9 -- ALL artifacts in this closer (smoke + Postman + IT +
  ADR + INDEX + CHANGELOG + README banner + DiscoveryClient
  autoconfig-exclude IT property + `SloEvaluator`
  follow-up issue + 4-file flip) MUST ship in ONE PR so the
  LD104 closer-pattern semantics close cleanly. The 5-leg
  gate (Leg A `mvn verify` + Leg B smoke + Leg C Newman +
  Leg D cross-phase IT + Leg E docs review) must all be
  GREEN at PR-merge time. The `SloEvaluator` prod fix is
  the ONE exception (D2b) -- the follow-up issue is filed
  in this PR, the actual fix ships in its own PR.

## Considered options

### For the cross-phase IT shape (D3 + D4 + D6)

1. **Singleton in-process `WireMockServer` started in a static
   block + ONE `@SpringBootTest` class with 9 test methods +
   `@DynamicPropertySource` NOT NEEDED (the stub
   `DiscoveryClient` already knows `WIRE_MOCK.port()` so the
   probe's `instance.uri` is computed at test time, not
   property-resolution time) + per-test
   `WIRE_MOCK.resetAll()` in `@BeforeEach`.** -- accepted.
   Pays the cold-start cost ONCE (~25-30 s on Windows)
   across all 9 tests, then reuses the Spring context +
   WireMock for every subsequent test. `resetAll()` clears
   stubs + journal between tests so each test is
   independent. 9 tests / single class / one context boot.
   Compile-clean under `mvn verify`.
2. **Per-test `@RegisterExtension WireMockExtension` +
   `@SpringBootTest`.** -- rejected. Same context-boot
   multiplier rationale as ADR-0043 option 2: would force
   Spring to rebuild the context per test, blowing the
   Failsafe budget.
3. **Real Eureka container (Testcontainers) + real target
   service container.** -- rejected. Multi-container cold-
   start cost is 60-120 s on Windows + Docker Desktop. The
   stub `DiscoveryClient` + in-process WireMock cover the
   same SPI surfaces in 27 s. Real Eureka belongs in
   `infra/eureka/` for full-stack manual exploration, not in
   the IT.

### For the DiscoveryClient triplet conflict (D2a)

A. **`spring.autoconfigure.exclude=` listing the three
   `DiscoveryClient` autoconfigurations
   (`EurekaClientAutoConfiguration`,
   `CompositeDiscoveryClientAutoConfiguration`,
   `SimpleDiscoveryClientAutoConfiguration`) in the IT's
   `properties=` block + `@Primary` on the stub bean.** --
   accepted. Surgical: only the IT context is affected,
   prod and per-phase tests are unchanged. The IT is the
   FIRST place these three autoconfigs collide with a
   `@TestConfiguration @Primary DiscoveryClient` stub
   because it's the FIRST `@SpringBootTest` in the module
   that publishes such a stub. Single-property change.
B. **Drop `@Primary` from the stub and rely on
   `@Bean(name="discoveryClient")` name-based override.** --
   rejected. Spring Cloud Commons does NOT register the
   composite + simple beans under a stable name; the
   composite bean is named after the underlying
   implementation. Name-based override is brittle across
   Spring Cloud minor versions.
C. **Disable Spring Cloud entirely via
   `spring.cloud.bootstrap.enabled=false`.** -- rejected.
   Disables too much; loses the rest of Spring Cloud's
   prod-parity wiring (e.g. `RestClient` HTTP/1.1 +
   timeout configurer). The IT is intentionally a
   prod-parity boot.
D. **Refactor `EurekaActuatorHealthProbe` to take an
   optional `Provider<DiscoveryClient>` and skip the probe
   gracefully when no client is available.** -- rejected.
   Prod-API change to work around a test-only collision;
   inverts the LD104 closer-pattern semantics (closers fix
   real prod bugs, not invent prod-API knobs to make tests
   easier).

### For the `SloEvaluator.fixedRateString` issue (D2b)

P. **Workaround in the IT only (use numeric millis
   `3600000` for `cortex.monitoring.slo.evaluation-
   interval`) + file follow-up prod-fix issue with a
   recommended `@Bean Long sloEvaluationIntervalMillis +
   SpEL @Scheduled(fixedRateString="#{@bean}")` design.** --
   accepted. Honours D2b: the closer ships green WITHOUT
   inventing a prod-scheduling-semantics change inside the
   closer PR. The follow-up bug-fix issue is filed in this
   PR so the audit trail is complete. Captures the gotcha
   as LD137 in memory.md for future Spring `@Scheduled`
   work.
Q. **Bake the prod fix into this closer PR.** -- rejected.
   Two design tradeoffs at play (`@Bean Long` vs
   `Duration` setter on `SloProperties` vs converter
   registration) that benefit from their own review.
   Bundling the fix into the closer would mix two
   concerns (closer triangulation + prod-API change) and
   make the closer PR harder to review.
R. **Switch the prod `SloProperties.evaluationInterval`
   property type from `Duration` to `Long` (millis) and
   keep `fixedRateString="${...}"` as-is.** -- rejected.
   Loses the `Duration` ergonomics in `application.yml`
   (operator writes `1h` / `30s`, not `3600000`). The
   follow-up issue's SpEL approach keeps the operator-
   facing `Duration` ergonomics intact.

### For the smoke / Postman shape (D8)

X. **Mirror the boot smoke 1:1 in Postman**: Health (probe
   backend == eureka-actuator) + Metrics-Baseline (three
   families present with HELP+TYPE + Part 17 tags) +
   Eureka-Probe wire contract (assert WireMock saw the
   expected GET shape) + Prometheus (rules loaded + targets
   UP) + Metrics-After (non-decreasing). 5 folders.
   `pm.execution.skipRequest()` when
   `prometheus_base_url` is empty (staging + prod env files
   leave it blank so the same collection runs offline against
   monitoring-only surfaces in those tiers). -- accepted.
Y. **Postman as a separate contract from the smoke.** --
   rejected. Same LD104 closer-pattern rationale as
   ADR-0037 + ADR-0043 option B: two contracts means two
   surfaces to keep in sync.
Z. **Newman invokes the SLO engine through a new REST
   controller exposed by log-monitoring-service.** --
   rejected. P8.2a is a closer, not a feature phase;
   introducing a new inbound REST controller would be a
   public API surface change that needs its own ADR + its
   own design. The cross-phase Failsafe IT is the source
   of truth for autowired SPI behaviour; Newman covers the
   HTTP-observable surfaces that DO exist (actuator +
   `/health/monitoring` + Prometheus exposition + alert
   rules + targets reachability). The future P8.2b SLO
   admin API is the right place to introduce a SLO-control
   REST surface.

## Decision outcome

Chosen options (combined): **option 1 (singleton WireMock +
ONE `@SpringBootTest` class with 9 tests) + option A
(`spring.autoconfigure.exclude` of the three
`DiscoveryClient` autoconfigurations + `@Primary` on the
stub) + option P (numeric-millis workaround in the IT +
filed follow-up bug-fix issue for the prod
`SloEvaluator.fixedRateString` defect) + option X (Postman
mirrors the smoke 1:1).**

### Implementation shape

- New cross-phase IT
  `log-monitoring-service/src/test/java/io/cortex/monitoring/closer/MonitoringProbeAndSloPipelineIT.java`:
  - `@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {"cortex.monitoring.probe.backend=eureka-actuator",
    "cortex.monitoring.slo.enabled=true",
    "cortex.monitoring.slo.backend=micrometer-derivation",
    "cortex.monitoring.eureka.request-timeout=30s",
    "cortex.monitoring.eureka.actuator-path=/actuator/health",
    "cortex.monitoring.slo.evaluation-interval=3600000",
    "eureka.client.enabled=false",
    "eureka.client.register-with-eureka=false",
    "eureka.client.fetch-registry=false",
    "spring.autoconfigure.exclude=...EurekaClientAutoConfiguration,
        ...CompositeDiscoveryClientAutoConfiguration,
        ...SimpleDiscoveryClientAutoConfiguration"})`
  - `@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)`
    + `private final` fields + package-private constructor for
    `ServiceHealthProbe probe`, `SloBudgetEngine engine`,
    `SloEvaluator evaluator`, `MonitoringMetrics metrics`,
    `MeterRegistry registry` (mirrors the
    `QuickwitCrossPhaseIT` pattern; Checkstyle Rule 14.1
    forbids field `@Autowired`).
  - `private static final WireMockServer WIRE_MOCK = new
    WireMockServer(WireMockConfiguration.options().
    dynamicPort());` started in a static block.
  - Nested `@TestConfiguration static class
    StubDiscoveryClientConfig` publishes `@Bean @Primary
    DiscoveryClient` that routes BOTH `TARGET_SERVICE_ID`
    + `DERIVE_SERVICE_ID` to a `DefaultServiceInstance`
    pointed at `WIRE_MOCK.port()` (D7 -- two-service
    routing).
  - `@BeforeEach resetWireMock()` -> `WIRE_MOCK.resetAll()` +
    re-install default `200 {"status":"UP"}` stub.
  - 9 test methods covering: probe backend bean class +
    counter family bootstrap + 3 probe outcome scenarios
    (HEALTHY / DEGRADED / UNREACHABLE-no-instance) + wire-
    shape verify + SLO banded-derivation + SLO no-data +
    evaluator-as-bean.

- New full-stack PowerShell smoke
  `scripts/smoke-p8-2a.ps1` (~340 lines):
  - Composes up `cortex-smoke-prometheus` (Prometheus
    2.55.1) via `docker compose -f infra/local/docker-
    compose.smoke.yml up -d prometheus`, waits for `/-/healthy`,
    then boots the actual service JAR with env bag
    (`CORTEX_MONITORING_PROBE_BACKEND=eureka-actuator,
    CORTEX_MONITORING_SLO_ENABLED=true,
    CORTEX_MONITORING_SLO_BACKEND=micrometer-derivation,
    EUREKA_CLIENT_ENABLED=false`), waits for
    `/actuator/health` UP.
  - Asserts `/actuator/health/monitoring`
    `details.backend=eureka-actuator`.
  - Scrapes `/actuator/prometheus` for the three families
    (`cortex_monitoring_probe_total`,
    `cortex_monitoring_slo_budget_remaining`,
    `cortex_monitoring_slo_burn_rate`) with `# HELP` +
    `# TYPE` headers + tag keys restricted to the Part 17
    allowlist.
  - Asserts Prometheus `/-/healthy` UP + `/api/v1/rules`
    returns the three CORTEX alert rules
    (`CortexSloFastBurn`, `CortexSloSlowBurn`,
    `CortexSloBudgetExhausted`) loaded + `/api/v1/targets`
    job `log-monitoring-service` is UP.
  - Teardown via `docker compose ... down` unless
    `-KeepInfra` flag is passed.

- New `postman/log-monitoring.postman_collection.json` (5
  folders -- Health, Metrics-Baseline, Eureka-Probe-Contract,
  Prometheus, Metrics-After) + three env files
  (`local`, `staging`, `prod`); `local` sets
  `prometheus_base_url=http://host.docker.internal:9090`;
  `staging` + `prod` leave it blank for offline replay.

- New `infra/local/prometheus.yml` (5 s scrape, `alerts/*.yml`
  rule_files, `scrape_configs.job=log-monitoring-service`
  pointed at `host.docker.internal:8098/actuator/prometheus`).

- `infra/local/docker-compose.smoke.yml` -- new `prometheus:`
  service (`prom/prometheus:v2.55.1`, container_name
  `cortex-smoke-prometheus`, port `9090`, volumes
  `./prometheus.yml + ./alerts` ro, `extra_hosts`
  `host.docker.internal:host-gateway`, healthcheck
  `wget -q -O- http://localhost:9090/-/healthy`).

- ADR-0047 (this doc) + INDEX bump 46 -> 47 + CHANGELOG
  `### Added` entry + service README banner flip
  `P8.0+P8.1+P8.2 SHIPPED` -> `P8.0+P8.1+P8.2+P8.2a
  SHIPPED` + new "Local smoke (P8.2a closer)" section in
  the service README.

### Implementation status (P8.2a)

- Cross-phase IT: ALL 9 tests pass under
  `mvnw verify -pl log-monitoring-service`.
- Boot smoke: `scripts/smoke-p8-2a.ps1 -KeepInfra` GREEN
  end-to-end against real Prometheus 2.55.1 + JVM on
  Windows.
- Newman: `newman run postman/log-monitoring.postman_
  collection.json -e postman/log-monitoring.postman_
  environment_local.json` GREEN against the still-running
  JVM.
- LD137 prod follow-up issue: filed (see issue ref in
  CHANGELOG / memory.md LD137).

## Consequences

### Positive

- Single place to assert end-to-end P8.0..P8.2 wiring under
  `mvn verify`; future P8 SPI additions plug into the same
  9-test class.
- Captures **LD137** (Spring `@Scheduled` `fixedRateString`
  does NOT accept Spring `Duration` strings; the converter
  registered for `@Value Duration` injection is NOT applied
  to the scheduling annotation) -- pattern recurs whenever
  a `@Scheduled` annotation reads a `Duration`-shaped
  property; the prescribed mitigation is `@Bean Long
  intervalMillis(Props p)` + SpEL bean reference
  `#{@intervalMillis}`.
- Reinforces LD131 (`@Lazy MonitoringMetrics` ctor param on
  `EurekaActuatorHealthProbe`) is the correct cycle break;
  the closer's `MonitoringMetrics` bootstrap loop survives
  P8.2's introduction of new beans without re-emerging.
- Postman collection covers both Eureka-probe wire contract
  AND Prometheus rule loading -- a sub-surface no per-phase
  test reaches.
- The `spring.autoconfigure.exclude` IT property documents
  WHICH autoconfigurations a `@TestConfiguration @Primary
  DiscoveryClient` must displace to win the `@Primary`
  contest, useful to any future monitoring/test that does
  the same.

### Negative

- Cross-phase IT pays a ~27 s cold-start cost on Windows
  per Failsafe run (singleton WireMock + single Spring
  context); acceptable since it runs ONCE per `mvn verify`
  of this module.
- The numeric-millis workaround for `cortex.monitoring.slo.
  evaluation-interval` in the IT looks ugly next to the
  `Duration`-typed `SloProperties.evaluationInterval` field
  it binds to; mitigated by an inline `// LD137 workaround`
  comment in the IT that points at the follow-up issue.
- The `spring.autoconfigure.exclude` IT property is a
  hard-coded class-name list that will break if Spring
  Cloud Commons renames or repackages the
  `CompositeDiscoveryClientAutoConfiguration` /
  `SimpleDiscoveryClientAutoConfiguration` classes;
  mitigated by version pinning on Spring Cloud
  `2023.0.4` (LD133-style version-pin discipline) and the
  fact that Spring Cloud BOM upgrades are gated through a
  separate ADR.

### Neutral

- Three new top-level artefacts on disk
  (`scripts/smoke-p8-2a.ps1` +
  `postman/log-monitoring.postman_collection.json` +
  `infra/local/prometheus.yml`); each follows the existing
  per-service naming conventions established by ADR-0037
  + ADR-0043.

## References

- ADR-0044: log-monitoring-service P8.0 scaffold + probe SPI
  (the binder-gate pattern this closer's
  `cortex.monitoring.probe.backend=eureka-actuator`
  property flips).
- ADR-0045: P8.1 `EurekaActuatorHealthProbe` adapter
  (the `@Lazy MonitoringMetrics` cycle break per LD131 that
  the cross-phase IT validates survives P8.2's bean
  additions).
- ADR-0046: P8.2 SLO budget engine
  (the binder-gate pattern this closer's
  `cortex.monitoring.slo.backend=micrometer-derivation` +
  `cortex.monitoring.slo.enabled=true` properties flip;
  ADR-0046 D5 OUTCOME_HEALTHY/OUTCOME_UNKNOWN no-data
  contract that the SLO derivation tests assert).
- ADR-0043: log-indexer-service P7.1a cross-phase closer
  (template for singleton-WireMock + `@TestConstructor(ALL)`
  + `@SpringBootTest` cross-phase IT shape).
- ADR-0037: log-remediation-service P6.1a cross-phase closer
  (template for boot-smoke + Postman collection + LD104
  closer-pattern split).
- LD104 (memory.md): per-phase Leg A only; ALL closer
  artefacts ship in ONE closer PR.
- LD123 (memory.md): cold-start budget + neutral test
  credentials.
- LD131 (memory.md): `@Lazy <X>Metrics` ctor param breaks
  `MetricsBootstrap <-> AutowiredSPIAdapter` cycle.
- LD137 (memory.md, this closer): Spring `@Scheduled`
  `fixedRateString` does NOT accept `Duration` strings;
  prescribed mitigation is `@Bean Long` + SpEL.
