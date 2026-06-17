# 0030. log-processor-service ParsedEventSink fan-out (Loki + Quickwit)

- Status: accepted
- Date: 2026-06-04
- Deciders: @varadharajaan
- Tags: processor, sink, loki, quickwit, fan-out, observability

## Context and problem statement

`log-processor-service` consumes parsed log events off
`cortex.logs.events.v1` (P5.1 / ADR-0028), classifies them through the
Spring AI anomaly classifier (P5.2 / ADR-0029), and then needs to make
the enriched event browsable in two downstream search tiers:

1. **Grafana Loki** — the operator-facing live tail + label-driven
   browse surface. Optimised for high-cardinality streams keyed by a
   small label set (tenant + service + level + anomaly).
2. **Quickwit** — the warm-tier full-text search index over the same
   events plus the classifier verdict. Optimised for ad-hoc forensic
   search on the message body and the structured labels map.

P5.0..P5.2 left both as TODO. P5.3 is the fan-out closer: every
classified event must arrive at both downstream tiers without
blocking the Kafka offset commit and without coupling the consumer
to either system's availability.

The plan + memory + checkpoint pinned this work to the same patterns
the gateway P3.3 (ADR-0018) and processor P5.2 (ADR-0029) settled on:
typed `@ConfigurationProperties`, HTTP/1.1-pinned outbound transport
via `JdkClientHttpRequestFactory` (LD42), and per-impl
`@ConditionalOnProperty` feature gates so a stock dev boot loads
zero sink beans.

## Decision drivers

- D1 — must NOT block the Kafka happy path: a sink HTTP failure or
  500ms latency spike at Loki or Quickwit must not delay
  `ack.acknowledge()`. The current consumer is single-threaded per
  partition; a blocking sink would directly cap pipeline throughput.
- D2 — must NOT throw past the consumer: a sink raising a
  `RuntimeException` must not bubble up to spring-kafka because that
  would rewind the offset and re-deliver the event on the next poll.
- D3 — must be feature-gated per-sink so an operator can disable
  either tier independently (dev typically runs both off; smoke +
  prod typically run both on).
- D4 — must emit Micrometer counters per sink + per tenant + per
  failure reason so an outage in one tier is visible on the
  processor's `/actuator/prometheus` scrape without scraping the
  sink's own metrics.
- D5 — must pin outbound HTTP to HTTP/1.1 per LD42 so the same WireMock
  smoke harness (port 8094 in the cortex-smoke compose stack) keeps
  working unchanged.
- D6 — must give Quickwit a deterministic `id` (= `event.eventId()`)
  so a Kafka rebalance redelivery results in a server-side
  no-op on the search tier (no duplicate doc). Loki has no
  equivalent dedupe primitive on its push API; accept the duplicate
  exposure and document it.

## Considered options

1. **In-process fan-out: a `ParsedEventSink` SPI list injected into
   the existing `LogEventConsumer`, two impls
   (`LokiSink`, `QuickwitSink`), each gated by
   `@ConditionalOnProperty`, each speaking HTTP/1.1 via a shared
   `RestClient` factory.** — accepted.
2. **Kafka Connect: deploy the Grafana Loki sink connector + the
   Quickwit Kafka source connector against the same topic.** —
   rejected: introduces a new runtime surface (Connect cluster) on
   day one of P5.3 just to defer two ~150 LOC sinks; ops cost +
   deployment surface dwarfs the benefit; Quickwit's Kafka source
   is GA but still recommends batching tuning we'd own anyway.
3. **Vector forwarder: deploy `vector` as a sidecar / DaemonSet that
   consumes the Kafka topic and fans out to both sinks.** — rejected:
   adds a Rust runtime to the surface, splits the deployment graph,
   and offloads the classifier verdict (Vector knows nothing about
   `Classification`). Would require also publishing the verdict to
   Kafka or to a side-channel, raising the schema-evolution cost.
4. **Two binder approach: route the parsed event through a second
   Kafka topic per sink and let two dedicated downstream consumers
   handle the HTTP push.** — rejected: triples the Kafka topic
   count, doubles the schema-evolution surface, and adds two new
   consumer groups + offsets to monitor for ~zero throughput gain
   over option 1 at P5.3 scale.
