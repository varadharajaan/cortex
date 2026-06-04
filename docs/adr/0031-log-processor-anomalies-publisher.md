# 0031. log-processor-service synchronous `cortex.anomalies.v1` publisher (no outbox)

- Status: accepted
- Date: 2026-06-04
- Deciders: @varadharajaan
- Tags: processor, anomalies, kafka, cloudevents, handoff, remediation

## Context and problem statement

`log-processor-service` consumes parsed log events off
`cortex.logs.events.v1` (P5.1 / ADR-0028), classifies them through
the Spring AI 1.0 anomaly classifier (P5.2 / ADR-0029), and -- as of
P5.3 / ADR-0030 -- fans the verdict out to Grafana Loki + Quickwit
in-process. None of those deliveries reach the **future P6
`log-remediation-service`**, which needs the verdict on a dedicated
Kafka topic so it can dispatch playbooks (Slack / PagerDuty / Jira)
without coupling to the upstream classification pipeline.

P5.4 is the handoff: every confirmed anomaly must arrive on a
dedicated topic (`cortex.anomalies.v1`) as a CloudEvents 1.0
envelope, byte-stable for downstream dedupe, with enough provenance
that the P6 consumer can correlate back to the source log event.

The plan + memory + checkpoint pinned this work to the same patterns
P4.4b / P4.4c shipped on the ingest side (ADR-0026, ADR-0027): a
direct `KafkaTemplate<byte[], byte[]>` send with `acks=all` +
`enable.idempotence=true`, a 10-second bounded send-timeout, and a
two-header envelope (`content-type=application/cloudevents+json` +
`x-source-topic`). The open question was whether P5.4 also needs a
**transactional outbox table + poller** the way P4.4a / P4.4b did,
or whether the consumer thread can publish synchronously.

## Decision drivers

- D1 -- must NOT lose an anomaly: a broker NACK or send timeout at
  the P5.4 publisher must result in a redelivery of the source
  record, not a silent drop. (This is what the P4.4 outbox table
  solves on the ingest side.)
- D2 -- must NOT block the Kafka happy path for tens of seconds on
  every send: a slow broker must surface as a bounded
  `IllegalStateException` not an unbounded wait.
- D3 -- must give the P6 consumer a stable dedupe key so a P5.4
  redelivery does not result in duplicate Slack pages.
- D4 -- must carry the same two-header contract as the ingest-side
  CloudEvents producer (ADR-0026) so downstream consumers can
  dispatch on header without inspecting the payload.
- D5 -- must emit a Micrometer counter so an outage in the P5.4
  publisher is visible on the processor's `/actuator/prometheus`
  scrape without scraping the broker.
- D6 -- must NOT introduce a second source of truth: the verdict is
  already encoded in the upstream `cortex.logs.events.v1` offset; a
  separate Postgres outbox would be a third copy of the same data
  with its own consistency window.

## Considered options

1. **Synchronous publish on the consumer thread via `KafkaTemplate.send().get(10s)`;
   on failure, throw `IllegalStateException` and leave the source
   record un-acked so Kafka rebalance redelivery retries the
   publish on the next poll.** -- accepted.
2. **Transactional outbox: V5 Flyway `anomaly_events` table + per-row
   `@Transactional(REQUIRES_NEW)` writer in `LogEventConsumer` +
   `@Scheduled` poller modeled on `OutboxPoller` (P4.4b).** --
   rejected: doubles the schema-evolution surface (a second outbox
   table on top of `outbox_events` in `log-ingest-service`), adds a
   Postgres dependency to a service that today has none, and adds
   a new poller thread to monitor. The justification for the
   ingest-side outbox (HTTP returns 202 Accepted to the client
   BEFORE the Kafka publish, so an inbound batch CAN NOT be
   replayed from upstream) does NOT apply here: the source of the
   verdict is itself a Kafka record consumed with
   `enable-auto-commit=false` + `AckMode.MANUAL_IMMEDIATE`, so the
   Kafka offset IS the durability mechanism (LD117).
3. **Async fire-and-forget: `kafkaTemplate.send(record)` without
   `.get(...)`; rely on the producer's internal retry config.** --
   rejected: violates D1 -- a producer-side timeout silently
   discards the verdict and the consumer has already acked the
   source record. There is no second chance.
4. **Direct StreamBridge from a Spring Cloud Stream binder.** --
   rejected outright: LD79 documents that SCSt outbound on
   spring-cloud-stream 2023.0.4 silently drops `Message<byte[]>`
   sends through `StreamBridge.send()`. This is the same trap
   P4.4b fell into; ADR-0026 settled on direct `KafkaTemplate` for
   exactly this reason.
5. **Reuse the P5.3 `ParsedEventSink` SPI list and add a third
   `KafkaAnomalySink` impl.** -- rejected: the SPI list is
   explicitly designed for non-blocking, exception-swallowing
   sinks (ADR-0030 D1 + D2). The anomalies publisher needs the
   OPPOSITE contract -- a failure MUST bubble up so the source
   record stays un-acked. Forcing the anomalies publisher through
   the same SPI would weaken either contract or both.

