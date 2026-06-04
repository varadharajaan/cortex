# P5 -> P6 handoff: `cortex.anomalies.v1` contract

> Single-page contract pinning everything the future P6
> `log-remediation-service` consumer needs in order to subscribe to
> `cortex.anomalies.v1` without reading the P5 producer source code.
> Shipped with P5.5 (closer for the P5 epic, PR for issue #82).
> Owner of the producer side: P5.4 (PR #81, ADR-0031, LD117).

---

## 1. Topic + Kafka coordinates

| Field          | Value                                                              |
|----------------|--------------------------------------------------------------------|
| Topic name     | `cortex.anomalies.v1` (overridable via `CORTEX_PROCESSOR_ANOMALIES_TOPIC` env or `cortex.processor.anomalies.topic` Spring property) |
| Source topic   | `cortex.logs.events.v1` (published by `log-ingest-service` outbox) |
| Producer       | `log-processor-service.AnomaliesPublisher`                         |
| Producer mode  | **Synchronous** `KafkaTemplate.send(..).get(10s)`. The consumer offset for `cortex.logs.events.v1` is the durability boundary -- on publish failure the source record is not acked and Kafka rebalance redelivery retries (LD117). No outbox table on the producer side. |
| Partitions     | Inherited from broker default. Partition selection by record key. |
| Key            | `event.eventId().getBytes(StandardCharsets.UTF_8)` -- the SHA-256 hex from the originating `LogEvent.eventId`. **Use this for downstream idempotency keys.** |
| Headers (mandatory) | `content-type: application/cloudevents+json` + `x-source-topic: cortex.logs.events.v1` |

---

## 2. CloudEvents 1.0 envelope (structured-mode JSON `data`)

The record value is the structured-mode CloudEvent JSON, written by
`io.cloudevents:cloudevents-json-jackson` `JsonFormat.serialize(..)`.
Field shape (ADR-0031):

| CloudEvent field  | Value                                                                                          |
|-------------------|------------------------------------------------------------------------------------------------|
| `specversion`     | `1.0`                                                                                          |
| `id`              | Newly generated UUIDv4 string (the anomaly event's own id, **not** the source `eventId`).      |
| `source`          | `/cortex/log-processor-service`                                                                |
| `type`            | `io.cortex.anomaly.v1`                                                                         |
| `subject`         | The originating `LogEvent.eventId` (SHA-256 hex). Use for idempotency joins with the source.   |
| `time`            | Anomaly emit time (ISO-8601 UTC, RFC 3339), produced by an injected `Clock`.                   |
| `datacontenttype` | `application/json`                                                                             |
| `data`            | Inline JSON object (next table).                                                               |

### 2.1. `data` payload schema

| `data.*` field | Type   | Notes                                                                                |
|----------------|--------|--------------------------------------------------------------------------------------|
| `eventId`      | string | SHA-256 hex from `LogEvent.eventId` -- duplicates `subject` for non-CE consumers.    |
| `tenantId`     | string | UUID of the originating tenant.                                                      |
| `level`        | string | Original log level (`ERROR`, `WARN`, ...).                                           |
| `message`      | string | Original log message verbatim.                                                       |
| `verdict`      | string | Classifier output, currently `ANOMALY` for everything emitted on this topic.         |
| `score`        | number | Classifier confidence in `[0.0, 1.0]`.                                               |
| `model`        | string | Classifier model id (e.g. `ollama:llama3` in dev, `azure-openai:gpt-4o` in prod).    |
| `reason`       | string | Optional free-form explanation. May be `null`.                                       |

---

## 3. Consumer guidance for P6

1. **Idempotency**: Treat `subject` (== `data.eventId`) as the idempotency key. Combine with `tenantId` if you fan playbooks per tenant.
2. **Deserialization**: Use `io.cloudevents:cloudevents-json-jackson` `JsonFormat.deserialize(bytes)` then `Reader<JsonNode>` (or generated DTO) for the `data` block. Do **not** hand-parse -- the round-trip is unit-tested on the producer side (`AnomaliesPublisherTest`).
3. **Schema enforcement**: Validate `specversion == "1.0"` and `type == "io.cortex.anomaly.v1"` defensively. Reject + DLQ anything else (the producer never emits other types on this topic, but P10 schema-registry will enforce server-side).
4. **Bootstrap counter**: Producer bumps `cortex.processor.anomalies.published_total{topic=cortex.anomalies.v1,tenant_id=unknown}` at startup so dashboards do not flatline on idle. Mirror this pattern on the consumer side for `cortex.remediation.dispatched_total`.
5. **Header forwarding**: `x-source-topic` is provided as a debugging aid -- log it but do not branch on it.
6. **DLQ**: When P6 ships, follow the ingest/processor pattern -- on rejection, republish to `cortex.anomalies.v1.dlq` with extra headers `x-orig-topic` + `x-failure-reason` (mirror of ADR-0027).

---

## 4. References

- **ADR-0031** -- `docs/adr/0031-log-processor-anomalies-publisher.md` -- producer design, 5 rejected alternatives.
- **ADR-0030** -- `docs/adr/0030-log-processor-fanout-sinks.md` -- the `ParsedEventSink` SPI list that the anomaly publish branches off of.
- **ADR-0029** -- `docs/adr/0029-log-processor-spring-ai-classifier.md` -- the upstream classifier that produces the `verdict`.
- **ADR-0027** -- `docs/adr/0027-log-ingest-cloudevents-binding.md` -- the CloudEvents envelope contract that the source records and the anomaly records both follow.
- **LD117** (`memory.md`) -- "for Kafka consumer -> Kafka producer relay services the Kafka offset itself IS the durability mechanism" -- justifies no outbox on the producer side.
- **P5.4 PR #81** -- `d2e6acc` -- the producer ship.

---

## 5. Open items for P6 (not blockers for handoff)

- Schema-registry binding (Apicurio / Confluent) is a P10 item.
- Azure OpenAI provider validation end-to-end on a real Azure subscription is a P9 item.
- GraphQL `subscription { anomalies(tenant: "...") }` is a P9 item.

P6 may start consuming `cortex.anomalies.v1` today against the JSON
envelope shape pinned above; no schema-registry dependency on the
consumer side until P10.