5. **Synchronous blocking sinks in the consumer thread (no SPI, no
   try-catch).** — rejected outright: violates D1 + D2. Cited only
   to anchor why the SPI list pattern is the obvious choice.

## Decision outcome

Chosen option: **option 1, in-process `ParsedEventSink` SPI list
injected into `LogEventConsumer`, two impls (`LokiSink`,
`QuickwitSink`), each `@ConditionalOnProperty`-gated, each speaking
HTTP/1.1 via a shared `RestClient` factory.**

### Implementation shape

- New package `io.cortex.processor.sink` with:
  - `ParsedEventSink` SPI — `void send(RawLogEvent, Classification)`
    plus `String name()`. Contract: implementations MUST NOT throw;
    they MUST tick `SinkMetrics.{loki|quickwit}Failed(...)` on every
    failure category and return.
  - `SinkProperties` — `@ConfigurationProperties("cortex.processor.sinks")`
    record with nested `Loki(enabled, baseUrl, requestTimeout)` +
    `Quickwit(enabled, baseUrl, index, requestTimeout)` records.
    Defensive defaults on canonical ctors so blank yml entries do
    not NPE. Defaults: both `enabled=false`, Loki
    `http://localhost:3100`, Quickwit `http://localhost:7280` +
    index `cortex-logs`, both `requestTimeout=PT5S`. Bound via
    `@EnableConfigurationProperties(SinkProperties.class)` placed
    on `SinkMetrics` (always loaded) so the properties bind even
    when both sinks are disabled (which would otherwise leave the
    annotation on a `@ConditionalOnProperty enabled=true` bean that
    never loads).
  - `SinkMetrics` — `@Component("cortexSinkMetrics")`. Two counter
    families per sink:
    - `cortex.processor.sink.{loki|quickwit}.published_total{tenant_id}`
    - `cortex.processor.sink.{loki|quickwit}.failed_total{tenant_id, reason}`
    with `reason` drawn from a bounded enum
    (`HTTP_STATUS`, `TIMEOUT`, `TRANSPORT`, `SERIALIZATION`,
    `UNKNOWN`). Counters lazy-registered through a `ConcurrentMap`
    keyed by `metric|tenant|reason` per LD106. Tenant tag
    sanitised to `unknown` when blank/null.
  - `LokiSink` — `@Component @ConditionalOnProperty(prefix="cortex.processor.sinks.loki", name="enabled", havingValue="true")`.
    Posts `{streams:[{stream:{tenant_id,service,level,anomaly}, values:[[tsNanos,line]]}]}`
    to `{base-url}/loki/api/v1/push`. Public ctor builds an
    HTTP/1.1-pinned `RestClient` via `JdkClientHttpRequestFactory`;
    package-private ctor accepts a pre-built `RestClient` for unit
    tests against an in-process JDK `HttpServer`.
  - `QuickwitSink` — `@Component @ConditionalOnProperty(prefix="cortex.processor.sinks.quickwit", name="enabled", havingValue="true")`.
    Posts an NDJSON doc per event to `{base-url}/api/v1/{index}/ingest`.
    The doc sets `id = event.eventId()` so Quickwit performs
    server-side dedupe on rebalance redelivery (D6). Other fields
    flatten `RawLogEvent` + the classifier verdict
    (`anomaly`, `severity`, `reason`).

- `LogEventConsumer` gains a constructor-injected
  `List<ParsedEventSink>` (Spring will inject an empty list when no
  sinks are enabled). The list is defensively copied with
  `List.copyOf(sinks)` (null → `Collections.emptyList()`). A new
  private `fanOut(RawLogEvent, Classification)` method iterates the
  list inside an outer `try { ... } catch (RuntimeException)` so a
  defective sink can never bubble up and rewind the Kafka offset
  (D2). `fanOut(...)` is called inline AFTER the
  `events.classified_total{outcome}` counter ticks and BEFORE
  `ack.acknowledge()` on the success branch.

- Both sinks pin outbound HTTP to HTTP/1.1 via
  `JdkClientHttpRequestFactory` (D5; mirrors gateway P3.3 +
  processor P5.2).