## Decision outcome

Chosen option: **option 1, synchronous publish on the consumer
thread via `KafkaTemplate.send().get(10s)`; on failure, throw
`IllegalStateException` and leave the source record un-acked so
Kafka rebalance redelivery retries the publish on the next poll.**

### Implementation shape

- New class `io.cortex.processor.consume.AnomaliesPublisher` --
  mirrors the `DlqPublisher` shape exactly:
  - Two constructors: public `@Autowired` Spring ctor + package-
    private test-seam ctor accepting an explicit `Clock`.
  - Reuses the existing `KafkaTemplate<byte[], byte[]>` bean
    (`dlqKafkaTemplate`) from `ProcessorKafkaProducerConfig`. No
    new producer factory; the byte[]/byte[] producer with
    `acks=all` + `enable.idempotence=true` + `request.timeout=5s`
    + `delivery.timeout=30s` already satisfies the P5.4 contract.
  - `publish(RawLogEvent, Classification)` builds a CloudEvents 1.0
    structured-mode JSON envelope:
    - `id = event.eventId()` (D3 -- mirrors the upstream producer
      ID so the P6 consumer dedupes end-to-end on the same key).
    - `source = /cortex/log-processor-service`.
    - `type = io.cortex.anomaly.v1`.
    - `time = OffsetDateTime.now(clock).withOffsetSameInstant(UTC)`.
    - `subject = event.tenantId()`.
    - `datacontenttype = application/json`.
    - `data` = deterministic-field-order JSON map of
      `{eventId, tenantId, severity, reason, ts, level, service,
      message}`. The `ts` field is stringified at the publisher
      so a downstream consumer using a bare `ObjectMapper` (no
      `JavaTimeModule`) can decode the envelope byte-for-byte.
  - Sends synchronously with `send(record).get(10, SECONDS)`. On
    `InterruptedException` the thread is re-interrupted and the
    method throws `IllegalStateException`. On
    `ExecutionException` / `TimeoutException` it throws
    `IllegalStateException` with the cause attached.
  - Kafka record key = `event.eventId().getBytes(UTF_8)` so the P6
    consumer's partition assignment is stable per-event for dedupe.
  - Two Kafka headers per D4:
    `content-type = application/cloudevents+json`,
    `x-source-topic = ${cortex.processor.topic}`.

- `LogEventConsumer` wired with the new collaborator -- ctor gains
  a 7th argument `AnomaliesPublisher anomaliesPublisher` placed
  between `dlqPublisher` and `sinks` (mirrors the natural data flow:
  parse -> validate -> classify -> publish-on-anomaly -> fan-out
  -> ack). The anomaly branch becomes:
  ```
  metrics.incAnomaliesPublished(...);
  anomaliesPublisher.publish(event, verdict);   // throws on failure
  fanOut(event, verdict);                       // P5.3 sinks
  ack.acknowledge();
  ```
  A publish failure surfaces as `IllegalStateException`, is logged
  at ERROR with `eventId + tenantId`, and the method returns early
  WITHOUT calling `ack.acknowledge()`. On the next poll, Kafka
  rebalance redelivery re-presents the same source record and the
  publish is re-attempted (D1).

- `ProcessorMetrics` gains one new counter:
  `cortex.processor.anomalies.published_total` tagged
  `{topic, tenant_id}`. Bootstrap-registered at construct-time with
  `topic=cortex.anomalies.v1` + `tenant_id=unknown` per LD106 +
  LD112 so the counter is visible on the very first Prometheus
  scrape, before any anomaly has flowed through. The Postman /
  Newman + smoke contracts can therefore baseline + delta the
  counter unconditionally; no family-presence gate is required.

- Configuration:
  - `application.yml` (main + test per LD100):
    `cortex.processor.anomalies.topic: ${CORTEX_PROCESSOR_ANOMALIES_TOPIC:cortex.anomalies.v1}`.
  - The producer reuses `spring.kafka.bootstrap-servers`; no
    additional `spring.kafka.producer.*` block.

