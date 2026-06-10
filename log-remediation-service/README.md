# log-remediation-service

**Status: P6.0 .. P6.3 + P6.0a + P6.1a + P19/P20/P21 + P9.3/P9.3a
implemented and verified** -- the service consumes
`cortex.anomalies.v1`, parses anomaly CloudEvents, routes malformed
envelopes to `cortex.anomalies.v1.dlq`, dedupes valid anomalies with
Redis SETNX, runs policy-gated auto-remediation through the
`RemediationPlaybook` SPI, publishes every valid decision to
`cortex.remediation.outcomes.v1`, persists valid anomaly rows into the
remediation-owned Postgres read model, exposes
`GET /api/v1/anomalies`, and falls back to exactly one active Slack,
PagerDuty, Jira, or noop `RemediationDispatcher` only when the
auto-remediation outcome is `skipped` or `failed`.

The P19/P20/P21 + P9.3/P9.3a lane was verified on 2026-06-09 with
`.\mvnw.cmd -pl log-remediation-service clean verify -B`, live JVM boot
smoke via `scripts\live-e2e\smoke-p6-1a.ps1 -SkipInfra -KeepInfra
-Provider all`, P9.3a read-model smoke via
`scripts\live-e2e\smoke-p9-3a.ps1 -KeepInfra -KeepService`, and Newman
against the live process using
`postman\log-remediation.postman_collection.json`.

CORTEX log remediation is now a **fix-first anomaly response service**.
`log-processor-service` owns AI classification; this module owns
deterministic policy, playbook execution, outcome audit, and guarded
human escalation. Auto-fix success is silent except for the audit topic.

## 1. Overview

`log-remediation-service` is the **operator-facing remediation leg of the
CORTEX pipeline**. It has actuator endpoints and one direct, tenant-scoped
read API (`GET /api/v1/anomalies`) on `:8096`; the write/action path still
happens on the Kafka consumer thread bound to `cortex.anomalies.v1`:

1. Receive a CloudEvents 1.0 structured-mode JSON envelope via
   `@KafkaListener` with manual offset commit (ADR-0028 stance
   mirrored, ADR-0032 D1).
2. Decode the envelope through `AnomalyEnvelopeParser`; enforce
   `specversion == "1.0"` and `type == "io.cortex.anomaly.v1"`;
   reshape the `data` block into a typed `AnomalyEvent` record.
3. On parse failure, publish the original bytes to
   `cortex.anomalies.v1.dlq` with failure metadata and acknowledge the
   Kafka offset.
4. On parse success, persist a fail-open query copy into the Postgres
   `anomalies` read model.
5. Claim `{tenantId}:{eventId}` in Redis so duplicate
   deliveries do not double-remediate.
6. Resolve `RemediationPolicy`, find a `RemediationPlaybook`, dry-run,
   and apply only when policy allows.
7. Publish every valid non-duplicate decision to
   `cortex.remediation.outcomes.v1`.
8. If the outcome is `fixed`, stop without notifying humans. If the
   outcome is `skipped` or `failed`, call the active dispatcher through
   a Resilience4j guard and tick
   `cortex.remediation.dispatched_total{channel,outcome,tenant_id}`.
9. Commit the Kafka offset.

## 2. Architecture (one screen)

```mermaid
flowchart TD
    anomalies[(Kafka<br/>cortex.anomalies.v1)]
    consumer[@KafkaListener<br/>manual offset commit]
    parser[AnomalyEnvelopeParser]
    dlq[(Kafka<br/>cortex.anomalies.v1.dlq)]
    readmodel[(Postgres<br/>anomalies read model)]
    api[GET /api/v1/anomalies]
    dedupe[RemediationDedupeService<br/>Redis SETNX tenantId:eventId]
    engine[RemediationEngine]
    policy[RemediationPolicyService]
    playbooks[RemediationPlaybookRegistry]
    outcomes[(Kafka<br/>cortex.remediation.outcomes.v1)]
    guard[RemediationDispatcherGuard<br/>Resilience4j retry + circuit breaker]
    dispatcher[RemediationDispatcher<br/>noop / Slack / PagerDuty / Jira]
    metrics[RemediationMetrics<br/>dispatched_total]
    ack[ack.acknowledge]

    anomalies --> consumer --> parser
    parser -->|parse fail| dlq --> ack
    parser -->|parse ok| readmodel
    readmodel --> api
    readmodel --> dedupe --> engine
    engine --> policy
    engine --> playbooks
    engine -->|fixed / skipped / failed| outcomes
    engine -->|fixed| ack
    engine -->|skipped or failed| guard --> dispatcher --> metrics --> ack
```

