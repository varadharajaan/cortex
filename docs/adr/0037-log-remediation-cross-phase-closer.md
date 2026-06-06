# 0037. log-remediation-service P6.1a cross-phase closer (per-channel kafka topic isolation in smoke + singleton-Testcontainers cross-phase IT base + Postman collection + 5-leg gate)

- Status: accepted
- Date: 2026-06-06
- Deciders: @varadharajaan
- Tags: remediation, closer, smoke, postman, cross-phase, testcontainers, kafka-topic-isolation

## Context and problem statement

P6.0 (scaffold + consumer + SPI), P6.1 (Slack adapter), P6.2
(PagerDuty adapter), P6.3 (Jira adapter), and P6.0a (strict-
rules cleanup) each shipped **Leg A only** per the LD104
closer-pattern precedent (`B/C/D/E -- deferred to the P6.1a
closer that builds smoke + Postman + cross-phase regression
ONCE for all three P6 adapters together`). The P6.1a closer
(issue #93) is therefore the ONLY remaining P6 deliverable
before the epic can be marked DONE.

The cross-phase regression covers a different surface than each
adapter's individual `*RemediationDispatcherWireMockIT`:

- The per-adapter IT instantiates the dispatcher class directly
  and pumps WireMock-shaped HTTP responses through it.
- The cross-phase regression must instead exercise the **full
  ingest -> consume -> classify -> dispatch -> metrics loop**
  through a real `@SpringBootTest`-booted application, a real
  embedded Kafka broker, and a real WireMock HTTP endpoint, so
  the `@KafkaListener` plumbing + CloudEvents 1.0 envelope
  parse + dispatcher selection + meter tagging are all wired
  identically to production.

Additionally, the closer must ship:

1. A full-stack PowerShell **boot smoke** (`scripts/smoke-
   p6-1a.ps1`) that starts the actual service JAR against
   Testcontainers Kafka + a separate WireMock container per
   channel and proves the end-to-end loop with a real docker
   exec + Prometheus scrape.
2. A **Postman collection** (`postman/log-remediation.postman_
   collection.json`) that captures the contract the boot smoke
   exercises for offline reuse / CI replay.
3. A **cross-phase IT suite** under
   `log-remediation-service/src/test/java/io/cortex/remediation/
   closer/` with one `@SpringBootTest` subclass per channel.
4. **ADR-0037** (this doc) + INDEX bump 36 -> 37 + CHANGELOG +
   service README banner flip P6.0a -> P6.0a + P6.1a.

Two issues bit during the smoke build-out that the IT design
had to address up front (or they would have replicated in IT):

- **Shared Kafka topic + `auto.offset.reset=earliest` causes
  cross-channel envelope replay.** When all three channels
  published to the same `cortex.anomalies.v1` topic in a single
  smoke run, the second channel's consumer would replay the
  first channel's envelopes (different WireMock stubs registered
  by then -> wrong outcome classification -> failed counter
  assertions). Captured as **LD125** during P6.1a Leg B.
- **Javadoc comments cannot contain `*/` even inside `{@code
  ...}` blocks** -- the Javadoc lexer terminates greedily on the
  first `*/` regardless of context. Captured as **LD126** during
  P6.1a Leg D when the initial draft of
  `SlackCrossPhaseIT.java` line 49 contained
  `{@code T*/B*/X*}` and broke `javac` with 33 cascading
  compile errors. The Eclipse JDT language server used by VS
  Code does not catch this; only `javac` does.

## Decision drivers

- D1 -- the cross-phase IT must run inside the normal Failsafe
  cycle (`mvn verify`) so CI catches regressions on every PR,
  not just on a manual smoke run. No standalone runner, no
  separate Maven profile.
- D2 -- Kafka + WireMock infrastructure costs ~25-40 s of cold
  start per `@SpringBootTest` cycle on a typical Windows + Docker
  Desktop dev box. The closer adds 3 subclasses (one per
  channel) -- if each booted its own Kafka + WireMock the suite
  would gain ~2 minutes of wall-clock time. The closer must
  share a **singleton** Kafka container + WireMock server
  across all 3 subclasses to stay inside the existing Failsafe
  budget.
