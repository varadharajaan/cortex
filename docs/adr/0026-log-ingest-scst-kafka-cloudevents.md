# 0026 - log-ingest outbox publisher (Kafka + CloudEvents structured-mode JSON, SCSt outbound deferred)

Status: Accepted
Date: 2026-06-02
Deciders: CORTEX core engineering
Tags: P4.4b, ingest, outbox, kafka, cloudevents, B9.1, B9.2, B10.1, ADR-0005, ADR-0025, ADR-0027

## Context

ADR-0025 / P4.4a landed the transactional outbox **write path**:
every accepted `raw_logs` row commits a sibling `outbox_events`
row in the same per-row `REQUIRES_NEW` transaction, with status
`PENDING`, payload as a stable Jackson JSON envelope, and
`next_attempt_at = now()`. P4.4a deliberately shipped no broker
client, no scheduled poller, and no metrics that depend on
publish state.

P4.4b closes the **read path**: a `@Scheduled` poller drains
PENDING rows due for publish, wraps each row in a CloudEvents
1.0 envelope, ships it to the broker, and flips the row to
`PUBLISHED` after the broker acknowledges. Downstream consumers
(P5 indexer, P6 analytics, P7 self-healing) consume from the
broker instead of polling Postgres.

Five constraints shape this ADR:

- **B9.1 (strict rule)**: messaging must go through Spring Cloud
  Stream so the binder swap (Kafka in local-dev, Service Bus in
  Azure prod) is a configuration change, not a code change. The
  binder portability contract is the whole point of B9.
- **B9.2 (strict rule)**: payloads on the wire are CloudEvents
  1.0; Avro + Schema Registry is the eventual target but the
  P14 migration plan was reviewed in O8 and explicitly deferred
  out of P4. P4.4b ships structured-mode JSON
  (`application/cloudevents+json`) so consumers can decode the
  envelope without a registry round-trip.
- **B10.1 (strict rule)**: never publish a domain event from
  inside a `@Transactional` method. ADR-0025 already enforced
  this on the write side; P4.4b's poller runs outside the
  request handler entirely, so this is enforced by topology
  rather than discipline.
- **At-least-once delivery is the contract**, not exactly-once.
  Downstream consumers MUST be idempotent on
  `(tenant_id, event_id)`. The CloudEvents `id` attribute
  carries `event_id` so consumer-side dedupe needs no payload
  parsing.
- **Local-dev parity**: the smoke triangle (POST batch -> Postgres
  raw_logs row -> CloudEvent on Kafka topic) must run on a single
  Docker compose stack with no Schema Registry, no Confluent
  licence, and no cloud dependency.

## Decision drivers

- **D1. Per-row delivery guarantee.** A row is flipped to
  PUBLISHED only after the broker acknowledges under
  `acks=all`. A failed `send().get(timeout)` re-schedules the
  row with exponential backoff and leaves it `PENDING` so the
  next tick retries it. No "fire and forget" path.