## 3. Tech stack

| Layer                      | Tech                                                                 |
|----------------------------|----------------------------------------------------------------------|
| Runtime                    | Java 17 LTS (no virtual threads per ADR-0001)                        |
| Framework                  | Spring Boot 3.3.6 / Spring Cloud 2023.0.4                            |
| Service registry           | Spring Cloud Netflix Eureka client (`lb://`)                         |
| Kafka client               | spring-kafka (direct `@KafkaListener`, manual offset commit; mirror of ADR-0028) |
| Envelope format            | CloudEvents 1.0 structured-mode JSON (cloudevents-json-jackson 4.0.1) |
| Auto-remediation engine    | `RemediationEngine` + `RemediationPolicyService` + `RemediationPlaybook` SPI |
| Dispatcher SPI             | `RemediationDispatcher` + `DispatchResult` (ADR-0032)                |
| Dedupe                     | Redis SETNX through `StringRedisTemplate`, fail-open on Redis outage |
| Read model                 | Postgres `anomalies` table, Flyway migration, Spring JDBC `JdbcClient` |
| Resilience                 | Resilience4j retry + circuit breaker per dispatcher channel         |
| Audit topics               | `cortex.remediation.outcomes.v1` for valid decisions; `cortex.anomalies.v1.dlq` for malformed anomaly envelopes |
| Metrics                    | Micrometer Prometheus with Part 17 allowlist (`channel`, `outcome`, `tenant_id`) |
| Health / probes            | Spring Boot Actuator (`health,info,metrics,prometheus,beans`)        |
| Persistence                | Postgres read model for anomaly queries; Kafka offset remains the action-path durability boundary; Redis is a bounded dedupe cache |
| Build                      | Maven 3.9.9 wrapper, JaCoCo BUNDLE 0.80 line + 0.80 branch           |
| Integration tests          | Testcontainers Kafka/Postgres + Awaitility                           |

## 4. Design decisions (ADR pointers)

- **ADR-0032** -- `RemediationDispatcher` SPI + per-channel
  adapter contract. Single method, single argument, single
  return; exactly one adapter bean active per profile selected
  by `cortex.remediation.dispatcher.provider` +
  `@ConditionalOnProperty`; default `NoopRemediationDispatcher`
  gated `matchIfMissing=true` so the scaffold boots green.
- **ADR-0051** -- P19/P20/P21 auto-remediation pipeline.
  Processor keeps AI classification; remediation owns deterministic
  dedupe, policy, playbook execution, outcome audit, and guarded human
  fallback. Malformed anomaly envelopes route to
  `cortex.anomalies.v1.dlq`; valid decisions publish to
  `cortex.remediation.outcomes.v1`; `fixed` is silent success;
  `skipped` and `failed` fall back through dispatchers. No generic
  DLQ-2 exists for valid remediation misses.
- **ADR-0052** -- P9.3/P9.3a anomaly read model and direct
  remediation API. Valid parsed anomalies are copied into the
  remediation-owned Postgres `anomalies` table through a fail-open
  writer, then exposed by `GET /api/v1/anomalies?tenantId&since&until&limit`.
  There is no FK to ingest-owned tenant tables and no Kafka replay query.
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
  mechanism; no outbox table is needed. This service is now a
  Kafka consumer -> Kafka producer / outbound HTTP relay: outcome
  audit events and malformed-envelope DLQ records are produced to
  Kafka, while dispatcher calls remain guarded HTTP side effects.
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

## 4a. Channel adapters -> Slack (P6.1, ADR-0033)

The first real `RemediationDispatcher` implementation.
Gated by `cortex.remediation.dispatcher.provider=slack`. Posts
a plain-text JSON body to a Slack Incoming Webhook URL using a
`RestClient` wired with HTTP/1.1 pinned `JdkClientHttpRequestFactory`
(LD42 symmetry with `LokiSink` / `QuickwitSink`).

### Configuration

```yaml
cortex:
  remediation:
    dispatcher:
      provider: slack
    slack:
      webhook-url: https://hooks.slack.com/services/T.../B.../X...
      request-timeout: 5s
      username: cortex-remediation     # optional
      channel-override: '#sre-incidents'  # optional
```

Env vars (preferred for the webhook URL):