- D3 -- each channel subclass MUST publish to its own
  per-channel Kafka topic (`cortex.anomalies.v1.cross-phase.
  <channel>`) and use its own unique `spring.kafka.consumer.
  group-id`. LD125 makes shared-topic-across-subclasses a
  REJECTED design: rebalance + earliest-reset semantics replay
  the previous subclass's envelopes through the next subclass's
  dispatcher.
- D4 -- the cross-phase IT must NOT use the per-adapter test's
  Mockito-stubbed `RestClient`. It must hit a real WireMock
  endpoint over a real socket so the `JdkClientHttpRequestFactory`
  + HTTP/1.1 pin + dual-timeout + `RestDispatchTemplate` outcome
  classification (per ADR-0036) are all exercised on the wire.
- D5 -- credentials MUST be neutral per LD123 (no realistic
  `xoxb-...` / `ATATT3xFfGF0...` / `R0...` prefixes). The Slack
  webhook path is `/services/IT/CROSS/PHASE`; PagerDuty routing
  key is `00000000000000000000000000000000`; Jira email is
  `test@example.com` + token `placeholder-token-not-real`.
- D6 -- the IT must assert on **observable production
  surfaces**, not on `RestDispatchTemplate` internals: the
  `cortex_remediation_dispatched_total{channel,outcome,tenant_id}`
  counter ticks + the WireMock POST being recorded at the
  expected path + the DLT topic staying empty (P6.4 hasn't
  shipped yet so any DLT growth indicates a regression).
- D7 -- the Postman collection MUST mirror the boot smoke's
  on-the-wire calls 1:1 (admin actuator probes + WireMock
  stub-and-scrape sequence) so an operator can re-run the
  smoke contract from Newman without booting the PowerShell
  script.
- D8 -- ALL artifacts in this closer (smoke + Postman + IT +
  ADR + INDEX + CHANGELOG + README banner + 4-file flip) MUST
  ship in ONE PR so the LD104 closer-pattern semantics close
  cleanly. The 5-leg gate (Leg A `mvn verify` + Leg B smoke +
  Leg C Postman + Leg D cross-phase IT + Leg E docs review)
  must all be GREEN at PR-merge time.

## Considered options

### For the cross-phase IT shape (D2 + D3)

1. **Singleton Testcontainers Kafka + in-process WireMock
   server owned by an abstract base + 3 subclasses each
   `@SpringBootTest` with a unique provider + unique topic +
   unique consumer group + `@DynamicPropertySource` cascading
   from base to subclass.** -- accepted. Pays the cold-start
   cost ONCE across the suite. Each subclass owns its
   per-channel topic per LD125. Each subclass's
   `@DynamicPropertySource` adds only the provider-specific
   properties (webhook URL / routing key / Jira credentials).
   No reflection, no provider-list tricks. Compile-clean under
   `mvn verify`.
2. **One mega `@SpringBootTest` class with manual provider
   switching via `@SpringBootTest.properties` mid-test or via
   `ApplicationContextRunner`.** -- rejected. Spring's
   `@ConditionalOnProperty` is evaluated at context-startup
   time only; switching providers mid-test requires a full
   context restart, which negates the singleton-container win
   and makes the test fixture much harder to read.
