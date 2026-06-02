# 0027 - log-ingest DLQ + 3 Micrometer counters + Service Bus binder gate

Status: Accepted
Date: 2026-06-02
Deciders: CORTEX core engineering
Tags: P4.4c, ingest, outbox, kafka, dlq, micrometer, service-bus, B9.1, B9.2, B10.1, ADR-0025, ADR-0026

## Context

ADR-0026 / P4.4b landed the outbox publisher path: a `@Scheduled`
poller drains PENDING `outbox_events` rows to
`cortex.logs.events.v1` via `KafkaTemplate<byte[], byte[]>` as
CloudEvents 1.0 structured-mode JSON envelopes, with exponential
backoff on transient failures and two counters
(`cortex.ingest.outbox.published`,
`cortex.ingest.outbox.failed`). ADR-0026 also explicitly deferred
three items to this ADR:

- **DLQ topic**: a row that exhausts its retry budget has nowhere
  to go and stays `PENDING` forever (just gets re-tried at the
  capped backoff). Operators have to grep `outbox_events.last_error`
  and manually intervene.
- **Service Bus binder gate**: B9.1 says messaging MUST go through
  a configurable seam so the binder swap (Kafka local-dev ->
  Service Bus Azure prod) is a configuration change, not a code
  change. ADR-0026 publishes directly via `KafkaTemplate`, so
  today the binder choice is hard-wired.
- **DLQ counter**: a metric the SLO dashboard needs to fire on
  retry-exhausted -> DEAD transitions distinct from the
  per-attempt `failed` counter.

P4.4c (this ADR) closes all three. The result is the final piece
of the P4.4 epic; once it lands P4.5 closes P4 and P5 (indexer)
moves to IN PROGRESS.

Five constraints shape this ADR:

- **B9.1 (strict rule)**: the binder swap must be a config flip.
  Both publishers MUST share the same Java seam so neither the
  poller nor any downstream code knows which one is wired.
- **B9.2 (strict rule)**: DLQ records carry the exact same
  CloudEvents 1.0 structured-mode JSON envelope as the production
  record. A consumer that subscribes to the DLQ topic gets the
  same bytes it would have got from the production topic, plus
  two Kafka headers that explain why the row was dead-lettered.
- **B10.1 (strict rule)**: the DLQ publish stays outside any
  `@Transactional` boundary -- it runs on the poller thread, same
  as the production publish. Per-row failures cannot leak into
  the request handler's tx.
- **Bounded cardinality**: the three counters share a fixed tag
  surface (`topic`, `tenant_id`, `reason`). `reason` is an
  allowlist of 5 short strings; `tenant_id` is bounded by the
  tenants table; `topic` is one of two known values. The
  tag-budget envelope is documented in this ADR so the next
  feature does not silently blow it up.
- **Local-dev parity**: the smoke triangle and the IT cover the
  DLQ path on the same single-broker Kafka container the rest of
  P4 uses. No Schema Registry, no Confluent licence, no cloud
  dependency.

## Decision drivers

- **D1. DLQ as a separate Kafka topic, not an in-table dead-letter
  column.** Two alternatives were on the table:
  - Add a `dead_letter` boolean to `outbox_events` and let an
    operator scan the table.
  - Publish to a sibling topic
    `cortex.logs.events.v1.dlq` with the same envelope bytes plus
    failure-tracking headers.
  The sibling-topic option wins because it preserves the
  publish-side contract: a consumer that subscribes to the DLQ
  topic can be the same Spring Cloud Stream consumer codepath
  that subscribes to the production topic (just with a different
  `destination`), and the broker handles partitioning + replay
  exactly the same way it does for production records. KEDA can
  scale a DLQ consumer on topic depth in P10 without inventing a
  Postgres-backed metric source. The in-table option would also
  require yet another partial index and yet another sweep job to
  drain it.