```powershell
$env:CORTEX_REMEDIATION_DISPATCHER          = 'slack'
$env:CORTEX_REMEDIATION_SLACK_WEBHOOK_URL   = 'https://hooks.slack.com/services/T/B/X'
$env:CORTEX_REMEDIATION_SLACK_REQUEST_TIMEOUT = '5s'
$env:CORTEX_REMEDIATION_SLACK_USERNAME      = 'cortex-remediation'
$env:CORTEX_REMEDIATION_SLACK_CHANNEL       = '#sre-incidents'
```

A blank `webhook-url` is tolerated: the adapter returns
`DispatchResult.skipped("slack:unconfigured")` and the boot
stays green (ADR-0033 D5).

### Body shape (ADR-0033 D2)

```json
{
  "text": ":rotating_light: HIGH anomaly on checkout (tenant=tenant-abc): checkout 5xx burst",
  "username": "cortex-remediation",
  "channel": "#sre-incidents"
}
```

`username` and `channel` are dropped from the body when blank.

### Outcome -> `DispatchResult` table (ADR-0033 D3)

| HTTP outcome      | `outcome`           | `reason`            |
|-------------------|---------------------|---------------------|
| 2xx               | `dispatched`        | `""`                |
| 429               | `transient_failure` | `slack:429`         |
| 5xx               | `transient_failure` | `slack:5xx:<code>`  |
| 4xx (other)       | `permanent_failure` | `slack:4xx:<code>`  |
| Read timeout      | `transient_failure` | `slack:timeout`     |
| Connection error  | `transient_failure` | `slack:transport`   |
| Other RuntimeEx   | `transient_failure` | `slack:unknown`     |
| Blank webhook URL | `skipped`           | `slack:unconfigured`|
| Null event        | `skipped`           | `slack:null-event`  |

Per ADR-0032 D6 + ADR-0033 D4 the adapter NEVER throws on a
transient downstream failure -- it returns a typed verdict so
the consumer can ack the offset and the operator alerts on the
failed-outcome metric. No in-adapter retry: ADR-0033 D4 defers
the retry-budget axis to the outer `RemediationDispatcherGuard`
documented in ADR-0051.

### Counter bootstrap

`RemediationMetrics` bootstrap-registers three Slack outcome
series at construct time per LD106 + LD112:

- `cortex.remediation.dispatched_total{channel=slack, outcome=dispatched, tenant_id=unknown}`
- `cortex.remediation.dispatched_total{channel=slack, outcome=transient_failure, tenant_id=unknown}`
- `cortex.remediation.dispatched_total{channel=slack, outcome=permanent_failure, tenant_id=unknown}`

So the `/actuator/prometheus` scrape exposes the full Slack
outcome surface on the very first scrape, before any anomaly
hits the dispatcher.

## 4b. Channel adapters -> PagerDuty (P6.2, ADR-0034)

The second real `RemediationDispatcher` implementation. Gated
by `cortex.remediation.dispatcher.provider=pagerduty`. Posts a
PagerDuty Events API v2 envelope to
`https://events.pagerduty.com/v2/enqueue` using a `RestClient`
wired with HTTP/1.1 pinned `JdkClientHttpRequestFactory` AND
pinning BOTH `HttpClient.connectTimeout(...)` and
`factory.setReadTimeout(...)` per LD42 + LD121.

### Configuration

```yaml
cortex:
  remediation:
    dispatcher:
      provider: pagerduty
    pagerduty:
      routing-key: 'abcdef1234567890abcdef1234567890'
      request-timeout: 5s
      events-url: https://events.pagerduty.com/v2/enqueue
      dedup-key-template: '{tenantId}:{eventId}'
      source: cortex-remediation
      severity-default: error
```

Env vars (preferred for the routing key):

```powershell
$env:CORTEX_REMEDIATION_DISPATCHER                       = 'pagerduty'
$env:CORTEX_REMEDIATION_PAGERDUTY_ROUTING_KEY            = 'abcdef1234567890abcdef1234567890'
$env:CORTEX_REMEDIATION_PAGERDUTY_REQUEST_TIMEOUT        = '5s'
$env:CORTEX_REMEDIATION_PAGERDUTY_EVENTS_URL             = 'https://events.pagerduty.com/v2/enqueue'
$env:CORTEX_REMEDIATION_PAGERDUTY_DEDUP_KEY_TEMPLATE     = '{tenantId}:{eventId}'
$env:CORTEX_REMEDIATION_PAGERDUTY_SOURCE                 = 'cortex-remediation'
$env:CORTEX_REMEDIATION_PAGERDUTY_SEVERITY_DEFAULT       = 'error'
```