- Test coverage:
  - `AnomaliesPublisherTest` (unit) -- builds the envelope with an
    in-memory `ObjectMapper` + a fixed `Clock`; asserts the
    payload bytes round-trip back through `JsonFormat.deserialize`
    to a CloudEvent with the documented attributes + the same
    `data` JSON object.
  - `LogEventConsumerTest` (unit) -- extends every existing test
    with a `Mockito.mock(AnomaliesPublisher.class)`; verifies the
    anomaly branch invokes `publish(...)` exactly once and the
    non-anomaly branches NEVER invoke it. New failure-path test
    `anomalyPublishFailureLeavesRecordUnacked` proves that an
    `IllegalStateException` from the publisher prevents
    `ack.acknowledge()` from being called.
  - `LogEventConsumerKafkaIT` (Testcontainers IT) -- new `@Order(4)
    errorEnvelopeIsClassifiedAndPublishedToAnomaliesTopic` test
    + new `StubAnomalyClassifierConfig` `@TestConfiguration` that
    forces `level=ERROR` -> verdict `severity=HIGH`. The IT
    pre-creates `cortex.anomalies.v1` on the container broker,
    publishes an ERROR-level envelope to the source topic, drains
    the anomalies topic with a dedicated consumer, and asserts:
    - the envelope `specversion=1.0`, `type=io.cortex.anomaly.v1`,
      `source=/cortex/log-processor-service`,
      `subject=tenant_id`, `datacontenttype=application/json`;
    - the two headers `content-type=application/cloudevents+json`
      and `x-source-topic=cortex.logs.events.v1`;
    - the record key = `eventId` bytes;
    - the `data` object carries `eventId, tenantId, severity,
      reason, level, service, message` with the expected values;
    - the `cortex.processor.anomalies.published_total` counter
      strictly increased.

### Positive consequences

- No new persistence dependency: the processor service stays
  Postgres-free.
- No new schema-evolution surface: no V5 Flyway migration, no
  `anomaly_events` table, no second outbox poller to monitor.
- Source-of-truth invariant: the Kafka offset IS the durability
  mechanism. A P5.4 publish failure causes a Kafka rebalance
  redelivery of the source record, which causes the publish to be
  re-attempted, which causes the offset to advance only on
  success (D1).
- Bounded send-timeout: a slow broker surfaces as a deterministic
  10-second `IllegalStateException`, not an unbounded wait on the
  consumer thread (D2).
- End-to-end dedupe contract: P6 consumes by `eventId` and dedupes
  on the same key the upstream producer used (D3).
- Two-header contract matches the ingest-side ADR-0026 envelope so
  downstream consumers + ops dashboards can dispatch / filter on
  header (D4).
- Per-topic + per-tenant counter exposes outage on the processor's
  own scrape (D5).
- ~600 lines of code, 1 Postgres dependency, 1 Flyway migration,
  and 1 poller class avoided vs. the rejected outbox option.

### Negative consequences

- The anomalies publish happens inline on the consumer thread. With
  a healthy broker the send completes in <10ms; under broker stress
  the throughput ceiling drops to roughly
  `consumer-threads * (1 / publish_latency)`. P5.x ships single-
  threaded per partition so this is acceptable; if P9 / P10 scales
  the partition count beyond one container, the consumer factory
  concurrency should be bumped in tandem.
- A duplicate anomaly CAN reach the P6 topic on a Kafka rebalance
  redelivery (publish succeeded but ack timed out). This is
  acceptable because the envelope `id = eventId` is stable across
  redelivery, so the P6 consumer dedupes server-side. (Compare to
  the P5.3 Loki sink which has no idempotency primitive on its
  push API; see ADR-0030 D6.)
- The anomalies publisher is a 7th constructor argument on
  `LogEventConsumer`, one over the Checkstyle `ParameterNumber`
  default of 6. Suppressed locally with
  `@SuppressWarnings("checkstyle:ParameterNumber")` because the
  six cooperating collaborators of the Kafka consumer pipeline
  (parser, validator, classifier, metrics, dlqPublisher,
  anomaliesPublisher, sinks) are the intended design and folding
  them into a facade bean would obscure the data flow.

### References

- ADR-0005: message bus (RabbitMQ local, Service Bus prod) -- the
  P6 remediation service will consume from `cortex.anomalies.v1`
  per this same stance.
- ADR-0006: AI provider abstraction.
- ADR-0026: log-ingest-service direct `KafkaTemplate` +
  CloudEvents 1.0 envelope (the producer contract this ADR
  mirrors on the processor side).
- ADR-0027: log-ingest-service DLQ counters binder (the
  two-header pattern this ADR reuses).
- ADR-0028: log-processor-service direct `@KafkaListener`
  consumer (manual offset commit makes LD117 possible).
- ADR-0029: log-processor-service Spring AI 1.0 anomaly classifier
  (provides the verdict this ADR publishes).
- ADR-0030: `ParsedEventSink` fan-out to Loki + Quickwit (the
  swallow-exceptions sibling pattern; this ADR is its complement).
- memory.md LD79: SCSt outbound silently drops `Message<byte[]>`.
- memory.md LD89: never claim a ship without quoting CLI stdout in
  the same turn.
- memory.md LD100: src/test/resources/application.yml fully
  shadows main yml.
- memory.md LD106: Micrometer counters lazy-register; bootstrap-
  register at construct-time so the first scrape sees the family.
- memory.md LD112: tenant tag defaults to literal `unknown` not
  `null` to keep Prometheus cardinality bounded.
- memory.md LD117 (this ADR): Kafka consumer -> Kafka producer
  relay services do NOT need an outbox table; the Kafka offset is
  the durability mechanism.
- GitHub issue #80.
