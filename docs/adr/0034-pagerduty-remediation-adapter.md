# 0034. PagerDuty `RemediationDispatcher` adapter -- Events API v2 + trigger-only

- Status: accepted
- Date: 2026-06-05
- Deciders: @varadharajaan
- Tags: remediation, dispatcher, pagerduty, events-api, http

## Context and problem statement

P6.0 / ADR-0032 locked the `RemediationDispatcher` SPI and shipped
the noop default. P6.1 / ADR-0033 landed the Slack adapter. P6.2
lands the second channel: PagerDuty. Operators want confirmed
anomaly verdicts off `cortex.anomalies.v1` to page the on-call
rotation in PagerDuty so the platform escalates the AI
classifier's verdicts through the same incident-response surface
the SRE team already uses out-of-band. The PagerDuty API surface
is large (Events API v2, REST API v2 Incidents endpoint, OAuth
chat-style flows, etc.); this ADR records the design choices the
P6.2 adapter takes so P6.3 (Jira) can mirror or diverge with
explicit intent.

Open questions this ADR settles:

1. Which PagerDuty API to call (Events API v2 enqueue vs REST
   API v2 Incidents endpoint vs OAuth chat-style apps).
2. Which event_action to send (trigger-only vs full
   trigger / acknowledge / resolve workflow inside the adapter).
3. How to classify HTTP outcomes (202 / 4xx / 429 / 5xx / IO)
   onto the `DispatchResult.OUTCOME_*` constants ADR-0032 D3
   froze.
4. How to pick a dedup-key so PagerDuty de-duplicates retries /
   replays naturally without the adapter holding state.
5. Whether to add retry/backoff to the adapter or defer to the
   P6.4 retry-budget design (same question ADR-0033 answered
   for Slack).
6. How to map the upstream `AnomalyEvent.severity` (free-form
   ALL-CAPS string) onto the PagerDuty Events API v2 severity
   enum (`critical|error|warning|info`).

## Decision drivers

- D1 -- the adapter MUST work with a single integration-level
  secret (the Events API v2 routing key) and MUST NOT require an
  account-level OAuth app per deployment; the routing key is the
  smallest credential surface that still respects per-service
  routing inside the operator's PagerDuty account (one routing
  key per PagerDuty Service, configured out-of-band by the
  PagerDuty admin).
- D2 -- the adapter MUST send `event_action=trigger` only and
  MUST NOT auto-resolve or auto-acknowledge incidents from the
  pipeline. Resolution belongs to the human responder (or a
  separate operator workflow); coupling auto-resolve into the
  dispatcher would race with the on-call workflow and silently
  close incidents the human is still investigating.
- D3 -- the HTTP outcome -> `DispatchResult` mapping MUST be a
  pure function the operator can read from a single table; no
  hidden retries, no swallowed exceptions; every category ticks
  exactly one Micrometer counter row with the right
  `{channel=pagerduty, outcome=...}` tags. PagerDuty's success
  code is **202 Accepted** (not 200), so the 2xx classifier must
  not narrow to `==200`.
- D4 -- the dedup-key MUST be deterministic from the upstream
  envelope (`{tenantId}:{eventId}`) so PagerDuty natively
  collapses Kafka replays + at-least-once redeliveries into one
  incident. Holding the dedup-key in the adapter (vs random per
  call) gives idempotency without an outbox + without forcing a
  P6.4 retry-budget rewrite.
- D5 -- the adapter MUST honour ADR-0032 D6 (never throw on
  transient failures) and D7 (stay agnostic to the future P6.4
  DLQ + retry-budget axis); returning a typed `transient_failure`
  verdict satisfies both without breaking the SPI -- same
  contract Slack accepts in ADR-0033.
- D6 -- severity mapping MUST be lossless when the upstream
  value is already in the Events API v2 enum, and MUST fall
  back to a configured `severity-default` (default `"error"`)
  when the upstream value is anything else; raising on unknown
  severity would block alerts from reaching the on-call rotation
  for verdicts whose severity string the classifier hasn't yet
  been taught about.
- D7 -- the adapter MUST tolerate a blank routing key at boot so
  profiles that select the PagerDuty provider without supplying
  the secret still boot green (the operator sees the
  `outcome=skipped, reason=pagerduty:unconfigured` counter climb
  instead of CrashLoopBackOff) -- mirrors ADR-0033 D5.