A blank `routing-key` is tolerated: the adapter returns
`DispatchResult.skipped("pagerduty:unconfigured")` and the boot
stays green (ADR-0034 D7). The dedup-key template uses
single-brace `{tenantId}:{eventId}` placeholders (NOT Spring
`${...}` syntax) so the literal default string does not
collide with Spring's property-placeholder parser at boot; the
substitution is performed inside the adapter via two
`String.replace` calls.

### Body shape (ADR-0034 D1)

```json
{
  "routing_key": "abcdef1234567890abcdef1234567890",
  "event_action": "trigger",
  "dedup_key": "tenant-abc:evt-1",
  "payload": {
    "summary": ":rotating_light: HIGH anomaly on checkout (tenant=tenant-abc): checkout 5xx burst",
    "severity": "error",
    "source": "cortex-remediation",
    "custom_details": {
      "eventId": "evt-1",
      "tenantId": "tenant-abc",
      "reason": "checkout 5xx burst",
      "level": "ERROR",
      "service": "checkout",
      "message": "503 from /pay endpoint",
      "ts": "2026-06-04T15:00:00Z",
      "rawSeverity": "HIGH"
    }
  }
}
```

The `event_action` is hard-coded `"trigger"` per ADR-0034 D2 --
the adapter NEVER auto-acknowledges or auto-resolves; the
on-call human (or a separate operator workflow) owns
resolution.

### Severity mapping (ADR-0034 D6)

1. Lowercase the upstream `AnomalyEvent.severity` string.
2. If the lowercased value is in
   `{critical, error, warning, info}`, pass it through to
   `payload.severity` verbatim.
3. Otherwise fall back to
   `cortex.remediation.pagerduty.severity-default` (default
   `"error"`).

The raw upstream severity is always copied into
`payload.custom_details.rawSeverity` so the on-call human sees
the classifier's original verdict string in the PagerDuty
incident UI even when the Events API enum gets the default.

### Outcome -> `DispatchResult` table (ADR-0034 D3)

| HTTP outcome      | `outcome`           | `reason`                |
|-------------------|---------------------|-------------------------|
| 202 (success)     | `dispatched`        | `""`                    |
| 2xx (other)       | `dispatched`        | `""`                    |
| 429               | `transient_failure` | `pagerduty:429`         |
| 5xx               | `transient_failure` | `pagerduty:5xx:<code>`  |
| 4xx (other)       | `permanent_failure` | `pagerduty:4xx:<code>`  |
| Read timeout      | `transient_failure` | `pagerduty:timeout`     |
| Connection error  | `transient_failure` | `pagerduty:transport`   |
| Other RuntimeEx   | `transient_failure` | `pagerduty:unknown`     |
| Blank routing key | `skipped`           | `pagerduty:unconfigured`|
| Null event        | `skipped`           | `pagerduty:null-event`  |

Per ADR-0032 D6 + ADR-0034 D5 the adapter NEVER throws on a
transient downstream failure -- it returns a typed verdict so
the consumer can ack the offset and the operator alerts on the
failed-outcome metric. No in-adapter retry: ADR-0034 D5 defers
the retry-budget axis to the outer `RemediationDispatcherGuard`
documented in ADR-0051.

### Counter bootstrap

`RemediationMetrics` bootstrap-registers three PagerDuty
outcome series at construct time per LD106 + LD112:

- `cortex.remediation.dispatched_total{channel=pagerduty, outcome=dispatched, tenant_id=unknown}`
- `cortex.remediation.dispatched_total{channel=pagerduty, outcome=transient_failure, tenant_id=unknown}`
- `cortex.remediation.dispatched_total{channel=pagerduty, outcome=permanent_failure, tenant_id=unknown}`

So the `/actuator/prometheus` scrape exposes the full PagerDuty
outcome surface on the very first scrape, before any anomaly
hits the dispatcher.

## 4c. Channel adapters -> Jira (P6.3, ADR-0035)

The third real `RemediationDispatcher` implementation. Gated
by `cortex.remediation.dispatcher.provider=jira`. Posts a Jira
Cloud REST API v3 create-issue envelope to
`{baseUrl}/rest/api/3/issue` using a `RestClient` wired with
HTTP/1.1 pinned `JdkClientHttpRequestFactory` AND pinning
BOTH `HttpClient.connectTimeout(...)` and
`factory.setReadTimeout(...)` per LD42 + LD121.

