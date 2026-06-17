# log-processor-service

**Status: P0..P5 SHIPPED** -- the P5 epic is closed on `main` at
merge commit `d2e6acc` (PR #81, 2026-06-04T08:37:51Z). Five
sub-phases delivered: P5.0 scaffold + Kafka consumer (PR #67), P5.1
parser + schema validator + DLQ (PR #70), P5.2 Spring AI 1.0
anomaly classifier (PR #73), P5.3 Loki + Quickwit fan-out sinks
(PR #77), P5.4 `cortex.anomalies.v1` synchronous CloudEvents
publisher for P6 handoff (PR #81, ADR-0031 + LD117). Closer:
P5.5 docs + handoff doc + atomic 4-file flip (this PR).

CORTEX log processor. **Consume CloudEvents from Kafka -> parse +
validate -> classify (Spring AI 1.0) -> commit offset, fan rejected
envelopes out to the DLQ topic**. Activated by the
`cortex.logs.events.v1` topic that `log-ingest-service` publishes to
via its transactional outbox (P4.4b/c).

> P5.0 shipped the scaffold + Kafka consumer + classifier SPI +
> metrics (PR #67, `068a3f8`). P5.0a (PR #68, `a8e539c`) shipped the
> shared `CloudEventEnvelope` in `log-agent-lib`, flipped the gateway
> `/api/v1/logs/**` route onto `lb://log-ingest-service`, and moved
> this service from `:8094` to `:8095` so WireMock keeps `:8094`.
> P5.1 (PR #70, `65e2ab8`) shipped the parser + JSON-schema validator
> + DLQ publisher. P5.2 (PR #73, `e92efaf`) shipped the Spring AI 1.0
> GA anomaly classifier. P5.2a (PR #75, `43a94e9`) shipped the
> Postman collection + ten-section README + Newman Leg C evidence.
> P5.3 (PR #77, `6e2f51c`) wired the `ParsedEventSink` SPI list +
> `LokiSink` + `QuickwitSink` fan-out behind
> `cortex.processor.sinks.{loki,quickwit}.enabled` feature gates
> (ADR-0030). P5.3a (PR #78, `5579186`) bumped the matching
> `postman/README.md` matrix entry (LD116). P5.4 (PR #81, `d2e6acc`)
> wired `AnomaliesPublisher` to publish CloudEvents 1.0 anomaly
> envelopes to `cortex.anomalies.v1` for the future P6
> `log-remediation-service` handoff (ADR-0031 / LD117 -- no outbox
> table because Kafka offset is the durability mechanism). P5.5
> (this PR) closes the epic: banner flip, ADR INDEX refresh, P6
> handoff doc, atomic 4-file flip.

## 1. Overview

`log-processor-service` is the **AI-classification leg of the CORTEX
pipeline**. There is no inbound REST contract for the data path; the
service has only the actuator surface (`:8095`). All real work happens
on the Kafka consumer thread bound to `cortex.logs.events.v1`:

1. Receive a CloudEvents 1.0 structured-mode JSON envelope via
   `@KafkaListener` with manual offset commit (ADR-0028, LD79).
2. Parse the envelope -> `LogEvent` POJO; reject unknown
   `specversion` / `type` / missing required fields with DLQ fan-out.
3. Validate the `LogEvent` against the schema (required fields,
   `level` enum allowlist, ISO-8601 `timestamp`); reject invalid
   payloads with DLQ fan-out.
4. Classify the `LogEvent` via the `LogEventClassifier` SPI; the
   default `spring-ai` provider calls `ChatClient.call(...)` against
   the configured Ollama / Azure OpenAI / WireMock binder, falls back
   to `outcome=skipped` on timeout and `outcome=error` on classifier
   exception (the event is still committed, just not classified).
5. Increment the right Micrometer counter family then commit the
   Kafka offset.

P5.3 (next) wires the anomaly classification into a remediation hook
so a confirmed anomaly fans out to `log-remediation-service`.

## 2. Architecture (one screen)

```
                     +-----------------------------+
   cortex.logs.      |  @KafkaListener             |
   events.v1   ----->|  (manual offset commit,     |---+
   (CloudEvents 1.0  |   AckMode.MANUAL_IMMEDIATE) |   |
    JSON envelope)   +-----------------------------+   |
                                                       v
                                  +---------------------------------+
                                  |  CloudEventEnvelopeParser       |
                                  |  (rejects unknown type /        |
                                  |   specversion / missing fields) |
                                  +---------------------------------+
                                              |
                              parse OK         \  parse FAIL
                                              |   `-> DlqPublisher  -> cortex.logs.events.v1.dlq
                                              v        (+ counter cortex.processor.events.dlq_replay_total{reason})
                                  +---------------------------------+
                                  |  LogEventSchemaValidator        |
                                  |  (required fields, level enum,  |
                                  |   ISO-8601 timestamp)           |
                                  +---------------------------------+
                                              |
                              valid            \  invalid
                                              |   `-> DlqPublisher -> cortex.logs.events.v1.dlq
                                              v
                                  +---------------------------------+
                                  |  LogEventClassifier (SPI)       |
                                  |   - default: SpringAiAnomaly... |
                                  |     (Ollama / AzureOpenAI /     |
                                  |      WireMock via ADR-0029)     |
                                  |   - fallback: NoopClassifier    |
                                  +---------------------------------+
                                              |
                       outcome=anomaly|normal  |  outcome=error|skipped
                                              v
                                  +---------------------------------+
                                  |  Counter bumps:                 |
                                  |   - parsed_total                |
                                  |   - classified_total{outcome=X} |
                                  +---------------------------------+
                                              |
                                              v
                                  +---------------------------------+
                                  |  Manual offset commit on the    |
                                  |  ack so a DLQ publish failure   |
                                  |  becomes a Kafka redelivery     |
                                  |  (LD94 ordering invariant)      |
                                  +---------------------------------+
```

## 3. Tech stack

| Layer                      | Tech                                                                 |
|----------------------------|----------------------------------------------------------------------|
| Runtime                    | Java 17 LTS (no virtual threads per ADR-0001)                        |
| Framework                  | Spring Boot 3.3.6 / Spring Cloud 2023.0.4                            |
| Service registry           | Spring Cloud Netflix Eureka client (`lb://`)                         |
| Kafka client               | spring-kafka (direct `@KafkaListener`, manual offset commit, ADR-0028) |
| Envelope format            | CloudEvents 1.0 structured-mode JSON (ADR-0026, mirror of ingest)   |
| AI binder                  | Spring AI 1.0 GA `spring-ai-starter-model-ollama` (ADR-0029)        |
| AI HTTP transport          | HTTP/1.1 pin via `JdkClientHttpRequestFactory` (LD42)               |
| Prompt rendering           | Literal `.replace("{tenant_id}", ...)` (NOT ST4, LD42 fix)           |
| Metrics                    | Micrometer Prometheus with Part 17 allowlist                         |
| Health / probes            | Spring Boot Actuator (`health,info,metrics,prometheus,beans`)        |
| Persistence                | None (the service is stateless on the consumer thread)               |
| Build                      | Maven 3.9.9 wrapper, JaCoCo BUNDLE 0.80 line + 0.80 branch (LD23)    |
| Smoke contract             | `scripts/p5-2/smoke-p5-2.ps1` + `scripts/p5-2a/smoke-p5-2a.ps1` + `scripts/p5-3/smoke-p5-3.ps1` + `scripts/p5-4/smoke-p5-4.ps1` |
| Newman collection          | `postman/log-processor.postman_collection.json` (P5.4 / 13+ requests / 55+ assertions) |

## 4. Design decisions (ADR pointers)

- **ADR-0028** -- direct `@KafkaListener` over SCSt + binder; manual
  offset commit (`AckMode.MANUAL_IMMEDIATE`). Carries forward LD79
  from P4.4b (SCSt outbound silently dropped sends).
- **ADR-0029** -- Spring AI 1.0 GA provider matrix: Ollama in dev,
  Azure OpenAI in prod, WireMock-stubbed Ollama on `:8094` in smoke.
  Selection is `cortex.processor.classifier.provider` +
  `spring.ai.ollama.base-url`.
- **ADR-0030** -- `ParsedEventSink` SPI list + `LokiSink` +
  `QuickwitSink` fan-out behind
  `cortex.processor.sinks.{loki,quickwit}.enabled` feature gates.
  Both sinks pin outbound HTTP to HTTP/1.1 per LD42; both tick
  per-tenant + per-reason failure counters; sink failures NEVER
  block the Kafka offset commit. Loki stream labels are bounded to
  `tenant_id`, `service`, `level`, and `anomaly`; event id stays out
  of labels. See `cortex.processor.sinks.*`
  block in `application.yml` for the per-sink env-var overrides.
- **ADR-0031** -- synchronous `cortex.anomalies.v1` CloudEvents
  1.0 publisher for the future P6 `log-remediation-service`
  handoff. `AnomaliesPublisher` reuses the existing
  byte[]/byte[] `KafkaTemplate` and sends synchronously with a
  10-second bounded wait; on failure throws
  `IllegalStateException` so the source record stays un-acked
  and Kafka rebalance redelivery retries the publish. New
  Micrometer counter `cortex.processor.anomalies.published_total
  {topic, tenant_id}` bootstrap-registered at construct-time
  per LD106 + LD112.
- **LD117** -- for Kafka consumer -> Kafka producer relay
  services, the Kafka offset itself IS the durability mechanism;
  no outbox table is needed unless the source of the verdict is
  non-Kafka. P4.4 needed an outbox because HTTP ingest returns
  202 Accepted to the client before durable persistence; P5.4
  does not have that asymmetry.
- **LD42** -- HTTP/1.1 pin on `OllamaApi` (`JdkClientHttpRequestFactory`)
  + literal substring prompt template render. OkHttp HTTP/2 stream
  resets were silently mapped to retried 5xx and ST4 crashed on
  unbalanced `{` in user log messages.
- **LD79** -- SCSt outbound `StreamBridge` silently dropped sends in
  P4.4b; direct `KafkaTemplate` + `@KafkaListener` is the contract.
- **LD92** -- port `:8095` not `:8094` because WireMock has owned
  `:8094` since P3.3 and the local smoke compose maps it that way.
- **LD93** -- on multi-NIC dev hosts, the `dev` profile pins
  `eureka.instance.ipAddress=127.0.0.1` so `lb://` resolves to
  loopback instead of a virtual adapter.
- **LD94** -- the consumer commits the offset AFTER any DLQ publish
  succeeds so a DLQ publish failure becomes a Kafka redelivery rather
  than a silent data loss.
- **Part 17 allowlist** -- the three permitted Prometheus tags on the
  classifier counters are `topic`, `tenant_id`, `outcome`. Anything
  else is dropped by `MicrometerCardinalityFilter`.

## 5. SOLID + Clean Code notes

- `LogEventClassifier` is the SPI seam (S + O + D); the `spring-ai`
  and `noop` implementations sit behind it so adding a future
  provider (Azure AI Inference, AWS Bedrock, on-cluster vLLM) is a
  new class + a new `@ConditionalOnProperty` toggle, no consumer
  code change.
- `CloudEventEnvelopeParser`, `LogEventSchemaValidator`,
  `LogEventClassifier`, `DlqPublisher`, `ProcessorMetrics` are all
  single-responsibility components; the consumer orchestrates them.
- The consumer never `catch (Exception)`-eats: every failure either
  goes to the DLQ with a typed `FailureReason` or is rethrown so
  Kafka redelivers.
- No persistence layer, no `@Transactional` outside the Kafka
  consumer container's offset commit; the service is intentionally
  stateless (LSP-friendly horizontal scaling).
- Lombok-free per ADR-0013 (because `log-agent-lib` is the contract
  module and that module is Lombok-free).

## 6. Logging

- Logback JSON appender shared via `log-agent-lib` (ADR-0011 +
  Rule 26.10.6); MDC carries `traceId`, `requestId`, `tenantId`,
  `userId` populated from the `traceparent` header on the inbound
  CloudEvent (when present) or derived from `subject` / `id`.
- The classifier logs only `tenant_id`, `event_id`, `outcome`,
  `duration_ms`, and `provider`; it does NOT log the prompt text or
  the model response body so a future PII-in-model-output bug stays
  contained.
- Errors that flow to the DLQ are logged at `WARN` with the
  `FailureReason` enum value; the actual rejected envelope is NOT
  logged (the envelope already lives in the `.dlq` topic with the
  `x-failure-reason` header).

## 7. Run locally

```powershell
# 0. Prereqs: infra-up (Postgres+Redis+Kafka+WireMock) so the upstream
#    log-ingest-service can publish to cortex.logs.events.v1 and the
#    classifier can hit the WireMock-stubbed Ollama on :8094.
docker compose -f infra\local\docker-compose.smoke.yml up -d `
    postgres redis kafka wiremock

# 1. Install parent + log-agent-lib peer jar once per session.
.\mvnw.cmd -pl ".,log-agent-lib" install -DskipTests -B

# 2. Iterate on log-processor-service only after the install above.
.\mvnw.cmd -pl log-processor-service verify -B

# 3. Boot the registry + service (use the per-phase boot script so the
#    spring-ai classifier + WireMock-stubbed Ollama are wired correctly):
powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts\p5-2\boot-processor-spring-ai.ps1

# 4. Probe:
Invoke-WebRequest http://localhost:8095/actuator/health
Invoke-WebRequest http://localhost:8095/actuator/prometheus `
    | Select-Object -ExpandProperty Content `
    | Select-String 'cortex_processor_events_'
```

For the full pipeline run (gateway -> ingest -> Kafka -> processor)
use `scripts\p5-2a\boot-full-stack.ps1`. For the P5.3 fan-out
variant that flips both `cortex.processor.sinks.loki.enabled` and
`cortex.processor.sinks.quickwit.enabled` to `true` (pointed at the
WireMock :8094 stub), use `scripts\p5-3\boot-full-stack.ps1`. For
the P5.4 anomalies-publisher variant that additionally drains the
`cortex.anomalies.v1` topic and asserts the new counter delta, use
`scripts\p5-4\boot-full-stack.ps1`.

## 8. Docker

This service ships **without a Dockerfile in P5.x**; the
`infra/docker/` full-compose lands in P10. For local containerized
runs against the smoke stack, point the existing Spring Boot
image-build at this module:

```powershell
.\mvnw.cmd -pl log-processor-service spring-boot:build-image `
    -DimageName=cortex/log-processor-service:dev
```

then add a service block to `infra/local/docker-compose.smoke.yml`
when P10 reshapes the local stack. The image inherits the
`paketobuildpacks/builder-jammy-base:0.3.x` base used by every other
service.

## 9. API documentation

The service has **no public REST surface**. The Postman v2.1
contract in `postman/log-processor.postman_collection.json`
documents the only HTTP endpoints (actuator + the end-to-end
pipeline assertion published through the gateway). The Kafka
consumer contract is the `CloudEventEnvelope` shape in
`log-agent-lib`:

| Field                   | Value                                                       |
|-------------------------|-------------------------------------------------------------|
| `specversion`           | `1.0`                                                       |
| `type`                  | `io.cortex.logs.event.v1`                                   |
| `source`                | `/cortex/log-ingest-service`                                |
| `subject`               | `tenant_id`                                                 |
| `id`                    | `event_id` (SHA-256 of canonical pre-image, server-computed)|
| `time`                  | `ingest_time` (ISO-8601 UTC)                                |
| `datacontenttype`       | `application/json`                                          |
| `data`                  | `LogEvent` POJO (level / message / labels / timestamp)      |

The same envelope is consumed from `cortex.logs.events.v1` and
republished to `cortex.logs.events.v1.dlq` on rejection with two
extra Kafka headers `x-orig-topic` + `x-failure-reason` (ADR-0027,
mirror of the ingest-side outbox DLQ contract).

## 10. Future improvements

- **P6** -- `log-remediation-service` consumes from
  `cortex.anomalies.v1` (the P5.4 / ADR-0031 handoff topic) and
  dispatches Slack / PagerDuty / Jira playbooks. Includes a
  CloudEvents 1.0 envelope schema-registry binding so the
  contract is enforced server-side at publish time. See
  [docs/p5-to-p6-handoff.md](../docs/p5-to-p6-handoff.md) for the
  concrete contract.
- **Azure OpenAI provider** -- light up the prod binder in P9 so we
  can validate the ADR-0029 dual-provider claim end-to-end on a real
  Azure subscription, not just under the WireMock stub.
- **mTLS** -- enable the commented `SslBundle` block in
  `application.yml` when P5.x rolls cluster-wide service-to-service
  mTLS out.
- **GraphQL `anomalies` query** -- expose the anomaly stream via the
  gateway's GraphQL surface in P9 so dashboards can subscribe via
  `subscription { anomalies(tenant: "...") }`.
- **Schema registry** -- replace the in-process JSON-schema validator
  with a Confluent / Apicurio schema registry binding in P10 once the
  cluster runs Schema Registry alongside Kafka.