- D8 -- outbound HTTP MUST use the same `RestClient` +
  HTTP/1.1-pinned `JdkClientHttpRequestFactory` pattern P5.3
  (`LokiSink` / `QuickwitSink`, LD42 + ADR-0030) and P6.1
  (`SlackRemediationDispatcher`, ADR-0033 D6) standardised;
  both the connect-timeout AND the read-timeout MUST be pinned
  per LD121 (LD121 was logged after P6.1 because Slack's
  `setReadTimeout` was previously missing).

## Considered options

1. **PagerDuty Events API v2 enqueue (`POST
   https://events.pagerduty.com/v2/enqueue`) + trigger-only
   `event_action` + deterministic `dedup_key` templated from
   `{tenantId}:{eventId}` + `RestClient` HTTP/1.1 pin (LD42 +
   LD121) + typed `DispatchResult` outcome classification table;
   blank routing key tolerated.** -- **ACCEPTED** (chosen
   option).

2. **PagerDuty REST API v2 Incidents endpoint (`POST
   https://api.pagerduty.com/incidents`) with `From:` header +
   `Authorization: Token token=...`.** -- **REJECTED**: this is
   the account-management surface (used by humans to create
   incidents from the dashboard). It requires an
   account-level API token with `incidents.write` scope, a
   `From:` header bound to a PagerDuty user, and a
   `service.id` lookup per call. The Events API v2 enqueue is
   the right surface for fire-and-forget alert ingestion --
   it's what PagerDuty's own integrations (Datadog, NewRelic,
   Prometheus AlertManager) use because routing-key is an
   integration-level secret, not an account-level token.

3. **Full trigger / acknowledge / resolve workflow inside the
   adapter (auto-resolve when the upstream severity drops or
   when a follow-up "all clear" verdict arrives).** --
   **REJECTED** per D2. Auto-resolve races with the human
   responder workflow and silently closes incidents the
   on-call is still investigating. The dispatcher's job is to
   page; resolution belongs to the human responder (or a
   separate operator-driven workflow) so the on-call has full
   control over incident lifecycle.

4. **PagerDuty Apps OAuth flow (workspace-scoped app token).**
   -- **REJECTED**: same shape as Slack `chat.postMessage`
   rejected in ADR-0033 alternative #2. Higher credential
   surface (one app install per PagerDuty account + per-tenant
   token rotation) for zero functional benefit over a
   per-service routing key the SRE team already manages
   out-of-band.