### Configuration

```yaml
cortex:
  remediation:
    dispatcher:
      provider: jira
    jira:
      base-url: 'https://cortex.atlassian.net'
      email: 'ops@cortex.io'
      api-token: '<your-jira-api-token>'
      request-timeout: 5s
      project-key: OPS
      issue-type: Bug
      severity-label-prefix: anomaly-severity
```

Env vars (preferred for the four credential / target fields):

```powershell
$env:CORTEX_REMEDIATION_DISPATCHER                          = 'jira'
$env:CORTEX_REMEDIATION_JIRA_BASE_URL                       = 'https://cortex.atlassian.net'
$env:CORTEX_REMEDIATION_JIRA_EMAIL                          = 'ops@cortex.io'
$env:CORTEX_REMEDIATION_JIRA_API_TOKEN                      = '<your-jira-api-token>'
$env:CORTEX_REMEDIATION_JIRA_REQUEST_TIMEOUT                = '5s'
$env:CORTEX_REMEDIATION_JIRA_PROJECT_KEY                    = 'OPS'
$env:CORTEX_REMEDIATION_JIRA_ISSUE_TYPE                     = 'Bug'
$env:CORTEX_REMEDIATION_JIRA_SEVERITY_LABEL_PREFIX          = 'anomaly-severity'
```

Any of blank `base-url` / `email` / `api-token` / `project-key`
is tolerated: the adapter returns
`DispatchResult.skipped("jira:unconfigured")` and the boot
stays green (ADR-0035 D7). Authentication uses Jira Cloud's
Basic-auth-with-API-token scheme per ADR-0035 D2 -- the adapter
builds an `Authorization: Basic <Base64(email:apiToken)>`
header per request (the API token is rotatable in the
id.atlassian.com profile UI).

### Body shape (ADR-0035 D2)

```json
{
  "fields": {
    "project":  { "key": "OPS" },
    "summary":  "[HIGH] checkout: checkout 5xx burst",
    "description": {
      "type": "doc",
      "version": 1,
      "content": [
        { "type": "paragraph", "content": [
          { "type": "text", "text": "eventId: evt-1" } ] },
        { "type": "paragraph", "content": [
          { "type": "text", "text": "tenantId: tenant-abc" } ] },
        { "type": "paragraph", "content": [
          { "type": "text", "text": "severity: HIGH" } ] }
      ]
    },
    "issuetype": { "name": "Bug" },
    "labels": [
      "cortex-remediation",
      "tenant:tenant-abc",
      "anomaly-severity-high"
    ]
  }
}
```

The adapter ships create-issue only per ADR-0035 D2 -- it
NEVER auto-transitions or auto-resolves issues; the on-call
human (or a separate Jira Automation rule that fires on the
`cortex-remediation` label) owns issue lifecycle.

### Severity mapping (ADR-0035 D5)

1. Lowercase the upstream `AnomalyEvent.severity` string.
2. Prepend the configurable `severity-label-prefix` (default
   `anomaly-severity`) with a `-` separator.
3. Emit the resulting string as a Jira label (e.g.
   `anomaly-severity-high`).

Label-based severity sidesteps every per-project priority
scheme issue while still letting operators build Jira filter
queries like `labels = "anomaly-severity-high"` for the
on-call dashboard.

### Outcome -> `DispatchResult` table (ADR-0035 D3)

| HTTP outcome                                | `outcome`           | `reason`              |
|---------------------------------------------|---------------------|-----------------------|
| 201 (success)                               | `dispatched`        | `""`                  |
| 2xx (other)                                 | `dispatched`        | `""`                  |
| 429                                         | `transient_failure` | `jira:429`            |
| 5xx                                         | `transient_failure` | `jira:5xx:<code>`     |
| 4xx (other -- 400 / 401 / 403 / 404 / ...) | `permanent_failure` | `jira:4xx:<code>`     |
| Read timeout                                | `transient_failure` | `jira:timeout`        |
| Connection error                            | `transient_failure` | `jira:transport`      |
| Other RuntimeEx                             | `transient_failure` | `jira:unknown`        |
| Blank `baseUrl`/`email`/`apiToken`/`projectKey` | `skipped`       | `jira:unconfigured`   |
| Null event                                  | `skipped`           | `jira:null-event`     |

