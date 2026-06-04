# log-remediation-service

**Status: P6.0 SHIPPED** -- scaffold + Kafka consumer of
`cortex.anomalies.v1` (the P5.4 / ADR-0031 handoff topic) +
`RemediationDispatcher` SPI stub + Micrometer counter
(PR for #84, ADR-0032). The real Slack / PagerDuty / Jira
adapters land in P6.1 (#85), P6.2 (#86), P6.3 (#87); DLQ +
retry budgets land in P6.4.

CORTEX log remediation. **Consume anomaly CloudEvents from
Kafka -> decode the 8-field `data` block into a typed
`AnomalyEvent` -> hand to the active `RemediationDispatcher`
adapter -> tick the dispatched counter -> commit offset.** The
P6.0 scaffold ships with a default `NoopRemediationDispatcher`
(gated `cortex.remediation.dispatcher.provider=noop`,
`matchIfMissing=true`) so the service boots green with no
downstream HTTP dependency.

## 1. Overview

`log-remediation-service` is the **operator-facing leg of the
CORTEX pipeline**. There is no inbound REST contract for the data
path; the service has only the actuator surface (`:8096`). All
real work happens on the Kafka consumer thread bound to
`cortex.anomalies.v1`:

1. Receive a CloudEvents 1.0 structured-mode JSON envelope via
   `@KafkaListener` with manual offset commit (ADR-0028 stance
   mirrored, ADR-0032 D1).
2. Decode the envelope through `AnomalyEnvelopeParser`; enforce
   `specversion == "1.0"` and `type == "io.cortex.anomaly.v1"`;
   reshape the `data` block into a typed `AnomalyEvent` record.
3. Dispatch the typed event through the `RemediationDispatcher`
   SPI. Exactly one adapter bean is active per profile, selected
   by `cortex.remediation.dispatcher.provider` +
   `@ConditionalOnProperty` (ADR-0032 D4).
4. Tick `cortex.remediation.dispatched_total{channel, outcome,
   tenant_id}` using the verdict the dispatcher returned + the
   parsed event's tenant.
5. Commit the Kafka offset.

P6.1 (next) lands `SlackRemediationDispatcher`; P6.2
`PagerDutyRemediationDispatcher`; P6.3
`JiraRemediationDispatcher`; P6.4 the DLQ writer +
Resilience4j retry budgets.

## 2. Architecture (one screen)

```
                     +-----------------------------+
   cortex.            |  @KafkaListener             |
   anomalies.v1 ----->|  (manual offset commit,     |---+
   (CloudEvents 1.0   |   AckMode.MANUAL_IMMEDIATE) |   |
    JSON envelope)    +-----------------------------+   |
                                                        v
                                  +---------------------------------+
                                  |  AnomalyEnvelopeParser          |
                                  |  (decodes CloudEvents 1.0 via   |
                                  |   cloudevents-json-jackson;     |
                                  |   guards specversion="1.0" +    |
                                  |   type="io.cortex.anomaly.v1";  |
                                  |   reshapes data -> AnomalyEvent)|
                                  +---------------------------------+
                                              |
                              parse OK         \  parse FAIL
                                              |   `-> log WARN + ack
                                              v        (P6.4 will route to
                                              |         cortex.anomalies.v1.dlq
                                              |         with x-failure-reason header)
                                              |
                                  +---------------------------------+
                                  |  RemediationDispatcher (SPI)    |
                                  |   - default: NoopRemediation... |
                                  |     (returns skipped verdict;   |
                                  |      zero outbound HTTP)        |
                                  |   - P6.1: SlackRemediation...   |
                                  |   - P6.2: PagerDutyRemediation. |
                                  |   - P6.3: JiraRemediation...    |
                                  |   gated by                      |
                                  |   cortex.remediation.dispatcher |
                                  |     .provider=<one of above>    |
                                  +---------------------------------+
                                              |
                                              v
                                  +---------------------------------+
                                  |  RemediationMetrics.            |
                                  |    incDispatched(channel,       |
                                  |                  outcome,       |
                                  |                  tenantId)      |
                                  |  -> cortex.remediation.         |
                                  |       dispatched_total          |
                                  |       {channel, outcome,        |
                                  |        tenant_id}               |
                                  +---------------------------------+
                                              |
                                              v
                                  +---------------------------------+
                                  |  ack.acknowledge() -- offset    |
                                  |  commits AFTER dispatch so a    |
                                  |  dispatcher RuntimeException    |
                                  |  is caught + logged + acked     |
                                  |  (D6 -- prevents poison loop;   |
                                  |   transient downstream failures |
                                  |   surface as outcome verdict,   |
                                  |   not as throws)                |
                                  +---------------------------------+
```

## 3. Tech stack

| Layer                      | Tech                                                                 |
|----------------------------|----------------------------------------------------------------------|
| Runtime                    | Java 17 LTS (no virtual threads per ADR-0001)                        |
| Framework                  | Spring Boot 3.3.6 / Spring Cloud 2023.0.4                            |
| Service registry           | Spring Cloud Netflix Eureka client (`lb://`)                         |
| Kafka client               | spring-kafka (direct `@KafkaListener`, manual offset commit; mirror of ADR-0028) |
| Envelope format            | CloudEvents 1.0 structured-mode JSON (cloudevents-json-jackson 4.0.1) |
| Dispatcher SPI             | `RemediationDispatcher` + `DispatchResult` (ADR-0032)                |
| Metrics                    | Micrometer Prometheus with Part 17 allowlist (`channel`, `outcome`, `tenant_id`) |
| Health / probes            | Spring Boot Actuator (`health,info,metrics,prometheus,beans`)        |
| Persistence                | None (Kafka offset IS durability per LD117)                          |
| Build                      | Maven 3.9.9 wrapper, JaCoCo BUNDLE 0.80 line + 0.80 branch           |
| Integration tests          | Testcontainers Kafka 3.8.0 (`apache/kafka:3.8.0` image) + Awaitility |

## 4. Design decisions (ADR pointers)

- **ADR-0032** -- `RemediationDispatcher` SPI + per-channel
  adapter contract. Single method, single argument, single
  return; exactly one adapter bean active per profile selected
  by `cortex.remediation.dispatcher.provider` +
  `@ConditionalOnProperty`; default `NoopRemediationDispatcher`
  gated `matchIfMissing=true` so the scaffold boots green.
- **ADR-0031** -- producer side of the handoff topic: the
  P5.4 `AnomaliesPublisher` synchronously publishes
  CloudEvents 1.0 envelopes to `cortex.anomalies.v1` with
  `id = eventId` for end-to-end dedupe and two headers
  (`content-type=application/cloudevents+json`,
  `x-source-topic=cortex.logs.events.v1`). This consumer
  decodes that exact contract.
- **ADR-0028** -- direct `@KafkaListener` over SCSt + binder;
  manual offset commit (`AckMode.MANUAL_IMMEDIATE`). Carries
  forward LD79 from P4.4b (SCSt outbound silently dropped
  sends).
- **LD117** -- for Kafka consumer -> Kafka producer relay
  services, the Kafka offset itself IS the durability
  mechanism; no outbox table is needed. This service is a
  Kafka consumer -> outbound HTTP relay (in P6.1+); the
  inbound durability mechanism is still the Kafka offset.
  P6.4 will add a DLQ writer for envelopes that fail parsing
  + retry budgets for dispatcher transient failures.
- **LD79** -- SCSt outbound `StreamBridge` silently dropped
  sends in P4.4b; direct `@KafkaListener` is the contract on
  the consumer side.
- **LD92** -- port `:8096` (next free port after `:8095`
  log-processor; `:8090` gateway, `:8092` ingest, `:8093`
  echo, `:8094` WireMock, `:8095` processor).
- **LD93** -- on multi-NIC dev hosts, the `dev` profile pins
  `eureka.instance.ipAddress=127.0.0.1` so `lb://` resolves to
  loopback instead of a virtual adapter.
- **LD100** -- `src/test/resources/application.yml` fully
  shadows the main yml under Spring Boot test, so the test
  resources file declares the full Kafka block; it does NOT
  inherit from main.
- **LD106** -- the
  `cortex.remediation.dispatched_total` counter family is
  bootstrap-registered at construct time with all-`unknown`
  tag values so the `/actuator/prometheus` scrape exposes the
  family on the very first scrape, before any anomaly ticks.
- **LD112** -- when the tenant value would be `null`, the
  metrics layer substitutes the literal string `unknown` to
  keep Prometheus cardinality bounded.
- **Part 17 allowlist** -- the three permitted Prometheus tags
  on the dispatcher counter are `channel`, `outcome`,
  `tenant_id`. The `DispatchResult.CHANNEL_*` +
  `DispatchResult.OUTCOME_*` constants bound the first two
  axes by construction.

## 5. SOLID + Clean Code notes

- `RemediationDispatcher` is the SPI seam (S + O + D); the
  `NoopRemediationDispatcher` impl ships now and the future
  Slack/PD/Jira impls sit behind it so adding a future channel
  is a new class + a new `@ConditionalOnProperty` toggle, no
  consumer code change.
- `AnomalyEnvelopeParser`, `RemediationDispatcher`,
  `RemediationMetrics` are all single-responsibility
  components; the consumer orchestrates them.
- The consumer never `catch (Exception)`-eats silently: every
  failure either ticks the outcome counter via a verdict
  (`outcome="transient_failure"` / `"permanent_failure"`) or
  is logged at ERROR with full context (`partition`,
  `offset`, `eventId`, stack trace) before ack.
- No persistence layer, no `@Transactional` outside the Kafka
  consumer container's offset commit; the service is
  intentionally stateless (LSP-friendly horizontal scaling).
- Lombok is used only in the production sources of this
  module (the `log-agent-lib` contract is Lombok-free per
  ADR-0013); the test sources stay Lombok-free.

## 6. Logging

- Logback JSON appender shared via `log-agent-lib` (ADR-0011 +
  Rule 26.10.6); MDC carries `traceId`, `requestId`,
  `tenantId`, `userId` populated from the `traceparent` header
  on the inbound CloudEvent (when present) or derived from
  `subject` / `id`.
- The dispatcher log line includes only `eventId`, `tenantId`,
  `channel`, `outcome`, `reason`; it does NOT log the
  classifier `message` body so a future PII-in-log-message bug
  stays contained.
- Parse failures log at WARN with the categorical
  `FailureReason` enum value
  (`INVALID_ENVELOPE` / `WRONG_TYPE` / `MISSING_DATA`); the
  actual rejected envelope is NOT logged (P6.4 will land the
  DLQ writer that publishes it to
  `cortex.anomalies.v1.dlq` with header
  `x-failure-reason=<reason>`).

## 7. Run locally

```powershell
# 0. Prereqs: infra-up (Kafka) so the upstream log-processor-service
#    can publish to cortex.anomalies.v1.
docker compose -f infra\local\docker-compose.smoke.yml up -d `
    postgres redis kafka wiremock

# 1. Install parent + log-agent-lib peer jar once per session.
.\mvnw.cmd -pl ".,log-agent-lib" install -DskipTests -B

# 2. Iterate on log-remediation-service only after the install above.
.\mvnw.cmd -pl log-remediation-service verify -B

# 3. Boot the registry + service (P6.0 boot scripts ship in the
#    gitignored scripts/p6-0/ folder per LD104; see scripts/p6-0/README.md
#    for the local-only boot harness):
powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts\p6-0\boot-full-stack.ps1

# 4. Probe:
Invoke-WebRequest http://localhost:8096/actuator/health
Invoke-WebRequest http://localhost:8096/actuator/prometheus `
    | Select-Object -ExpandProperty Content `
    | Select-String 'cortex_remediation_dispatched_total'
```

The P6.0 scaffold runs the default `NoopRemediationDispatcher`
(no Slack webhook URL, no PagerDuty integration key, no Jira API
token required). To force a real adapter once P6.1..P6.3 land:

```powershell
$env:CORTEX_REMEDIATION_DISPATCHER = 'slack'        # P6.1
$env:CORTEX_REMEDIATION_DISPATCHER = 'pagerduty'    # P6.2
$env:CORTEX_REMEDIATION_DISPATCHER = 'jira'         # P6.3
```

## 8. Docker

This service ships **without a Dockerfile in P6.0**; the
`infra/docker/` full-compose lands in P10. For local
containerized runs against the smoke stack, point the existing
Spring Boot image-build at this module:

```powershell
.\mvnw.cmd -pl log-remediation-service spring-boot:build-image `
    -DimageName=cortex/log-remediation-service:dev
```

then add a service block to
`infra/local/docker-compose.smoke.yml` when P10 reshapes the
local stack. The image inherits the
`paketobuildpacks/builder-jammy-base:0.3.x` base used by every
other service.

## 9. API documentation

The service has **no public REST surface in P6.0**; only the
actuator endpoints are exposed. The Postman v2.1 contract in
`postman/log-remediation.postman_collection.json` documents the
actuator surface and the end-to-end pipeline assertion (publish
a CloudEvent to `cortex.anomalies.v1`, observe the
`cortex.remediation.dispatched_total` counter delta).

The Kafka consumer contract is the `AnomalyEvent` shape this
service decodes from the CloudEvent `data` field (matches the
producer-side ADR-0031 contract exactly):

| Field      | Type           | Source / meaning                                              |
|------------|----------------|---------------------------------------------------------------|
| `eventId`  | `String`       | SHA-256 hex from the upstream `LogEvent.eventId`; idempotency key |
| `tenantId` | `String` (UUID)| Originating tenant; becomes the `tenant_id` metric tag        |
| `severity` | `String`       | Classifier severity bucket (`NONE`/`LOW`/`MEDIUM`/`HIGH`/`CRITICAL`) |
| `reason`   | `String`       | Classifier short explanation (may be empty, never null)        |
| `ts`       | `Instant`      | ISO-8601 UTC instant of the originating log line              |
| `level`    | `String`       | Original log level (`TRACE`..`ERROR`)                         |
| `service`  | `String`       | Logical service / app name that produced the log line          |
| `message`  | `String`       | Human-readable message verbatim from the source               |

The CloudEvent envelope MUST carry:

| Envelope attribute | Value                                                              |
|--------------------|--------------------------------------------------------------------|
| `specversion`      | `1.0`                                                              |
| `type`             | `io.cortex.anomaly.v1`                                             |
| `source`           | `/cortex/log-processor-service`                                    |
| `subject`          | `tenant_id`                                                        |
| `id`               | `event_id` (matches `data.eventId` for end-to-end dedupe)          |
| `time`             | ISO-8601 UTC                                                       |
| `datacontenttype`  | `application/json`                                                 |
| `data`             | `AnomalyEvent` JSON (8-field record above)                         |

Plus two Kafka headers (ADR-0027 mirror):

| Header           | Value                                  |
|------------------|----------------------------------------|
| `content-type`   | `application/cloudevents+json`         |
| `x-source-topic` | `cortex.logs.events.v1`                |

## 10. Future improvements

- **P6.1** -- `SlackRemediationDispatcher` (`RestClient` +
  Slack incoming-webhook URL + Resilience4j
  `@CircuitBreaker` + `@TimeLimiter`; gated
  `cortex.remediation.dispatcher.provider=slack`). Per-tenant
  webhook routing via a config-driven map.
- **P6.2** -- `PagerDutyRemediationDispatcher` (PagerDuty
  Events API v2 envelope with `routing_key` + `dedup_key
  = event.eventId()` for end-to-end dedupe; gated
  `cortex.remediation.dispatcher.provider=pagerduty`).
- **P6.3** -- `JiraRemediationDispatcher` (Jira REST issue
  create with per-tenant project + issuetype; gated
  `cortex.remediation.dispatcher.provider=jira`).
- **P6.4** -- DLQ writer for parse failures
  (`cortex.anomalies.v1.dlq` with `x-failure-reason` header) +
  Resilience4j retry budgets for dispatcher transient failures
  + `cortex.remediation.dispatcher.errors_total{channel}`
  counter for the consumer catch-all.
- **P6.5** -- (conditional on operator demand) explicit
  fan-out dispatcher (a `FanOutRemediationDispatcher` that is
  itself a `RemediationDispatcher` + delegates to N inner
  adapters). The ADR-0032 SPI shape was chosen specifically
  to make this a non-breaking superset.
- **P9 / P10** -- multi-partition consumer concurrency bump
  once partition counts on `cortex.anomalies.v1` exceed one.
  The `KafkaListenerContainerFactory.concurrency` setting
  defaults to 1 in P6.0; raise alongside the partition bump.