- **D2. SCSt outbound path is non-functional at 2023.0.4.**
  Prototyped against `spring-cloud-starter-stream-kafka` with
  `StreamBridge.send("cortexLogsEventsV1-out-0", message)`.
  The producer initialised (broker `API_VERSIONS` round-trip
  observed) but never issued a `PRODUCE` request -- the message
  was silently dropped between the bound channel subscriber and
  the producer's network thread. Tried both
  `Message<byte[]>` and `Message<CloudEvent>` payloads, both
  with and without `use-native-encoding=true`, both with and
  without a manual `MessageConverter` bean. The observed
  failure mode is consistent with
  [spring-cloud-stream#2882](https://github.com/spring-cloud/spring-cloud-stream/issues/2882)
  and adjacent issues on the binder dispatch path. We did not
  pursue a deep root cause because the unblock is cheap and
  the SCSt portability story has no consumer until P4.4c when
  Service Bus comes online (ADR-0027). LD79.
- **D3. Spring Kafka is in our classpath transitively.** The
  SCSt starter pulls `spring-kafka` in as a dep, so a direct
  `KafkaTemplate<byte[], byte[]>` publish costs zero new
  dependencies and lets us keep all of SCSt's properties
  parsing (we are not yet using SCSt outbound; we keep the
  starter dep because consumer-side SCSt bindings come back
  in P4.4c or P5 when search starts subscribing).
- **D4. CloudEvents structured-mode JSON now, Avro at P14.**
  O8 already locked the Avro+SR deferral with the migration
  ladder. Structured-mode JSON is self-contained on the wire
  (specversion / type / source / id / data are all in one
  envelope) so a consumer needs only the
  `cloudevents-json-jackson` dep to decode -- no registry
  needed in the dev or test path.
- **D5. Backoff is monotonic and bounded.** `next_attempt_at`
  is bumped with an overflow-safe doubling backoff capped at
  `cortex.ingest.outbox.poller.backoff-max-ms` (default 5min).
  No row can starve another by occupying the batch indefinitely.
- **D6. Counters land with the poller, not the writer.**
  `cortex.ingest.outbox.published` + `cortex.ingest.outbox.failed`
  are registered eagerly at `OutboxPoller` construction so they
  appear on `/actuator/prometheus` from boot, before the first
  tick fires.

## Decision

### Topic contract

| Topic                       | Partitions (local) | Partitions (prod) | RF (local) | RF (prod) | Retention (prod) |
|-----------------------------|--------------------|-------------------|------------|-----------|------------------|
| `cortex.logs.events.v1`     | 1 (auto-create)    | 12 (P10 infra)    | 1          | 3         | 7 days (P10)     |

Local-dev relies on `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` so
the first publish creates the topic with broker defaults
(1 partition, RF=1). Explicit `kafka-topics --create` with the
prod-grade partition / RF / retention triple is a P10
infrastructure story; the contract above is the one consumers
should code against.

### Envelope contract (CloudEvents 1.0 structured-mode JSON)

```
content-type: application/cloudevents+json
{
  "specversion":      "1.0",
  "id":               "<outbox_events.event_id>",
  "source":           "/cortex/log-ingest-service",
  "type":             "io.cortex.logs.event.v1",
  "time":             "<outbox_events.created_at ISO-8601 UTC>",
  "subject":          "<outbox_events.tenant_id>",
  "datacontenttype":  "application/json",
  "data":             { ...the Jackson envelope persisted in outbox_events.payload... }
}
```

Notes:

- `id` is the raw_logs `event_id`, NOT the `outbox_events.id`
  surrogate key. Consumers dedupe on
  `(subject = tenant_id, id = event_id)`.
- `time` is the **ingest** time (`outbox_events.created_at`),
  not the publish time. The publish time would not match across
  consumer retries and is not stable across poller restarts.
- `dataschema` is intentionally blank until P14 Avro+SR
  migration per O8. Adding it now would require a hosted
  registry URL that local-dev does not have.
- `source` and `type` are env-overridable
  (`CORTEX_INGEST_OUTBOX_CE_SOURCE`,
  `CORTEX_INGEST_OUTBOX_CE_TYPE`) so a stage / canary deployment
  can shadow-publish to the same topic without colliding with
  prod consumers.

### Kafka record contract

- **Key**: UTF-8 bytes of `tenant_id`. Co-locates all events
  for one tenant on the same partition (in-tenant ordering
  preserved; cross-tenant ordering is NOT a contract).
- **Value**: UTF-8 bytes of the CloudEvents envelope JSON above.
- **Headers**: `content-type: application/cloudevents+json` so a
  consumer can route on header before parsing the value.

### Producer contract (KafkaConfig)

| Setting                                     | Value             | Rationale                                       |
|---------------------------------------------|-------------------|-------------------------------------------------|
| `bootstrap.servers`                         | env-driven        | `${CORTEX_KAFKA_BROKERS:localhost:9092}`        |
| `key.serializer` / `value.serializer`       | `ByteArraySerializer` | envelope is pre-serialised by CloudEvents `EventFormat` |
| `acks`                                      | `all`             | strongest durability; required for B9 contract  |
| `enable.idempotence`                        | `true`            | broker-side dedupe of producer retries          |
| `retries`                                   | `Integer.MAX_VALUE` | poller's own backoff is the timeout; let the producer chew on transient errors |
| `max.in.flight.requests.per.connection`     | `5`               | safe with idempotence enabled per KIP-679       |
| `compression.type`                          | `zstd`            | CloudEvents JSON envelopes compress 3-5x        |
| `request.timeout.ms`                        | `30000`           | tolerate one short broker pause                 |
| `delivery.timeout.ms`                       | `120000`          | bounds the poller's per-row latency             |

### Poller contract (OutboxPoller)

- `@Scheduled(fixedDelayString = "${cortex.ingest.outbox.poller.fixed-delay-ms:1000}", initialDelayString = ...)`
- Master switch: `cortex.ingest.outbox.poller.enabled` short-
  circuits the tick before any DB call, so a hot-flag-flip can
  stop publishing without a restart.
- Per tick: `findPendingDueForPublish(now, batchSize)` (matches
  the partial index `outbox_events_pending_idx`), then for each
  row call `publishOne(row)` synchronously:
  1. Build the CloudEvents envelope via `CloudEventEnvelopeBuilder`.
  2. Serialise via `JsonFormat` (cached static).
  3. Build `ProducerRecord` with key + value + content-type header.
  4. `kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, SECONDS)`.
  5. On success: `markPublished(id, now)` and bump the
     `cortex.ingest.outbox.published` counter.
  6. On `InterruptedException`: restore the interrupt flag
     and re-schedule the row (counted as a failure).
  7. On `ExecutionException | TimeoutException | RuntimeException`:
     `markFailureAndReschedule(id, attempts+1,
     now + nextBackoff(attempts+1), truncatedError)` and bump
     the `cortex.ingest.outbox.failed` counter.
- `last_error` is truncated to 1024 chars to bound row width
  even on a misbehaving stack trace.

### Backoff math

`OutboxPollerProperties.PollerProps.nextBackoff(attempts)`:

```
delay = backoffInitialMs * 2^(attempts-1)
delay = min(delay, backoffMaxMs)
return Duration.ofMillis(delay)
```

Implemented with overflow guards so a row that has failed 60
times in a row still returns the cap rather than wrapping to a
negative value.

### Boot wiring

- `CortexIngestApplication` already carries `@EnableScheduling`
  (landed in P4.4b unit tier).
- `KafkaConfig` provides `ProducerFactory<byte[], byte[]>` and
  `KafkaTemplate<byte[], byte[]>` beans -- both
  `outboxProducerFactory` / `outboxKafkaTemplate` are scoped to
  the outbox publisher and do NOT pollute the SCSt auto-config
  surface.
- `application.yml` adds `spring.kafka.bootstrap-servers` and
  the `cortex.ingest.outbox.{poller,cloudevent}` property tree
  (all env-overridable).

### Local-dev stack

`infra/local/docker-compose.smoke.yml` adds `cortex-smoke-kafka`
(`apache/kafka:3.8.0`, KRaft single-broker, two listeners:
`PLAINTEXT_HOST://localhost:9092` for the host JVM,
`PLAINTEXT://kafka:9093` for sibling containers). Auto-create
is ON so the first publish provisions the topic.

## Consequences

### Positive

- Lock-step write+publish: a `raw_logs` row that committed in
  P4.4a is delivered to consumers exactly once per outbox row
  (modulo at-least-once semantics; consumer-side dedupe handles
  the duplicates).
- The publisher path is fully decoupled from the request handler;
  the request handler stays at the P4.4a latency budget.
- Counters land before the first tick fires, so `/actuator/
  prometheus` is scrape-safe from boot.
- Local smoke + ITs do not need Schema Registry, Confluent
  licence, or Avro tooling.

### Negative

- One Kafka broker dep on the local-dev compose stack; another
  port allocation (9092 host, 9093 container, 9094 controller).
  Mitigation: pinned to `apache/kafka:3.8.0` and gated behind
  the `smoke` compose profile; not added to the default `dev`
  stack.
- The SCSt outbound binder portability story is deferred to
  P4.4c. ADR-0027 (Service Bus binder) will introduce a thin
  `OutboxEventPublisher` interface with one Kafka impl
  (KafkaTemplate, today's path) and one Service Bus impl
  (StreamBridge or a Service Bus SDK call, whichever lands
  green). Until then, swapping the broker requires a code
  change to `OutboxPoller`'s constructor.
- Structured-mode JSON envelopes are ~2x the wire size of a
  raw binary payload. `zstd` compression on the producer
  recovers most of the overhead. Migrating to Avro at P14 will
  cut it further (O8).
- Each `publishOne` blocks the poller thread for up to
  `SEND_TIMEOUT_SECONDS` (10s). A pathological broker outage
  caps the per-tick throughput at `batchSize / SEND_TIMEOUT_S`.
  This is acceptable for P4 because the row-level backoff
  ensures forward progress even when the broker is flapping;
  P5 / P6 may move to an async `send()` if throughput becomes
  the bottleneck.

### Neutral

- No DLQ in P4.4b. A row that exhausts `retries` stays at
  `PENDING` forever (it just gets re-tried at the capped
  backoff). The DLQ + a manual "give up after N attempts"
  knob lands in P4.4c.
- `cortex.ingest.outbox.publish.latency` timer (ADR-0025
  mentioned it as a PR-2 deliverable) is intentionally NOT in
  P4.4b. The two counters cover the success / failure
  dimensions; a histogram on the synchronous `send().get()`
  span adds complexity without obvious P5 consumer. Re-evaluate
  in P4.4c.

## Alternatives rejected

- **SCSt outbound binding via `StreamBridge.send(...)`.** This
  was the planned P4.4b approach and the binder portability
  story of B9. Prototyped, integration test failed
  reproducibly: producer initialised, `API_VERSIONS` round-trip
  succeeded, but no `PRODUCE` request ever reached the broker
  (verified via `kafka-console-consumer --from-beginning
  --max-messages 1 --timeout-ms 5000` which timed out). The
  `Message<byte[]>` payload never crossed the SCSt dispatcher.
  We could not isolate a root cause inside SCSt 2023.0.4 in a
  reasonable time and the unblock (KafkaTemplate) costs zero
  new dependencies. LD79. The SCSt binding will be re-tried
  at P4.4c when Service Bus needs the abstraction.
- **CloudEvents binary mode (one CE attribute per Kafka
  header) instead of structured-mode JSON.** Binary mode is
  wire-efficient and is the preferred mode for binary payloads.
  Rejected for P4.4b because: (a) structured-mode is
  self-contained which simplifies the smoke (a single
  `ConvertFrom-Json` on the consumed line decodes the whole
  envelope), (b) binary mode requires a `MessageConverter`
  alignment that the SCSt outbound failure poisoned, (c) Avro
  at P14 will use binary mode by definition so binary in P4 is
  a transitional optimisation we would un-pick anyway.
- **Avro + Schema Registry now.** Locked by O8: Avro
  migration is a P14 story. SR requires a hosted instance and
  client-side serdes; both are out of scope for the local-dev
  smoke triangle.
- **Async non-blocking `kafkaTemplate.send(record).whenComplete(...)`
  callback marking the row PUBLISHED.** Cleaner under load but
  the per-row mark-PUBLISHED would race with the next tick's
  fetch (the same row could be returned again before the
  callback flips it). Solvable with a "claimed_at" column and
  an "in-flight" status, but that's a bigger schema change than
  P4.4b's budget. The synchronous `get(timeout)` is the right
  shape for the per-row delivery guarantee we want today.
- **Manual `kafka-topics --create` boot step in the smoke
  compose.** Tighter contract but adds a sidecar container or
  a healthcheck-style init script. Auto-create is broker-default
  and the P10 prod story will pin partitions / RF / retention
  explicitly anyway.
- **Per-tenant rate limiting on the poller.** Out of scope for
  P4.4b. A pathological tenant cannot starve other tenants
  because the partial index returns rows in `(status,
  next_attempt_at)` order, not in tenant order. P5 may add a
  bucketed scheduler if observed.

## Amendment to ADR-0005 (message bus binder)

ADR-0005 stated the binder choice is SCSt with the Kafka
binder for local-dev and the Service Bus binder for Azure
prod. P4.4b narrows this **for outbound publishing only**:
the outbox publisher uses Spring Kafka's `KafkaTemplate`
directly while SCSt outbound is non-functional at
`spring-cloud-stream@2023.0.4` (LD79). Consumer-side
bindings (P5 search indexer subscribe, P6 analytics
subscribe, P7 self-healing subscribe) remain unchanged and
will use SCSt as planned. The binder-portability abstraction
on the publish side is re-introduced in P4.4c via the
`OutboxEventPublisher` interface defined in ADR-0027 once
the Service Bus binder ships.

## Amendment to ADR-0025

ADR-0025 stated the publisher would consume the
`outbox_events.payload` column as the on-wire payload. This
ADR refines the contract: the publisher reads
`outbox_events.payload` as the **CloudEvent `data`** (the
inner object), then wraps it in a CloudEvents 1.0 envelope
whose attributes come from `outbox_events` columns and the
`cortex.ingest.outbox.cloudevent.{source,type}` properties.
The on-wire bytes are the envelope JSON, not the raw payload.
ADR-0025's "opaque bytes to the poller" framing was the
P4.4a-scope writer-side view; P4.4b adds the envelope
contract on top.