Per ADR-0032 D6 + ADR-0035 D6 the adapter NEVER throws on a
transient downstream failure -- it returns a typed verdict so
the consumer can ack the offset and the operator alerts on the
failed-outcome metric. No in-adapter retry: ADR-0035 D6 defers
the retry-budget axis to the outer `RemediationDispatcherGuard`
documented in ADR-0051.

### Counter bootstrap

`RemediationMetrics` bootstrap-registers three Jira outcome
series at construct time per LD106 + LD112:

- `cortex.remediation.dispatched_total{channel=jira, outcome=dispatched, tenant_id=unknown}`
- `cortex.remediation.dispatched_total{channel=jira, outcome=transient_failure, tenant_id=unknown}`
- `cortex.remediation.dispatched_total{channel=jira, outcome=permanent_failure, tenant_id=unknown}`

So the `/actuator/prometheus` scrape exposes the full Jira
outcome surface on the very first scrape, before any anomaly
hits the dispatcher.

## 4d. Cross-phase closer (P6.1a, ADR-0037)

The P6.1a closer is the **only P6 deliverable that ships
artifacts spanning all three real channels at once**. Per the
LD104 closer-pattern, P6.0..P6.3 + P6.0a shipped Leg A only
(`mvn verify` GREEN) and deferred Legs B-E to this closer so
the boot smoke + Postman collection + cross-phase IT could
exercise Slack + PagerDuty + Jira together rather than three
times in three separate PRs.

**5-leg gate** (all must be GREEN at PR-merge time):

- **Leg A** -- `./mvnw verify -pl log-remediation-service -am`:
  Surefire 76/76, Failsafe 22/22 (16 prior + 6 new closer ITs),
  0 Checkstyle, 0 SpotBugs, JaCoCo BUNDLE 0.80/0.80 met,
  `BUILD SUCCESS`.
- **Leg B** -- `scripts/smoke-p6-1a.ps1`: starts the actual
  service JAR three times (once per channel) against a per-
  channel Kafka topic (`cortex.anomalies.v1.<runId>.<channel>`
  via `CORTEX_REMEDIATION_TOPIC`, per LD125) + a per-channel
  WireMock container, publishes an anomaly envelope through
  the consumer, asserts the Prometheus counter delta + the
  WireMock journal entry. Last GREEN transcript:
  `scripts/logs/p6-1a/smoke-all-20260606-220655.log`.
- **Leg C** -- `npx newman run postman/log-remediation.postman_
  collection.json -e postman/log-remediation.postman_
  environment_local.json --reporters cli --bail`: at P6.1a close time,
  4 folders / 10 requests / 25 assertions mirrored the smoke 1:1 (Admin
  actuator + Metrics-Baseline + Channel-Mock-Smoke + Metrics-
  After). `pm.execution.skipRequest()` gates the WireMock
  folder on `wiremock_base_url` so staging + prod env runs
  exercise admin-only surfaces. The current collection adds the P9.3a
  Anomalies Read API folder.
- **Leg D** -- the new `closer/*CrossPhaseIT.java` suite under
  `src/test/java/io/cortex/remediation/closer/`. Singleton
  Testcontainers Kafka + in-process WireMock server owned by
  `AnomalyCrossPhaseBaseIT`; three sealed-shape `@SpringBootTest`
  subclasses (`SlackCrossPhaseIT`, `PagerDutyCrossPhaseIT`,
  `JiraCrossPhaseIT`), each on its own per-channel topic +
  unique consumer group-id, each with two tests
  (`happyPathTicksDispatched` + `transientPathTicksTransientFailure`)
  that assert (a) the `cortex_remediation_dispatched_total
  {channel,outcome,tenant_id}` counter ticks for a unique
  `tenant_id` per test, (b) the WireMock POST was recorded
  at the expected path, and (c) the malformed-envelope DLQ
  stayed empty on valid cross-phase events.
- **Leg E** -- this section + ADR-0037 + INDEX bump 36 -> 37
  + CHANGELOG `[Unreleased] > Added` entry + atomic 4-file
  tracking flip (plan.md + checkpoint.md + memory.md +
  todo.md).

**Cross-phase IT shape**:

- Base class owns `protected static final KafkaContainer KAFKA
  = new KafkaContainer("apache/kafka:3.8.0");` + `protected
  static final WireMockServer WIRE_MOCK = new WireMockServer
  (WireMockConfiguration.options().dynamicPort());` started
  in a static block (no `@Testcontainers` annotation; Ryuk
  reaper handles lifecycle). `@DynamicPropertySource
  baseProperties` registers `spring.kafka.bootstrap-servers`
  + disables Eureka. `@BeforeEach resetWireMock()` clears
  stubs + journal between tests.