- **D2. Retry-exhausted boundary is `backoff-max-ms`, not a
  separate `max-attempts`.** The poller already enforces capped
  exponential backoff (ADR-0026: `delay = min(initial * 2^(n-1),
  max)`). A second knob (`max-attempts`) would partially overlap
  with `backoff-max-ms` and create a configuration matrix where
  the two values can disagree (e.g. `max-attempts=10` but
  `backoff-max-ms=100ms` -> max time-to-DLQ ~10s; `max-attempts=10`
  but `backoff-max-ms=5min` -> max time-to-DLQ ~50min). The
  retry budget collapses cleanly into one knob if we say "a row
  is exhausted when its next uncapped backoff would touch the
  cap", which is exactly what
  `OutboxPollerProperties.PollerProps.isRetryExhausted(attempts)`
  computes:
  ```
  uncapped = backoff-initial-ms << (attempts - 1)
  return uncapped >= backoff-max-ms
  ```
  This is monotonic in `attempts`, overflow-safe via an explicit
  shift-width guard, and matches the operator's mental model
  ("how long am I willing to wait between retries before I give
  up?"). One knob, one boundary, no inconsistency surface.
- **D3. Three counters, not one counter with a `result` tag.**
  An alternative is `cortex.ingest.outbox.events{result=
  published|failed|dlq, ...}`. We rejected it because:
  - `result=dlq` would still need a `reason` tag for the SLO
    dashboard to slice by failure mode, but `result=published`
    has no `reason` (success has no cause). Sharing one metric
    forces every series to either carry `reason=""` for the
    success path (waste) or use Micrometer's per-tag-set
    counters (cardinality explodes: success rows would emit a
    `reason=""` series for each `(topic, tenant_id)` pair, then
    failed/dlq would emit a `reason=<one of 5>` series for the
    same pair, so the success path's cardinality is roughly
    doubled by carrying a tag it doesn't use).
  - Prometheus query syntax for "all failures excluding DLQ" is
    cleaner when failed and dlq are distinct names than when
    they're filtered by a `result!="dlq"` matcher.
  - The three counters total six unique meter ids in steady
    state (one per `(topic, tenant_id)` for published, one per
    `(topic, tenant_id, reason)` for failed, one per
    `(dlq-topic, tenant_id, reason)` for dlq), which is well
    below the per-service tag-budget threshold documented in
    O8 / Part 21.
- **D4. `tenant_id` is a tag; `event_id` is not.** `tenant_id`
  is bounded by the tenants table (~hundreds, B9.4 plans for
  ~thousands). `event_id` is one-per-record so tagging by it
  would create one unique series per record, which is the
  textbook Prometheus cardinality bomb. The trade-off is that
  the dashboard cannot drill down to a specific event from the
  counter alone -- but the row id is still queryable via
  `outbox_events.event_id` joined back to `raw_logs.event_id`,
  and the DLQ record itself carries the event_id in the
  CloudEvents `id` attribute (so a DLQ consumer can correlate
  back to the source row without paying the cardinality cost).
- **D5. `@ConditionalOnProperty` over a Spring profile for the
  publisher choice.** Three alternatives:
  - One profile per binder (`-Dspring.profiles.active=
    kafka-publisher` vs `-Dspring.profiles.active=
    servicebus-publisher`). Coupled to the lifecycle profile
    (dev / staging / prod) so a `dev,servicebus-publisher`
    combination needs explicit profile composition.
  - A single property `cortex.outbox.publisher` gated via
    `@ConditionalOnProperty(name="cortex.outbox.publisher",
    havingValue="kafka", matchIfMissing=true)` /
    `havingValue="servicebus"`. The two impls live in the same
    package, share the `OutboxEventPublisher` interface, and
    swap via one env var (`CORTEX_OUTBOX_PUBLISHER`) without
    touching the profile axis.
  - A `@Bean` factory that selects the impl based on a config
    value. Adds boilerplate and obscures the choice in
    Spring's startup logs.
  Option 2 wins: the bean discovery log on startup shows which
  publisher is active, the swap survives every other profile
  axis (dev / staging / prod / smoke / it), and a hybrid
  scenario (dev profile + servicebus publisher for an Azure
  staging deployment dogfooding the dev DB) is one env var.
- **D6. What we explicitly defer.** Three items belong to later
  phases:
  - **Real Azure Service Bus connector**: P4.4c ships only the
    @ConditionalOnProperty stub
    (`ServiceBusOutboxPublisher.publish/publishDlq` both throw
    `UnsupportedOperationException` with a "P4.4c stub" message).
    The real Service Bus SDK call or the SCSt
    `servicebus` binder (depending on what lands green) is a
    P10 infra-phase concern.
  - **KEDA autoscaling on DLQ depth**: needs a real Kubernetes
    cluster and the Service Bus binder; P10 / P11 infra.
  - **Grafana panel JSON for the three counters**: P17
    dashboards epic owns Grafana JSON authoring; P4.4c only
    wires the metric surface that the panels will read.

## Decision

### Topic contract

| Topic                             | Partitions (local) | Partitions (prod) | RF (local) | RF (prod) | Retention (prod)              |
|-----------------------------------|--------------------|-------------------|------------|-----------|-------------------------------|
| `cortex.logs.events.v1`           | 1 (auto-create)    | 12 (P10 infra)    | 1          | 3         | 7 days (P10)                  |
| `cortex.logs.events.v1.dlq`       | 1 (auto-create)    | 3  (P10 infra)    | 1          | 3         | 30 days (P10; operator drain) |

Local-dev relies on `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` so
the first DLQ publish creates the topic with broker defaults
(1 partition, RF=1). Explicit
`kafka-topics --create --partitions 3 --replication-factor 3 ...`
with the 30-day retention is a P10 infrastructure story; the
contract above is what consumers should code against.

### DLQ record contract

DLQ record value is BYTE-FOR-BYTE the same CloudEvents 1.0
envelope the production topic would have carried (or, if the
envelope build itself was the failure, the raw outbox payload
bytes). Two new Kafka headers explain the dead-letter:

| Header              | Value                                                                                |
|---------------------|--------------------------------------------------------------------------------------|
| `content-type`      | `application/cloudevents+json` (same as production)                                  |
| `x-orig-topic`      | `cortex.logs.events.v1` (the topic the publish would have targeted)                  |
| `x-failure-reason`  | One of the 5 strings below (matches the `reason` tag on the `failed` / `dlq` counters) |

### Failure-reason allowlist

The `reason` tag on the `failed` and `dlq` counters + the
`x-failure-reason` DLQ header use one of these 5 strings:

| String              | Source throwable                                                          |
|---------------------|---------------------------------------------------------------------------|
| `kafka.timeout`     | `java.util.concurrent.TimeoutException` (Kafka producer ack timeout)      |
| `kafka.interrupted` | `java.lang.InterruptedException` (poller thread interrupted mid-send)     |
| `kafka.execute`     | `ExecutionException` or any `org.apache.kafka.*` exception (broker error) |
| `outbox.poison`     | `RuntimeException` from `CloudEventEnvelopeBuilder` or `JsonFormat`       |
| `unknown`           | Anything else (fallback so the tag set stays bounded by definition)       |

`FailureReason.fromThrowable(Throwable)` is the single mapper.
Adding a new reason requires extending the allowlist here AND
in `FailureReason.java` AND in the smoke / IT assertions; no
runtime path may emit a tag value not on this list.

### Retry-exhausted boundary

Per D2 the row is dead-lettered on the attempt whose uncapped
exponential backoff first reaches `backoff-max-ms`. Concrete
examples for the default settings
(`backoff-initial-ms=1000`, `backoff-max-ms=300_000`):

| Attempt (new) | Uncapped delay ms | Cap reached? | Action                       |
|---------------|-------------------|--------------|------------------------------|
| 1             | 1_000             | no           | reschedule, attempts=1       |
| 2             | 2_000             | no           | reschedule, attempts=2       |
| 3             | 4_000             | no           | reschedule, attempts=3       |
| ...           | ...               | no           | ...                          |
| 8             | 128_000           | no           | reschedule, attempts=8       |
| 9             | 256_000           | no           | reschedule, attempts=9       |
| 10            | 512_000           | **yes**      | DLQ publish + status=DEAD    |

A misconfigured deployment with `backoff-max-ms < backoff-initial-ms`
(e.g. the test profile uses `1L`) dead-letters on the first
failure (`uncapped=1_000 >= cap=1`). That is intentional: the
operator asked for "give up immediately on failure" and the
predicate honours it.

### Three counters

| Name                                       | Tags                              | Increment trigger                                                         |
|--------------------------------------------|-----------------------------------|---------------------------------------------------------------------------|
| `cortex.ingest.outbox.published`           | `topic`, `tenant_id`              | row drained successfully + flipped to PUBLISHED                           |
| `cortex.ingest.outbox.failed`              | `topic`, `tenant_id`, `reason`    | per ATTEMPT (NOT per row); a single row that fails N times bumps this N times |
| `cortex.ingest.outbox.dlq`                 | `topic`, `tenant_id`, `reason`    | per ROW that transitioned to DEAD AND was successfully written to the DLQ topic |

The DLQ counter is incremented ONLY after the DLQ publish
succeeded. If the DLQ publish itself fails, the row is rolled
back to PENDING with `last_error="dlq-failed: ..."` and the
counter is NOT bumped (because no record actually landed on the
DLQ topic). The next tick retries the entire publish path; if
the production publish then succeeds, the row reaches
PUBLISHED normally (we treat the prior failure budget as spent
attempts, not as a permanent verdict). This matches the
at-least-once contract: a row never silently disappears.

### Publisher seam

```java
public interface OutboxEventPublisher {
    void publish(OutboxEvent row, byte[] value, String contentType);
    void publishDlq(OutboxEvent row, byte[] value, String contentType,
                    String origTopic, String reason);
}
```

Two impls in the same package:

- `KafkaOutboxPublisher` (`@ConditionalOnProperty(name=
  "cortex.outbox.publisher", havingValue="kafka", matchIfMissing=true)`):
  the production path, wraps `KafkaTemplate<byte[], byte[]>`,
  uses the `ProducerRecord` headers contract documented above.
- `ServiceBusOutboxPublisher` (`@ConditionalOnProperty(name=
  "cortex.outbox.publisher", havingValue="servicebus")`):
  stub. Both methods throw
  `UnsupportedOperationException("P4.4c stub -- real Azure SB
  binder lands in P10 infra phase ...")`. The class exists at
  P4.4c to prove the binder gate works; the real connector
  lands later.

The poller (`OutboxPoller`) sees only the interface; the
@ConditionalOnProperty mechanism ensures exactly one bean is
wired at boot. Mis-configuration (typo on the property, or no
matching impl) blows up at context start with a
`NoSuchBeanDefinitionException`, NOT at first publish.

### Outbox status lifecycle

| State       | Transition rule                                                                              |
|-------------|----------------------------------------------------------------------------------------------|
| `PENDING`   | initial; set by `OutboxEvent.pending(...)` inside the P4.4a per-row tx                       |
| `PUBLISHED` | poller publish succeeded; `published_at` stamped                                             |
| `FAILED`    | legacy; retained for backward compatibility but the P4.4c poller no longer writes it. The DLQ path uses `DEAD`. |
| `DEAD`      | new in P4.4c; poller publish exhausted retries AND DLQ publish succeeded; row is terminal    |

V4 Flyway migration drops the V3 `CHECK status IN
('PENDING','PUBLISHED','FAILED')` and re-adds it with
`'DEAD'` in the allowlist. The migration is forward-only;
production rows in `FAILED` (none today; the P4.4b poller did
not write `FAILED`) would stay valid under the new check.

### Boot wiring

- `KafkaConfig` (no change): provides `ProducerFactory<byte[],
  byte[]>` and `KafkaTemplate<byte[], byte[]>` as before.
- `KafkaOutboxPublisher` (new): consumes the existing
  `KafkaTemplate` bean. No additional Spring Kafka beans.
- `ServiceBusOutboxPublisher` (new): no dependencies; the stub
  is a single-arg `()` ctor `@Component`.
- `OutboxPoller` (refactored): constructor now takes
  `OutboxEventPublisher` instead of `KafkaTemplate`; also takes
  the new `OutboxMetrics` helper. The two counter constants
  that used to live on the poller moved to `OutboxMetrics` so
  the test surface stays clean.
- `OutboxMetrics` (new): centralises the three
  `Counter.builder().tag(...).register(registry).increment()`
  calls so the cardinality budget is enforced in one place.
- `application.yml` adds `cortex.outbox.publisher` (sibling of
  `cortex.ingest`) so the new property tree mirrors the binder
  choice docs.

### Local-dev stack

No changes to `infra/local/docker-compose.smoke.yml`. The
existing single-broker Kafka has auto-create-topics ON, so the
DLQ topic provisions on demand on the first `publishDlq`. The
P4.4c smoke (`scripts/p4-4c/smoke-p4-4c.ps1`) regression-tests
the P4.4b happy path, verifies the three counter families on
`/actuator/prometheus`, verifies the publisher beans wiring on
`/actuator/beans`, and confirms the DLQ topic is reachable via
`kafka-topics --list`. The end-to-end poison-row -> DLQ proof
lives in `OutboxPollerKafkaIT.poisonRowExhaustsRetriesAndPublishesToDlqWithFailureHeaders`
where the test can inject a controlled-failure publisher in-process
without disrupting the shared broker.

## Consequences

### Positive

- Operators get a real DLQ topic they can `kafka-console-consumer`
  on, with two headers that explain why each record landed there.
- The binder swap from Kafka to Service Bus is one env var
  (`CORTEX_OUTBOX_PUBLISHER=servicebus`), exactly as B9.1
  requires. The P10 infra phase only has to fill in
  `ServiceBusOutboxPublisher`'s two methods; no change to the
  poller, no change to the topic contract, no change to the
  metric surface.
- Cardinality stays bounded by construction: the
  `FailureReason` allowlist is the single source of truth for
  `reason` values; tag review at PR time only has to check
  whether the diff touches `FailureReason.java`.
- Counters land BEFORE the first tick fires (the
  `Counter.builder().register(registry)` registers the meter
  eagerly even though the count is zero), so `/actuator/
  prometheus` is scrape-safe from boot. SLO dashboards do not
  need to special-case the first-publish race.

### Negative

- The DLQ topic on the local-dev compose stack is created
  on-demand by the first failing publish (auto-create). A
  totally healthy local run never provisions the topic. The
  P4.4c smoke explicitly probes for it (and creates it via
  `kafka-topics --create` as a fallback) so the smoke result
  stays deterministic.
- `ServiceBusOutboxPublisher` is a stub. A deployment that
  sets `CORTEX_OUTBOX_PUBLISHER=servicebus` will boot but every
  publish will fail with `UnsupportedOperationException` --
  attempting it in production would dead-letter every row
  (DLQ publish would also throw, so rows stay PENDING with
  `last_error="dlq-failed: UnsupportedOperationException: ..."`).
  This is intentional for P4.4c: it proves the gate works and
  forces P10 to land the real connector before the prod flip.
- The legacy `FAILED` enum value is retained for backward
  compatibility with any future rollback. A future cleanup
  ADR could drop it once we are confident no production rows
  will need it.
- The retry-exhausted boundary collapses to ONE knob
  (`backoff-max-ms`). An operator who wanted "20 attempts
  regardless of backoff" cannot express that today. We accept
  this because the operator UX of "how long am I willing to
  wait" is far more common than "how many times exactly should
  I retry"; if the latter becomes a real ask, a future ADR can
  add an OR-clause to `isRetryExhausted` without breaking the
  one-knob default.

### Neutral

- No new HTTP endpoint. DLQ inspection is via
  `kafka-console-consumer` against `cortex.logs.events.v1.dlq`,
  same as inspecting the production topic. Adding an admin
  REST endpoint to peek at the DLQ would re-introduce the
  Postgres-backed dead-letter shape we rejected in D1.
- No Newman / Postman changes. The DLQ is operator-facing,
  not user-facing; the public API surface stays at the P4.4b
  16-request / 39-assertion matrix.
- The poller's per-tick latency floor goes up by one Kafka
  round-trip per dead-lettered row (the extra `publishDlq`
  call). Acceptable because the dead-letter path is the cold
  path; the hot path (successful publish) is unchanged.

## Migration ladder (relative to ADR-0026)

| Step | What stays | What changes |
|------|------------|--------------|
| P4.4c (this) | KafkaTemplate publish path; CloudEvents envelope; per-row backoff | + DLQ publish + DEAD status + 3 counters + binder gate + ServiceBusOutboxPublisher stub |
| P10 infra    | All of the above + DLQ topic exists in prod | + real Service Bus binder (or SCSt servicebus binder) + KEDA autoscaling on DLQ depth + explicit topic provisioning with prod-grade partition / RF / retention |
| P14          | Counter contract                          | CloudEvents envelope -> Avro + Schema Registry (B9.2 / O8) |
| P17          | Counter names and tag set                 | Grafana panel JSON for the three counters + DLQ depth alert |

## References

- ADR-0025 (P4.4a outbox write path).
- ADR-0026 (P4.4b outbox -> Kafka publisher; CloudEvents 1.0
  structured-mode JSON; SCSt outbound deferral).
- ADR-0020 (mTLS scaffold; cross-cutting).
- Strict rule B9.1 (messaging through SCSt-binder seam).
- Strict rule B9.2 (CloudEvents 1.0 envelope on the wire).
- Strict rule B10.1 (never publish a domain event from inside
  `@Transactional`).
- LD79 (SCSt outbound silently dropped messages at 2023.0.4;
  prefer direct KafkaTemplate publish).
- LD80 (`-Werror` + JaCoCo BUNDLE 0.80/0.80 + Checkstyle 0 +
  SpotBugs 0 are the merge gate).
- O8 (Avro + Schema Registry migration ladder).
- Plan row P4.4c (this PR).