- Exception → reason mapping (identical in both sinks):
  - `RestClientResponseException` → `HTTP_STATUS`
  - `ResourceAccessException` with cause
    `HttpTimeoutException` / `TimeoutException` → `TIMEOUT`
  - `ResourceAccessException` otherwise → `TRANSPORT`
  - Body serialisation `RuntimeException` before HTTP call →
    `SERIALIZATION`
  - Any other `RuntimeException` → `UNKNOWN`

- `application.yml` (main + test) declares the full
  `cortex.processor.sinks.{loki,quickwit}.*` block with env-var
  overrides + `enabled=false` defaults. The test yml fully shadows
  the main yml per LD100 so `@SpringBootTest` context loads pick
  up the typed properties without external env state.

- WireMock stubs in `infra/local/wiremock/mappings/`:
  - `loki-push.json` — `POST /loki/api/v1/push → 204`.
  - `quickwit-ingest.json` —
    `POST /api/v1/{index}/ingest → 200`
    with body `{"num_docs_for_processing":1}`.

- New per-phase script folder `scripts/p5-3/` (gitignored per LD86)
  mirroring P5.2a: `boot-full-stack.ps1`, `smoke-p5-3.ps1`,
  `newman-leg-c.ps1`, `teardown-full-stack.ps1`, `README.md`. The
  boot script flips
  `CORTEX_PROCESSOR_SINKS_{LOKI,QUICKWIT}_ENABLED=true` and points
  both base-urls at WireMock `:8094`. The smoke script asserts the
  new sink published_total counters AND WireMock journal counts on
  the two sink endpoints.

### Positive consequences

- Single deployable: no new pod (D1 / D2 satisfied via try-catch
  + lazy counter ticks).
- Fan-out is fully under our control: the verdict + the event
  arrive at both sinks in one process, no schema duplication.
- Operators can dark-launch a tier (e.g. cut Quickwit while leaving
  Loki on) via a single env-var flip; the disabled sink does not
  even instantiate (D3).
- Per-tenant + per-reason failure counters give an at-a-glance view
  of which sink is degraded and why (D4).
- HTTP/1.1 pin keeps the WireMock smoke harness reusable (D5).
- Quickwit dedupe via `id=eventId` removes the duplicate-doc risk
  on Kafka rebalance (D6).

### Negative consequences

- Loki has no idempotency on the push API, so a Kafka rebalance
  redelivery results in a duplicate chunk on the live tail
  (acknowledged D6 trade-off). The P5.x roadmap mentions a per-event
  hash label as a follow-up, but P5.3 accepts the exposure.
- Two synchronous HTTP calls per event on the consumer thread. With
  both sinks enabled the per-pod throughput ceiling drops to roughly
  `consumer-threads * (1 / (loki_latency + quickwit_latency))`.
  P5.4 / P6 will introduce an outbox to decouple the consumer from
  the sink HTTP latency; until then, P5.3 ships synchronous fan-out
  on the same thread for simplicity.
- One extra autowire dependency on `LogEventConsumer`. Existing
  integration tests (`LogEventConsumerKafkaIT`) bind via Spring,
  so they tolerate the empty list naturally; the unit test
  (`LogEventConsumerTest`) is updated to inject two
  `Mockito.mock(ParsedEventSink.class)` instances with matching
  `name()` stubs so the fan-out call is asserted.

### References

- ADR-0006: AI provider abstraction (Ollama local, Azure OpenAI prod).
- ADR-0018: log-gateway NL-to-LogQL via Spring AI 1.0 + Ollama + WireMock.
- ADR-0026: log-ingest-service CloudEvents 1.0 producer envelope.
- ADR-0027: log-ingest-service DLQ counters binder.
- ADR-0028: log-processor-service consumer (direct spring-kafka per LD79).
- ADR-0029: log-processor-service Spring AI 1.0 anomaly classifier.
- memory.md LD42: HTTP/2 h2c upgrade collision with WireMock 3.x.
- memory.md LD79: direct spring-kafka @KafkaListener.
- memory.md LD86: phase-folder convention + per-phase scripts gitignored.
- memory.md LD100: src/test/resources/application.yml fully shadows main yml.
- memory.md LD106: Micrometer counters lazy-registered.
- GitHub issue #76.