- Each subclass adds its own `@DynamicPropertySource` for the
  channel-specific properties (Slack webhook URL, PagerDuty
  routing key + events URL, Jira base URL + email + token +
  project key). Credentials are neutral per LD123 (no
  realistic `xoxb-...` / `ATATT3xFfGF0...` / `R0...` prefixes):
  - Slack webhook path: `/services/IT/CROSS/PHASE`.
  - PagerDuty routing key: `00000000000000000000000000000000`.
  - Jira email: `test@example.com`; token:
    `placeholder-token-not-real`; project key: `IT`.

**Why per-channel kafka topics (LD125)**: a single shared
`cortex.anomalies.v1` topic + `auto.offset.reset=earliest`
caused the next channel's consumer to replay the previous
channel's envelopes through different WireMock stubs, mis-
classifying the outcome. Each subclass + each smoke run now
publishes/consumes on `cortex.anomalies.v1.cross-phase.
<channel>` (IT) or `cortex.anomalies.v1.<runId>.<channel>`
(smoke) via the `CORTEX_REMEDIATION_TOPIC` env var (the
application.yml default is preserved:
`cortex.remediation.topic: ${CORTEX_REMEDIATION_TOPIC:cortex.
anomalies.v1}`).

**Failsafe runtime budget**: Tests run: 22 (16 prior + 6 new)
in ~3:44 wall clock vs 16 in ~3:00 prior -- ~14 s/test
marginal cost, well under the 25-40 s/test if each subclass
booted its own Kafka container. Singleton Kafka + in-process
WireMock are the only design that fits inside the existing
budget.

**Postman collection contract at P6.1a**: `postman/log-remediation.
postman_collection.json` mirrored the channel smoke 1:1. The current
collection extends that contract with the P9.3a `Anomalies Read API`
folder while preserving the Admin, Metrics-Baseline, Channel-Mock-Smoke,
and Metrics-After folders. The top-level test still asserts
`responseTime < 15000ms`. Three env files remain: `local`
(`wiremock_base_url=http://localhost:8094`), `staging` (blank), `prod`
(blank).

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
- Persistence is isolated to the fail-open anomaly read model. The
  remediation action path still treats the Kafka offset as its durability
  boundary; a query-store outage logs an error but does not suppress
  remediation or acknowledgments.
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
  actual rejected envelope is NOT logged. The original bytes are
  published to `cortex.anomalies.v1.dlq` with failure metadata so
  operators can inspect poison input without leaking it into app logs.

## 7. Run locally

```powershell
# 0. Prereqs: infra-up (Kafka) so the upstream log-processor-service
#    can publish to cortex.anomalies.v1, plus Postgres for the P9.3
#    anomalies read model.
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

# 5. P9.3a live read-model smoke + Newman precondition.
powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts\live-e2e\smoke-p9-3a.ps1 -KeepInfra -KeepService
```

The P6.0 scaffold runs the default `NoopRemediationDispatcher`
(no Slack webhook URL, no PagerDuty integration key, no Jira API
token required). To force a real adapter once P6.1..P6.3 land:

```powershell
$env:CORTEX_REMEDIATION_DISPATCHER = 'slack'        # P6.1
$env:CORTEX_REMEDIATION_DISPATCHER = 'pagerduty'    # P6.2
$env:CORTEX_REMEDIATION_DISPATCHER = 'jira'         # P6.3
```

P9.3a database settings are:

```powershell
$env:CORTEX_REMEDIATION_DB_URL      = 'jdbc:postgresql://localhost:5432/cortex_ingest'
$env:CORTEX_REMEDIATION_DB_USERNAME = 'cortex_ingest'
$env:CORTEX_REMEDIATION_DB_PASSWORD = 'cortex_ingest'
```

Local dev reuses the smoke database but writes Flyway history to
`remediation_flyway_schema_history`. Production should override these
values to a remediation-owned database or schema.

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

The service exposes actuator endpoints plus one direct read endpoint for
P9.3a:

`GET /api/v1/anomalies?tenantId=<tenant>&since=<iso>&until=<iso>&limit=<n>`

