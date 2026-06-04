# 0032. log-remediation-service `RemediationDispatcher` SPI + per-channel adapter contract

- Status: accepted
- Date: 2026-06-04
- Deciders: @varadharajaan
- Tags: remediation, dispatcher, kafka, cloudevents, slack, pagerduty, jira

## Context and problem statement

P6 introduces the `log-remediation-service`: a new Spring Boot
module that consumes confirmed anomaly verdicts off
`cortex.anomalies.v1` (the dedicated handoff topic ADR-0031 / P5.4
created on the processor side) and fans them out to operator-facing
channels (Slack, PagerDuty, Jira) so the platform actually closes
the loop on AI-detected incidents.

P6.0 ships the scaffold: the new module, its Kafka consumer, the
CloudEvents envelope parser, a metric counter, and a stub
dispatcher. The actual Slack / PagerDuty / Jira adapters land in
P6.1 (#85), P6.2 (#86), P6.3 (#87). To keep those follow-up PRs
narrow we need to lock the dispatcher contract NOW, before any
real adapter exists: a Service Provider Interface
(`RemediationDispatcher`) the consumer talks to, a verdict record
(`DispatchResult`) the metric layer reads, and a binder-gate
property (`cortex.remediation.dispatcher.provider`) that selects
exactly one adapter implementation per profile.

The open question this ADR settles is the SHAPE of that SPI:
synchronous vs reactive, single-bean vs multi-bean fan-out,
exception-throwing vs verdict-returning, and how the channel /
outcome / tenant tags reach the Micrometer counter without
exploding cardinality. We also need to decide whether the P6.0
scaffold ships with a no-op default or whether the consumer
short-circuits when no adapter is configured.

## Decision drivers

- D1 -- the consumer pipeline MUST stay synchronous on the Kafka
  poll thread and use manual `Acknowledgment.acknowledge()` so the
  Kafka offset IS the durability mechanism (mirrors ADR-0028 +
  ADR-0031 + LD117 -- a Kafka -> Kafka relay needs no outbox).
- D2 -- the dispatch interface MUST take an already-decoded typed
  `AnomalyEvent` (NOT a raw `byte[]` or `CloudEvent`) so the
  per-channel adapters never re-implement envelope parsing.
- D3 -- the verdict record MUST carry exactly three bounded
  enum-like strings (`channel`, `outcome`, `reason`) so the
  `cortex.remediation.dispatched_total{channel,outcome,tenant_id}`
  counter stays inside the Part 17 tag-key allowlist with bounded
  cardinality (Slack/PD/Jira/noop x 4 outcomes x N tenants =
  predictable cap).
- D4 -- exactly ONE adapter bean MUST be active in any given
  profile, selected by `cortex.remediation.dispatcher.provider`
  via `@ConditionalOnProperty`, so the consumer never has to
  multiplex over a list of dispatchers (P6.1/2/3 are AND -- not
  XOR -- but each runs in its own deployment via its own profile).
- D5 -- the default (no property set) MUST be a no-op dispatcher
  that returns `DispatchResult.skipped(...)` so the P6.0 scaffold
  boots green without ANY downstream HTTP dependency (no Slack
  webhook URL, no PagerDuty integration key, no Jira API token).
- D6 -- the dispatcher SPI MUST NOT throw on transient downstream
  failures (Slack 429, PagerDuty 5xx, Jira 503); a verdict with
  `dispatched=false` + `outcome="transient_failure"` ticks the
  failed-outcome counter without stalling the offset. Throwing is
  reserved for adapter contract violations (null arg, illegal
  config) and is logged + counted by the consumer's catch-all.
- D7 -- the SPI shape MUST be agnostic to the future P6.4 DLQ +
  retry budget design so we don't have to break the contract when
  retries land. Returning a verdict (instead of throwing) reserves
  that future axis.

## Considered options

1. **Synchronous `DispatchResult dispatch(AnomalyEvent)` SPI with
   exactly one bean per profile selected by
   `cortex.remediation.dispatcher.provider` +
   `@ConditionalOnProperty`; default `NoopRemediationDispatcher`
   gated by `matchIfMissing=true`.** -- accepted.
2. **Reactive `Mono<DispatchResult> dispatch(AnomalyEvent)` SPI
   backed by Spring WebFlux + Reactor.** -- rejected: the rest of
   the service is servlet (`spring-kafka` + `spring-boot-starter`)
   per ADR-0014 + ADR-0028; introducing Reactor here forces a
   second concurrency model on a service that runs one Kafka
   consumer thread per partition, with zero throughput benefit
   for outbound HTTP calls that are already orders of magnitude
   faster than the upstream Kafka poll cadence. The same
   resilience primitives (Resilience4j `CircuitBreaker` +
   `TimeLimiter`) work identically on a blocking call.
3. **Native channel SDKs (`com.slack.api:slack-app-backend`,
   `io.pagerduty:pagerduty-events-java`, `com.atlassian.jira:jira-rest-java-client`).**
   -- rejected: the three SDKs pull in incompatible dependency
   trees (Slack SDK pins `okhttp 4.x`, the Jira REST client
   pulls Atlassian-internal Guava 18, PagerDuty's Java client is
   essentially abandonware). The platform already pins HTTP/1.1
   `JdkClientHttpRequestFactory` per LD42; a plain
   `RestClient` + 4 lines of JSON per adapter is smaller,
   easier to audit, and avoids the dependency-convergence
   failures the parent POM Enforcer rule would catch.
4. **Single shared `HttpRemediationDispatcher` parameterized by
   channel config (URL, auth header, body template).** --
   rejected: the three downstream APIs have meaningfully
   different request shapes (Slack = JSON blocks; PagerDuty =
   Events API v2 envelope with `routing_key` + `dedup_key`;
   Jira = REST issue create with project + issuetype +
   description ADF). Folding them behind one bean either bloats
   the config (yaml-as-code for each channel's payload) or
   forces a template-string indirection that's strictly harder
   to test than three small adapter classes. Separation per
   ADR-0030 (sinks) is the established pattern.
5. **Server-side bus -- republish to per-channel topics
   (`cortex.remediation.slack.v1`, `cortex.remediation.pagerduty.v1`,
   `cortex.remediation.jira.v1`) consumed by separate workers.**
   -- rejected: P6 is a leaf in the data flow. There's no
   downstream consumer of the dispatch verdict other than the
   counter + log line; a per-channel topic adds a third Kafka
   broker hop, a new offset commit boundary, and another set of
   DLQ topics for the same outcome, with no testability win
   over an in-process SPI.
6. **Spring AI 1.0 tools framework -- expose each channel as a
   `FunctionCallback` and have the classifier itself decide which
   tool to call.** -- rejected: the AI provider is already
   bounded to `LogEventClassifier` per ADR-0029; entangling
   dispatcher selection with the classifier blurs the boundary
   that LD42 + ADR-0029 explicitly drew (the model produces a
   verdict, deterministic code routes it). Tool-calling also
   removes the operator's ability to flip a dispatcher in
   prod via a config property without redeploying the classifier.

## Decision outcome

Chosen option: **option 1, synchronous
`DispatchResult dispatch(AnomalyEvent)` SPI with exactly one bean
per profile selected by `cortex.remediation.dispatcher.provider` +
`@ConditionalOnProperty`; default `NoopRemediationDispatcher` gated
by `matchIfMissing=true`.**

### Implementation shape

- New SPI `io.cortex.remediation.dispatch.RemediationDispatcher`:
  ```
  public interface RemediationDispatcher {
      DispatchResult dispatch(AnomalyEvent event);
  }
  ```
  Single method, single argument, single return. Contract:
  - The argument is the typed `AnomalyEvent` already produced by
    `AnomalyEnvelopeParser.parse(byte[])`; the dispatcher never
    re-parses the CloudEvent (D2).
  - The return value is NEVER `null`; implementations use
    `DispatchResult.skipped("reason")` to express "no action"
    (D5 + D6).
  - Implementations MUST NOT throw on transient downstream
    failures; surface them as
    `outcome="transient_failure"` instead so the metric ticks
    + the offset moves (D6). The consumer's catch-all will only
    fire on dispatcher contract violations (D6).

- New value object `io.cortex.remediation.dispatch.DispatchResult`:
  ```
  public record DispatchResult(boolean dispatched,
                               String channel,
                               String outcome,
                               String reason) {
      public static final String CHANNEL_NOOP        = "noop";
      public static final String OUTCOME_SKIPPED     = "skipped";
      public static final String OUTCOME_DISPATCHED  = "dispatched";
      public static final String OUTCOME_PERMANENT_FAILURE = "permanent_failure";
      public static final String OUTCOME_TRANSIENT_FAILURE = "transient_failure";
      public static DispatchResult skipped(String reason) { ... }
  }
  ```
  - `channel` is bounded to the four constants `slack`,
    `pagerduty`, `jira`, `noop` (D3).
  - `outcome` is bounded to the four constants
    `dispatched`, `skipped`, `transient_failure`,
    `permanent_failure` (D3).
  - `reason` is free-form and surfaces ONLY in the consumer log
    line + the future P6.4 DLQ envelope header
    `x-dispatch-reason`. It is NEVER emitted as a Micrometer
    tag (D3 + Part 17 allowlist).

- Default implementation
  `io.cortex.remediation.dispatch.NoopRemediationDispatcher`:
  ```
  @Component
  @ConditionalOnProperty(
      prefix = "cortex.remediation.dispatcher",
      name = "provider",
      havingValue = "noop",
      matchIfMissing = true)
  public class NoopRemediationDispatcher implements RemediationDispatcher {
      @Override
      public DispatchResult dispatch(AnomalyEvent event) {
          return DispatchResult.skipped(
              "noop dispatcher (P6.0 scaffold); real adapters land in P6.1..P6.3");
      }
  }
  ```
  `matchIfMissing=true` per D5: the scaffold boots green without
  any property set so local-dev `mvn spring-boot:run` works
  immediately after `git pull` without env-var ceremony.

- Future per-channel adapters (P6.1..P6.3) follow the same shape:
  ```
  @Component
  @ConditionalOnProperty(
      prefix = "cortex.remediation.dispatcher",
      name = "provider",
      havingValue = "slack")
  public class SlackRemediationDispatcher implements RemediationDispatcher {
      // RestClient + Slack webhook URL + Resilience4j @CircuitBreaker
      // + try/catch -> DispatchResult.{dispatched(...) | transient(...) | permanent(...)}
  }
  ```
  Same shape for `pagerduty` (Events API v2) and `jira` (REST
  issue create). Each adapter ships with its own per-channel
  property block (`cortex.remediation.slack.webhook-url`, etc.)
  so the P6.0 `application.yml` stays minimal.

- Consumer wiring -- `AnomalyConsumer` already takes
  `RemediationDispatcher` as a ctor argument; the
  `@ConditionalOnProperty` gate picks exactly one impl, Spring
  injects it. The consumer's pipeline:
  ```
  parser.parse(payload)        // P6.0 envelope + data decode
  -> dispatcher.dispatch(event) // SPI -- exactly one impl active
  -> metrics.incDispatched(    // channel + outcome from verdict;
       result.channel(),       // tenant_id from parsed event
       result.outcome(),
       event.tenantId())
  -> ack.acknowledge()         // offset moves AFTER dispatch
  ```
  A `RuntimeException` from the dispatcher (contract violation,
  D6) is caught + logged at ERROR + the offset is committed; the
  bad message is NOT re-polled. This intentionally departs from
  the P5 LogEventConsumer's redeliver-on-failure stance because
  the dispatcher's transient failures already round-trip through
  `outcome="transient_failure"`; what's left for the catch-all
  is genuinely poisonous input that no redelivery will help.

- Metrics surface -- `RemediationMetrics` exposes one counter
  family `cortex.remediation.dispatched_total` tagged with
  `channel`, `outcome`, `tenant_id` (Part 17 allowlist).
  Bootstrap-registered at construct time with
  `channel=unknown, outcome=unknown, tenant_id=unknown` per
  LD106 + LD112 so the `/actuator/prometheus` scrape sees the
  counter family BEFORE any anomaly ticks; dashboards never
  flatline.

- Configuration -- `application.yml` (main + test, per LD100):
  ```
  cortex:
    remediation:
      topic: ${CORTEX_REMEDIATION_TOPIC:cortex.anomalies.v1}
      dlq:
        topic: ${CORTEX_REMEDIATION_DLQ_TOPIC:cortex.anomalies.v1.dlq}
      dispatcher:
        provider: ${CORTEX_REMEDIATION_DISPATCHER:noop}
  ```
  The DLQ topic property is declared in P6.0 but NOT wired
  (P6.4 lands the writer). Declaring it now keeps the
  ops-facing env-var contract stable across the P6 sub-phases.

- Test coverage:
  - `NoopRemediationDispatcherTest` (unit) -- asserts the
    skipped verdict shape (`channel=noop`,
    `outcome=skipped`, `dispatched=false`).
  - `AnomalyConsumerTest` (unit, Mockito) -- 7 tests covering
    every consumer branch including null payload, parse
    failure, dispatcher null return, dispatcher throws, and
    happy path; asserts the metric tag triple matches the
    verdict + the parsed event's `tenantId`.
  - `RemediationMetricsTest` (unit) -- asserts the bootstrap
    counter is registered at construct time AND that
    `incDispatched(channel, outcome, tenantId)` lazy-registers
    a second series with the supplied tags.
  - `AnomalyEnvelopeParserTest` (unit) -- 8 tests covering every
    branch of the parser (round-trip valid envelope, malformed
    JSON, wrong type, missing eventId, missing tenantId, null
    data block, undecodable data block, null reason coerces to
    empty string).
  - `AnomalyConsumerKafkaIT` (Testcontainers IT, `apache/kafka:3.8.0`)
    -- publishes a valid CloudEvent on `cortex.anomalies.v1`,
    asserts the consumer parses + dispatches (via the default
    no-op) + acks + ticks the counter exactly once with
    `channel=noop, outcome=skipped, tenant_id=<event tenant>`.

### Positive consequences

- One adapter bean per profile -- the consumer never branches on
  channel selection, never multiplexes over a list, and the
  whole `RemediationDispatcher` API surface is exactly one
  method. Adding P6.1 Slack is `+1 class +1 @ConditionalOnProperty`
  with zero consumer-side change (D4).
- The metric tag surface stays inside the Part 17 allowlist by
  construction: the `DispatchResult` constants enumerate every
  legal `channel`/`outcome` value, so a future adapter cannot
  accidentally explode Prometheus cardinality (D3).
- Default no-op gated by `matchIfMissing=true` means the P6.0
  PR ships a service that boots, consumes, parses, dispatches
  (to nothing), and increments the counter -- end-to-end -- with
  zero external dependencies (no Slack webhook URL required in
  CI). The Testcontainers IT exercises the full path and runs
  on every PR build (D5).
- Returning a verdict (instead of throwing) reserves the future
  P6.4 retry budget axis: a `transient_failure` outcome can be
  routed to a retry queue without changing the SPI shape (D7).
- The synchronous shape on the Kafka poll thread keeps the
  durability model identical to ADR-0031 + LD117: the Kafka
  offset IS the boundary, no outbox table, no second source of
  truth (D1).

### Negative consequences

- Only one dispatcher is active per profile. Operators who want
  to fire both Slack AND PagerDuty on the same anomaly must
  either (a) run two `log-remediation-service` deployments with
  different `cortex.remediation.dispatcher.provider` values
  consuming the same topic in different consumer groups, or
  (b) wait for the explicit fan-out adapter pattern we'll add
  in P6.5 if real-world demand surfaces. The single-active-bean
  contract was a deliberate D4 simplification; the fan-out
  pattern is a non-breaking superset (a hypothetical
  `FanOutRemediationDispatcher` is itself a
  `RemediationDispatcher`).
- The consumer catch-all swallows `RuntimeException` from the
  dispatcher + acks, so a broken adapter that throws on every
  event silently advances the offset. This is mitigated by
  (a) the ERROR log line including
  `eventId`, `partition`, `offset`, full stack trace; (b) a
  follow-up P6.0a candidate -- bump a
  `cortex.remediation.dispatcher.errors_total{channel}` counter
  in the catch-all (deferred to P6.4 where it lands with the
  retry-budget work).
- The free-form `reason` field on `DispatchResult` is asymmetric
  with the strictly-bounded `channel`/`outcome` -- adapters can
  cram arbitrary text into it. This is intentional (the field
  exists to make the log line + future DLQ envelope readable)
  but it does require code review to keep the field short and
  free of PII; a future ADR may codify a max length + a
  redaction filter if the log volume warrants it.
- ~3 dispatcher classes, ~3 property blocks, ~3
  `@ConditionalOnProperty` annotations across P6.1..P6.3. This
  is more boilerplate than the rejected "single shared
  HttpRemediationDispatcher parameterized by config" option,
  but the resulting per-channel test surface is much smaller and
  cleaner than templating one mega-adapter across three
  meaningfully different downstream APIs.

### References

- ADR-0005: message bus (RabbitMQ local, Service Bus prod) --
  the P6 remediation service consumes from
  `cortex.anomalies.v1` per this same stance; this ADR is the
  per-channel binding layer above it.
- ADR-0007: declarative YAML playbooks for remediation -- the
  pre-AI-classifier vision; this ADR is the production
  realisation (the dispatcher SPI is what a playbook engine
  would target in a future P9+ iteration).
- ADR-0014: Spring Cloud Gateway MVC (servlet) -- justifies the
  servlet stance reused here over the rejected Reactor option.
- ADR-0028: log-processor-service direct `@KafkaListener`
  consumer -- the symmetric upstream pattern.
- ADR-0029: log-processor-service Spring AI 1.0 anomaly
  classifier -- produces the verdict this service consumes.
- ADR-0030: `ParsedEventSink` fan-out to Loki + Quickwit -- the
  "one bean per side-channel" pattern this ADR mirrors, with
  the inverse contract (sinks swallow; dispatcher returns
  verdict).
- ADR-0031: synchronous `cortex.anomalies.v1` CloudEvents
  publisher -- the producer side of the handoff topic this
  service consumes; defines the envelope shape this ADR
  decodes.
- memory.md LD42: HTTP/1.1 pin on outbound HTTP via
  `JdkClientHttpRequestFactory` (the P6.1..P6.3 adapters
  inherit this).
- memory.md LD79: SCSt outbound silently drops
  `Message<byte[]>` -- justifies the direct `@KafkaListener`
  on the consumer side of this ADR (mirrors ADR-0028 stance).
- memory.md LD100: src/test/resources/application.yml fully
  shadows main yml.
- memory.md LD106: Micrometer counters lazy-register;
  bootstrap-register at construct time so the first scrape
  sees the family.
- memory.md LD112: tenant tag defaults to literal `unknown`
  not `null` to keep Prometheus cardinality bounded.
- memory.md LD117: Kafka consumer -> Kafka producer relay
  services do NOT need an outbox table; the Kafka offset is
  the durability mechanism. Applied here to the
  consumer -> dispatch -> ack path (no DB in P6).
- docs/p5-to-p6-handoff.md sections 2 + 3 -- the envelope
  shape + defensive guards this ADR's parser enforces.
- GitHub issue #84.