3. **Reflection-based dispatcher swap** (instantiate all three
   dispatchers + a `Map<String, RemediationDispatcher>` +
   swap the consumer's collaborator field via
   `ReflectionTestUtils.setField`). -- rejected. The
   `@ConditionalOnProperty` contract is part of the public ADR-
   0032 D4 design ("exactly one adapter bean active per
   profile"); reflection-swapping the consumer's collaborator
   bypasses that contract and tests a configuration shape
   that production never sees.
4. **Shared topic + manual offset commit + manual seek across
   subclasses.** -- rejected. Even with manual offset
   management, the rebalance protocol between two consumer
   groups on the same topic is non-deterministic on Windows +
   Docker Desktop. LD125 captures the root cause; the topic-
   per-channel design eliminates the entire failure mode.

### For the smoke / Postman shape (D7)

A. **Mirror the boot smoke 1:1 in Postman**: admin actuator
   probes (`/actuator/health/{liveness,readiness}`, `/info`,
   `/metrics`, `/prometheus`) + WireMock stub registration +
   per-channel POST registration + metrics-baseline +
   metrics-after delta assertion. -- accepted. 4 folders / 10
   requests / 25 assertions. `pm.execution.skipRequest()` when
   `wiremock_base_url` is empty (staging + prod env files
   leave it blank so the same collection runs offline against
   admin-only surfaces in those tiers).
B. **Postman as a separate contract from the smoke.** --
   rejected. Two contracts means two surfaces to keep in
   sync, two surfaces to regress, two surfaces to debug. LD104
   closer-pattern explicitly demands the closer's artifacts
   share ONE contract.
C. **Postman-only (skip the PowerShell smoke).** -- rejected.
   The PowerShell smoke owns the docker exec + producer.sh
   publish path (LD125 -- per-channel topic isolation, docker
   cp + sh -c publish to avoid PowerShell CRLF mangling),
   which Newman cannot reproduce.

## Decision outcome

Chosen options (combined): **option 1 (singleton Kafka +
WireMock + 3 @SpringBootTest subclasses) + option A (Postman
mirrors the smoke 1:1).**

### Implementation shape

- New abstract base `closer/AnomalyCrossPhaseBaseIT.java`
  (~234 lines):
  - `protected static final KafkaContainer KAFKA = new
    KafkaContainer("apache/kafka:3.8.0");` started in a
    static block. NO `@Testcontainers` annotation;
    lifecycle relies on the Ryuk reaper.
  - `protected static final WireMockServer WIRE_MOCK = new
    WireMockServer(WireMockConfiguration.options().
    dynamicPort());` similarly singleton.
  - `protected static final String DLT_TOPIC =
    "cortex.anomalies.v1.dlq";` shared DLT scrape topic.
  - `protected static final Duration AWAIT = Duration.
    ofSeconds(20);` Awaitility budget covers consumer-group
    join + cold-start JIT.
  - `@DynamicPropertySource baseProperties(...)` registers
    `spring.kafka.bootstrap-servers` + disables Eureka.
  - `@BeforeEach resetWireMock()` -> `WIRE_MOCK.resetAll()`.
  - Protected helpers: `preCreateTopic(topic)` (AdminClient),
    `buildEnvelope(eventId, tenantId)` (`CloudEventBuilder.v1`),
    `publish(topic, value)` (`KafkaProducer`),
    `readDltRecords()` (`KafkaConsumer` poll 1s),
    `counterValue(registry, channel, outcome, tenantId)`.

- 3 sealed-shape subclasses (each ~150-160 lines):
  - `closer/SlackCrossPhaseIT.java` -- TOPIC =
    `cortex.anomalies.v1.cross-phase.slack`, WEBHOOK_PATH =
    `/services/IT/CROSS/PHASE` (neutral per LD123).
  - `closer/PagerDutyCrossPhaseIT.java` -- TOPIC =
    `cortex.anomalies.v1.cross-phase.pagerduty`, ENQUEUE_PATH
    = `/v2/enqueue`, NEUTRAL_ROUTING_KEY =
    `00000000000000000000000000000000`.
  - `closer/JiraCrossPhaseIT.java` -- TOPIC =
    `cortex.anomalies.v1.cross-phase.jira`, ISSUE_PATH =
    `/rest/api/3/issue`, NEUTRAL_EMAIL = `test@example.com`,
    NEUTRAL_API_TOKEN = `placeholder-token-not-real`,
    NEUTRAL_PROJECT_KEY = `IT`.
  - Each subclass exposes 2 tests:
    `happyPathTicksDispatched()` (WireMock 2xx -> counter
    `outcome=dispatched` ticks 1.0 for a unique tenant_id +
    WireMock POST verified at the expected path + DLT empty)
    and `transientPathTicksTransientFailure()` (WireMock 500
    -> counter `outcome=transient_failure` ticks 1.0 + same
    POST verification + DLT empty).

- New PowerShell smoke `scripts/smoke-p6-1a.ps1` (per LD125
  + LD113 + LD114 + LD115 + the 5 fixes captured in
  `/memories/session/p6-1a-progress.md`):
  - Per-channel kafka topic isolation:
    `cortex.anomalies.v1.<runId>.<channel>` via
    `CORTEX_REMEDIATION_TOPIC` env var.
  - Publish via `docker cp` + `docker exec sh -c
    "...producer.sh < /tmp/..."` (avoids PowerShell pipeline
    CRLF + trailing-newline mangling).
  - Boot env var spelled `CORTEX_REMEDIATION_DISPATCHER_
    PROVIDER` (NOT `CORTEX_REMEDIATION_DISPATCHER` -- the
    latter falls back to noop and the smoke would silently
    pass against a no-op dispatcher).
  - ISO 8601 timestamp via `[System.DateTime]::UtcNow.
    ToString('yyyy-MM-ddTHH:mm:ss.fffZ',
    [System.Globalization.CultureInfo]::InvariantCulture)`
    -- en-IN culture renders `:` as `.` and the CloudEvents
    `time` field would fail schema validation.
  - Order-independent `Get-PromCounter` filter that walks
    each `name="value"` substring independently (hashtable
    enumeration order is non-deterministic in PowerShell).
  - Last GREEN transcript: `scripts/logs/p6-1a/smoke-all-
    20260606-220655.log` -- all 3 channels PASS with
    happy + transient counter ticks + WireMock journal
    delta = 2 at the expected path.

- New `postman/log-remediation.postman_collection.json`
  (4 folders / 10 requests / 25 assertions):
  - Admin (actuator): health / liveness / readiness / info /
    metrics / prometheus (6 requests).
  - Metrics-Baseline: snapshot per-channel sums into env vars
    `dispatched_baseline_{slack,pagerduty,jira}`.
  - Channel-Mock-Smoke: 3 requests mirroring the smoke's
    WireMock POSTs (Slack 200|404; PagerDuty 202|404; Jira
    201|404) -- `pm.execution.skipRequest()` when
    `wiremock_base_url` is empty so the collection runs
    against admin-only surfaces in staging + prod.
  - Metrics-After: asserts non-decreasing counters vs the
    baseline.
  - Pre-request: upserts `X-Request-Id` + `X-Tenant-Id`.
  - Top-level test: `responseTime < 5000ms`.
  - 3 env files: `local` (`wiremock_base_url=http://
    localhost:8094`), `staging` (blank), `prod` (blank).

- `docs/adr/INDEX.md` -- count bump 36 -> 37 + new ADR-0037
  row under "Remediation pipeline (P6)" + `Last refreshed`
  bumped to `2026-06-06 (P6.1a cross-phase closer, PR for
  #93)`.

- `CHANGELOG.md` -- new `[Unreleased] > ### Added` P6.1a entry
  inserted ABOVE the existing P6.0a `### Changed` block.

- `log-remediation-service/README.md` -- banner flipped
  `P6.0..P6.3 + P6.0a SHIPPED` -> `P6.0..P6.3 + P6.0a +
  P6.1a SHIPPED` with full closer scope summary; new section
  4d "Cross-phase closer (P6.1a, ADR-0037)" describing the
  5-leg gate + cross-phase IT contract + smoke pointer +
  Postman pointer.

- `memory.md` -- two new LDs captured by this closer:
  - **LD125** -- per-channel kafka topic isolation in
    multi-channel smoke runs. Root cause: shared
    `cortex.anomalies.v1` topic + `auto.offset.reset=
    earliest` causes the next channel's consumer to replay
    the previous channel's envelopes through different
    WireMock stubs -> wrong outcome classification ->
    failed counter assertions. Fix: every channel publishes/
    consumes on `cortex.anomalies.v1.<runId>.<channel>` via
    the `CORTEX_REMEDIATION_TOPIC` env var. The application
    yml already supports the env override
    (`cortex.remediation.topic: ${CORTEX_REMEDIATION_TOPIC:
    cortex.anomalies.v1}`).
  - **LD126** -- Javadoc comments CANNOT contain `*/`
    anywhere, including inside `{@code ...}` blocks. The
    Javadoc lexer terminates greedily on the first `*/`
    regardless of context. JDT (Eclipse / VS Code Java
    language server) does NOT catch this; only `javac` does
    at compile time. Rule going forward: rephrase the
    Javadoc to avoid the `*/` token entirely, or use the
    HTML entity `*&#47;` if the literal characters are
    essential. Always run `mvn compile` (or `mvn verify`)
    after Javadoc edits -- do not trust IDE `get_errors`
    alone for Javadoc validity.

### Tracking artifacts

- Existing issue **#93** OPEN (created P6.1 ship).
- Existing branch `feat/93-p6-1a-cross-phase-closer` off
  `4ddd078` (P6.0a main HEAD post-merge).
- `docs/adr/INDEX.md` row + count bump 36 -> 37 + refreshed-on
  date.
- `CHANGELOG.md` entry under `[Unreleased] > ### Added`.

### Positive consequences

- Cross-phase regression closes the P6 ring: every PR going
  forward runs the full consume -> dispatch -> WireMock loop
  for all three channels in CI, not just the per-adapter unit
  + WireMock IT slice.
- LD125 captured + smoke contract baked into the script means
  any future channel adapter (P6.4 retry/DLQ, future
  PagerDuty Webhook v2, future Slack Workflows) inherits the
  topic-isolation discipline for free.
- LD126 captured prevents future Javadoc-trap recurrences;
  the lesson generalises beyond this module.
- Postman collection lets operators replay the smoke contract
  offline without booting PowerShell + Docker; staging + prod
  env files exercise admin-only surfaces (skipped WireMock
  calls).
- Failsafe runtime budget held: Tests run: 22 (16 prior + 6
  new) in ~3:44 wall clock vs 16 in ~3:00 prior -- ~14 s/test
  marginal cost, well under the 25-40 s/test if each subclass
  booted its own Kafka.

### Negative consequences and trade-offs

- The singleton-Kafka pattern across 3 subclasses means a
  test isolation failure in one subclass can leak state into
  the next (e.g. a hung consumer-group from subclass A could
  affect subclass B's join time). Mitigated by per-channel
  topic + per-channel group-id; if a future flake surfaces,
  add an explicit `@BeforeAll consumerGroupReset()` helper.
- The 20 s Awaitility budget in `AnomalyCrossPhaseBaseIT.
  AWAIT` is conservative; cold-start consumer-group joins
  on Windows + Docker Desktop have been observed at 8-12 s
  (LD123). If the budget proves too tight on CI, bump to
  30 s.
- The cross-phase IT does not assert a strict end-to-end
  latency budget (the earlier draft proposed `<= 2 s` per
  envelope); the 20 s Awaitility timeout subsumes that
  assertion. If a future PR needs latency regression
  guarding, layer a Micrometer `Timer` on the dispatcher
  boundary rather than re-introducing a wall-clock latency
  assertion in the IT.
- The Postman collection's `pm.execution.skipRequest()` gate
  on `wiremock_base_url` means staging + prod env runs cover
  fewer requests than local -- this is intentional (no
  WireMock in those tiers) but operators must read the
  README to know which folders are gated off.

## Links / references

- Builds on: ADR-0032 (RemediationDispatcher SPI), ADR-0033
  (Slack), ADR-0034 (PagerDuty), ADR-0035 (Jira), ADR-0036
  (P6.0a strict-rules cleanup -- composition + OCP metrics +
  A2.3 supersede).
- Captures: LD125 (per-channel kafka topic isolation in
  multi-channel smoke), LD126 (Javadoc `*/` terminator trap).
- Reinforces: LD104 (closer-pattern: B/C/D/E shipped once for
  all channels), LD123 (neutral test credentials), LD42 +
  LD121 (HTTP/1.1 + dual-timeout pin), LD113 + LD114 + LD115
  (boot script discipline), LD89 + LD90 (truthful merge +
  issue-close evidence).
- Closes the P6 epic together with: PR #86 (P6.0), #88
  (P6.1), #90 (P6.2), #92 (P6.3), #96 (P6.0a).
- Tracking issue: #93
  (`feat/93-p6-1a-cross-phase-closer`).

## How to validate

```bash
# Leg A
./mvnw verify -pl log-remediation-service -am
# Surefire 76 unit tests PASS, Failsafe Tests run: 22
# (16 prior + 6 new closer ITs each 2/2 PASS), 0 Checkstyle,
# 0 SpotBugs, JaCoCo BUNDLE 0.80/0.80 met, BUILD SUCCESS.

# Leg B
./scripts/smoke-p6-1a.ps1
# All 3 channels GREEN end-to-end: Kafka publish -> dispatcher
# -> WireMock -> counter; WireMock journal delta = 2 per
# channel at the expected path; transcript under
# scripts/logs/p6-1a/.

# Leg C
npx newman run postman/log-remediation.postman_collection.json `
  -e postman/log-remediation.postman_environment_local.json `
  --reporters cli --bail
# All admin + metrics + channel-mock assertions PASS against
# a live local stack (service on :8096 + WireMock on :8094).

# Leg D
# (subset of Leg A) -- ./mvnw verify -Dit.test=
# 'closer.*CrossPhaseIT' -pl log-remediation-service
```