| Query parameter | Required | Meaning                                                  |
|-----------------|----------|----------------------------------------------------------|
| `tenantId`      | yes      | Tenant scope for the read model query                    |
| `since`         | no       | Inclusive lower `ts` bound, ISO-8601 instant             |
| `until`         | no       | Inclusive upper `ts` bound, ISO-8601 instant             |
| `limit`         | no       | Row limit; defaults to 100 and clamps at 500             |

The response is a JSON array ordered newest first by `ts DESC, id DESC`:

```json
[
  {
    "tenantId": "tenant-abc",
    "eventId": "evt-1",
    "severity": "HIGH",
    "reason": "checkout 5xx burst",
    "ts": "2026-06-09T10:00:00Z",
    "level": "ERROR",
    "service": "checkout",
    "message": "503 from /pay endpoint",
    "confidence": 0.97,
    "anomalyType": "availability",
    "remediationKey": "checkout-restart",
    "receivedAt": "2026-06-09T10:00:03Z"
  }
]
```

Validation errors return HTTP 400 with:

```json
{
  "errorCode": "VALIDATION_FAILED",
  "message": "tenantId is required"
}
```

The Postman v2.1 contract in
`postman/log-remediation.postman_collection.json` documents actuator,
dispatcher scrape, WireMock payload, and the P9.3a anomaly read API.
For a live end-to-end read-model proof, run
`scripts\live-e2e\smoke-p9-3a.ps1 -KeepInfra -KeepService` first, then
run Newman with the `tenant_id` and `anomaly_event_id` printed by the
smoke script.

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
| `confidence` | `double`     | Optional classifier confidence; defaults to `0.0` for older envelopes |
| `anomalyType` | `String`    | Optional classifier anomaly type; defaults to `UNKNOWN`       |
| `remediationKey` | `String` | Optional playbook lookup key; defaults to `none`              |

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
| `data`             | `AnomalyEvent` JSON (11-field record above)                        |

Plus two Kafka headers (ADR-0027 mirror):

| Header           | Value                                  |
|------------------|----------------------------------------|
| `content-type`   | `application/cloudevents+json`         |
| `x-source-topic` | `cortex.logs.events.v1`                |

The remediation decision audit topic is
`cortex.remediation.outcomes.v1`. Each record is a CloudEvents 1.0
structured-mode JSON envelope with:

| Envelope attribute | Value                                      |
|--------------------|--------------------------------------------|
| `type`             | `io.cortex.remediation.outcome.v1`         |
| `source`           | `/cortex/log-remediation-service`          |
| `subject`          | `tenant_id`                                |
| `id`               | `<eventId>:remediation:<outcome>`          |
| `datacontenttype`  | `application/json`                         |
| `data`             | `RemediationOutcome` JSON                  |

`RemediationOutcome` carries `eventId`, `tenantId`, `severity`,
`anomalyType`, `remediationKey`, `outcome`, `reason`, `playbookKey`,
`dryRun`, and `ts`. `outcome` is one of `fixed`, `skipped`, or `failed`.

## 10. Future improvements

- **P9.3b gateway parity** -- add gateway REST + GraphQL
  `getAnomalies` parity over this service via `lb://log-remediation-service`
  and a shared `get-anomalies` rate-limit bucket.
- **Tenant-backed remediation policies** -- replace the current
  property-backed default `RemediationPolicyService` implementation with
  a tenant-scoped store while keeping the same service seam.
- **Runnable playbook catalog** -- add real deterministic playbook beans
  behind the `RemediationPlaybook` SPI. Keep LLM calls out of this stage;
  processor classification remains the AI boundary.
- **P6.x Slack richer body** -- promote the plain-text Slack
  message to Block Kit with action buttons (acknowledge /
  escalate) once operator feedback warrants. ADR-0033 D2
  documents why the first ship stayed plain-text.
- **P6.x Slack per-tenant routing** -- replace the single
  `cortex.remediation.slack.webhook-url` with a config-driven
  `Map<String, String>` keyed by tenant id, falling back to
  the default when the tenant key is absent.
- **P6.5** -- (conditional on operator demand) explicit
  fan-out dispatcher (a `FanOutRemediationDispatcher` that is
  itself a `RemediationDispatcher` + delegates to N inner
  adapters). The ADR-0032 SPI shape was chosen specifically
  to make this a non-breaking superset.
- **P9 / P10** -- multi-partition consumer concurrency bump
  once partition counts on `cortex.anomalies.v1` exceed one.
  The `KafkaListenerContainerFactory.concurrency` setting
  defaults to 1 in P6.0; raise alongside the partition bump.