5. **Spring Retry `@Retryable(maxAttempts=3,
   backoff=ExponentialBackoff)` on the adapter.** --
   **REJECTED**: contradicts ADR-0032 D6 + D7 (same reason
   ADR-0033 alternative #5 rejected it for Slack). The SPI
   contract is "return a verdict, don't throw"; layering retry
   inside the adapter leaks the retry-budget axis ADR-0032 D7
   explicitly reserved for P6.4. A failed `transient_failure`
   verdict ticks the metric (operator alerts on the rate) and
   P6.4 will decide the budget centrally across all channels
   (Slack + PagerDuty + Jira).

6. **Resilience4j `@CircuitBreaker` + `@TimeLimiter` on the
   adapter.** -- **REJECTED** for the same reason as #5: P6.4
   will land the budget + breaker pattern uniformly.

7. **Fail closed: refuse to boot if `routing-key` is blank.**
   -- **REJECTED** per D7 + ADR-0033 D5 precedent: hostile to
   operator workflows where the PagerDuty integration is
   provisioned after the service starts (e.g. dev smoke /
   preview environments / new tenant onboarding). Fail open +
   surface the skipped-outcome counter gives the operator
   visibility without blocking the rest of the pipeline.

8. **Raise on unknown severity values (e.g. throw
   `IllegalArgumentException` when severity is not
   `critical|error|warning|info`).** -- **REJECTED** per D6:
   would block alerts from reaching the on-call rotation for
   verdicts whose severity string the classifier emits today
   but the adapter hasn't been taught about. The configurable
   `severity-default` (default `"error"`) preserves alert
   delivery + gives the operator a single config knob to
   change the fallback if `"error"` is too noisy.

## Decision outcome

Chosen option: **#1 -- PagerDuty Events API v2 enqueue +
trigger-only `event_action` + deterministic `dedup_key`
templated from `{tenantId}:{eventId}` + `RestClient` HTTP/1.1
pin + typed outcome classification + blank routing key
tolerated**.

The SPI signature is exactly the one ADR-0032 D2 + D4 + D6
froze; no changes to the dispatcher contract. The adapter ships
as `io.cortex.remediation.dispatch.PagerDutyRemediationDispatcher`
gated by `cortex.remediation.dispatcher.provider=pagerduty`. The
`RestClient` it consumes is built by `PagerDutyHttpConfig` (a
`@Configuration` class itself gated by the same property) using
the LD42 HTTP/1.1 pin + LD121 dual-timeout pin (both
`HttpClient.connectTimeout(...)` AND
`factory.setReadTimeout(...)`) +
`PagerDutyProperties.requestTimeout` (default 5 s). Body shape:

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

The dedup-key template defaults to `{tenantId}:{eventId}`
(single-brace placeholders, not Spring `${...}` syntax, to
avoid colliding with Spring's property placeholder parser at
boot; the substitution is performed inside
`PagerDutyRemediationDispatcher` via two `String.replace`
calls). Operators can override the template via
`cortex.remediation.pagerduty.dedup-key-template` if they want
per-service grouping (e.g. `{tenantId}:{service}:{eventId}` to
collapse all events for one service into one incident; the
adapter only substitutes `{tenantId}` and `{eventId}` for the
first ship, so other placeholders pass through literally and
the operator gets an explicit error -- a missed substitution --
in the resulting dedup-key string they can see in PagerDuty's
incident detail view).

### HTTP outcome -> `DispatchResult` table (D3)

| HTTP outcome      | `DispatchResult.outcome` | `reason`                |
|-------------------|--------------------------|-------------------------|
| 202               | `dispatched`             | `""`                    |
| 2xx (other)       | `dispatched`             | `""`                    |
| 429               | `transient_failure`      | `pagerduty:429`         |
| 5xx               | `transient_failure`      | `pagerduty:5xx:<code>`  |
| 4xx (other)       | `permanent_failure`      | `pagerduty:4xx:<code>`  |
| Read timeout      | `transient_failure`      | `pagerduty:timeout`     |
| Connection error  | `transient_failure`      | `pagerduty:transport`   |
| Other RuntimeEx   | `transient_failure`      | `pagerduty:unknown`     |
| Blank routing key | `skipped`                | `pagerduty:unconfigured`|
| Null event        | `skipped`                | `pagerduty:null-event`  |

Per ADR-0032 D6 the adapter NEVER throws on a transient
downstream failure. The consumer acks the offset regardless and
the operator alerts on the failed-outcome metric. The
`HTTP_TOO_MANY_REQUESTS=429` constant lives in
`PagerDutyRemediationDispatcher` because the JDK
`HttpStatus.TOO_MANY_REQUESTS` constant lives in Spring Web (not
java.net.http), and the Checkstyle MagicNumber rule wants a
named constant.

### Severity mapping (D6)

The upstream `AnomalyEvent.severity` is a free-form ALL-CAPS
string the classifier emits (e.g. `HIGH`, `MEDIUM`, `LOW`,
`CRITICAL`). The PagerDuty Events API v2 severity enum is
`critical|error|warning|info`. Mapping rules:

1. Lowercase the upstream value.
2. If the lowercased value is in
   `{critical, error, warning, info}`, pass it through verbatim.
3. Otherwise fall back to
   `cortex.remediation.pagerduty.severity-default` (default
   `"error"`).

The raw upstream severity is ALSO copied into
`payload.custom_details.rawSeverity` so the on-call human sees
the classifier's original verdict string in the PagerDuty
incident UI even when the Events API enum gets the default.

### Positive consequences

- One PagerDuty credential per service (routing key) rather
  than one OAuth app per account; the SRE team already manages
  routing keys out-of-band per PagerDuty Service.
- The body lands as a real incident in the on-call rotation
  with no per-account UI configuration; no Workflow Builder
  equivalent to worry about.
- The HTTP outcome table is a deterministic pure function of
  the HTTP response; an operator reading the counter knows
  exactly which downstream condition triggered the increment.
- The `dedup_key` template means at-least-once Kafka
  redeliveries collapse to a single PagerDuty incident
  naturally (PagerDuty's own dedup semantics handle it server
  side); no outbox + no per-event idempotency table required
  on the adapter.
- The severity fallback keeps alert delivery flowing even
  when the classifier emits a severity the adapter doesn't
  recognise; the raw value is preserved in `custom_details`.
- The adapter introduces zero new dependencies beyond what
  the parent POM already manages (`RestClient` is core
  Spring; WireMock is already in the parent test scope via
  the `wiremock.version` property).
- The blank-routing-key tolerance keeps preview / smoke
  environments green by default; operator opt-in is the env
  var `CORTEX_REMEDIATION_PAGERDUTY_ROUTING_KEY`.
- Retry / circuit-breaker logic stays out of the adapter so
  P6.4's retry-budget design is unconstrained.

### Negative consequences

- Trigger-only means the dispatcher cannot auto-resolve
  incidents when an upstream severity drops; the on-call human
  (or a separate operator workflow) owns resolution. This is
  intentional per D2 but is a tradeoff vs a future
  "ack-on-followup" workflow.
- The 5 s default request timeout means a PagerDuty-side
  incident can cause up to ~5 s of consumer-thread blocking
  per record. Acceptable because: (a) Events API v2 p99 in
  healthy state is well under 1 s; (b) the consumer thread
  pool is sized for parse + dispatch latency which is
  dominated by upstream Kafka poll cadence anyway; (c) P6.4
  will land the budget breaker that caps cumulative
  slow-PagerDuty damage.
- The severity fallback to `"error"` collapses upstream
  `HIGH` / `CRITICAL` verdicts onto the same PagerDuty enum
  value by default; the operator can change the fallback via
  `severity-default` but cannot get true `critical`-tier
  paging unless the classifier learns to emit the literal
  string `"critical"`. Acceptable for the first ship; future
  ADR (P6.x) could land a severity-mapping table if the
  classifier vocabulary stabilises.

## Verification

- Mockito-driven unit tests in
  `PagerDutyRemediationDispatcherTest` cover every row of the
  outcome table (202 / 429 / 5xx / 4xx-other / 401 / timeout /
  transport / unknown / blank-routing-key / null-event / body
  renderer / severity pass-through / severity fallback).
- Property-coercion tests in `PagerDutyPropertiesTest` prove
  the compact-ctor defaults + round-trip for all six fields.
- WireMock-driven IT in
  `PagerDutyRemediationDispatcherWireMockIT` proves the same
  outcome table end-to-end against a real HTTP server on a
  dynamic port; wire-format identical to production (JSON-path
  matchers on `$.routing_key`, `$.event_action`, `$.dedup_key`,
  `$.payload.severity`, `$.payload.source`). The transport-fault
  IT uses `Fault.CONNECTION_RESET_BY_PEER` per LD120 (NOT
  `withFixedDelay`) for deterministic transport-fault injection
  and asserts the result reason is in
  `["pagerduty:timeout","pagerduty:transport"]` to absorb the
  JDK HttpClient's reset-vs-timeout classification jitter.
- `RemediationMetricsTest` extension proves the three
  PagerDuty bootstrap counter series register at construct-time
  per LD106 + LD112.

## Links

- ADR-0028 (Spring for Apache Kafka direct, not SCSt -- consumer)
- ADR-0030 (sinks fan-out pattern -- `RestClient` HTTP/1.1
  precedent)
- ADR-0031 (`cortex.anomalies.v1` envelope contract)
- ADR-0032 (`RemediationDispatcher` SPI -- D1, D2, D3, D4, D6, D7)
- ADR-0033 (Slack adapter -- precedent for outcome table shape,
  blank-secret tolerance, body-renderer style)
- LD42 (HTTP/1.1 pin for outbound calls)
- LD106, LD112 (bootstrap Micrometer counter families)
- LD117 (Kafka offset = durability; no outbox on the relay)
- LD119 (Mockito `doReturn(bodySpec).when(bodySpec).body(any())`
  self-type stub for Spring `RestClient.RequestBodySpec`)
- LD120 (WireMock `Fault.CONNECTION_RESET_BY_PEER` for
  deterministic transport-fault injection)
- LD121 (HTTP outbound MUST pin BOTH
  `HttpClient.connectTimeout(...)` AND
  `factory.setReadTimeout(...)`)
- `docs/p5-to-p6-handoff.md` (envelope contract reference)
- [PagerDuty Events API v2 docs](https://developer.pagerduty.com/docs/events-api-v2/overview/)
- [PagerDuty Events API v2 severity enum](https://developer.pagerduty.com/api-reference/368ae3d938c9e-send-an-event)
