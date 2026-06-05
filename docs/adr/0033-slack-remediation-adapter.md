# 0033. Slack `RemediationDispatcher` adapter -- Incoming Webhook + plain-text body

- Status: accepted
- Date: 2026-06-04
- Deciders: @varadharajaan
- Tags: remediation, dispatcher, slack, webhook, http

## Context and problem statement

P6.0 / ADR-0032 locked the `RemediationDispatcher` SPI and shipped
the noop default. P6.1 lands the first real adapter: Slack.
Operators need confirmed anomaly verdicts off `cortex.anomalies.v1`
to land in a human-attended Slack channel (`#sre-incidents` or
similar) so the platform actually closes the loop on the AI
classifier. The Slack workspace API surface is large; this ADR
records the design choices the P6.1 adapter takes so P6.2
(PagerDuty) and P6.3 (Jira) can mirror or diverge with explicit
intent.

Open questions this ADR settles:

1. Which Slack API to call (Incoming Webhook vs `chat.postMessage`
   bot-token OAuth vs Workflow Builder webhook).
2. Which payload shape to send (plain `"text"` field vs Block Kit
   rich layout vs attachments).
3. How to classify HTTP outcomes (2xx / 4xx / 429 / 5xx / IO) onto
   the `DispatchResult.OUTCOME_*` constants ADR-0032 D3 froze.
4. Whether to add retry/backoff to the adapter or defer to the
   P6.4 retry-budget design.
5. Whether to fail closed (refuse to boot without webhook URL) or
   fail open (boot green, surface skipped-outcome counter).

## Decision drivers

- D1 -- the adapter MUST work without the operator provisioning a
  Slack OAuth app per deployment; the Incoming-Webhook URL is the
  smallest credential surface that still respects channel routing
  and tenant isolation (one webhook per Slack channel, configured
  out-of-band by the workspace admin).
- D2 -- the body MUST be readable on mobile (where SREs page from)
  without depending on Block Kit rendering; the first ship
  optimises for "the alert reaches the on-call human", richer
  formatting (Block Kit, action buttons) is deferred to a P6.x
  enhancement after operator feedback.
- D3 -- the HTTP outcome -> `DispatchResult` mapping MUST be a
  pure function the operator can read from a single table; no
  hidden retries, no swallowed exceptions; every category ticks
  exactly one Micrometer counter row with the right
  `{channel=slack, outcome=...}` tags.
- D4 -- the adapter MUST honour ADR-0032 D6 (never throw on
  transient failures) and D7 (stay agnostic to the future P6.4
  DLQ + retry-budget axis); returning a typed `transient_failure`
  verdict satisfies both without breaking the SPI.
- D5 -- the adapter MUST tolerate a blank webhook URL at boot so
  profiles that select the Slack provider without supplying the
  secret still boot green (the operator sees the
  `outcome=skipped, reason=slack:unconfigured` counter climb
  instead of CrashLoopBackOff).
- D6 -- outbound HTTP MUST use the same `RestClient` +
  HTTP/1.1-pinned `JdkClientHttpRequestFactory` pattern the P5.3
  `LokiSink` / `QuickwitSink` standardised (LD42 + ADR-0030
  symmetry); the parent POM already manages no other HTTP client
  dependency.

## Considered options

1. **Slack Incoming Webhook (URL POST, simple JSON body) +
   plain-text `"text"` field + `RestClient` HTTP/1.1 pin (LD42) +
   typed `DispatchResult` outcome classification table; blank URL
   tolerated.** -- **ACCEPTED** (chosen option).

2. **Slack `chat.postMessage` REST API with a workspace OAuth
   bot token in `Authorization: Bearer ...`.** -- **REJECTED**:
   higher credential surface (one app install per workspace +
   per-tenant bot token rotation), and the bot must be invited
   to each target channel by hand. The webhook URL collapses
   "credential" and "channel binding" into one configuration
   item the SRE team already manages out-of-band.

3. **Slack Workflow Builder webhooks.** -- **REJECTED**: the
   webhook URL surface is identical to the Incoming Webhook but
   the body schema is workspace-defined (each workspace admin
   names the trigger variables). That couples the adapter shape
   to a Slack admin's Workflow Builder config; the plain
   Incoming Webhook is portable across workspaces with zero
   per-tenant variation.

4. **Block Kit rich layout with header / divider / fields /
   actions blocks.** -- **REJECTED** for the first ship: Block
   Kit is the right answer for an operator dashboard ("click to
   acknowledge", "click to escalate") but that capability lands
   in a richer P6.x enhancement once the basic Slack delivery
   loop has SRE feedback. Block Kit also doubles the body size
   per alert; the plain text field renders identically on every
   Slack client (web / desktop / iOS / Android / unread badge
   preview).

5. **Spring Retry `@Retryable(maxAttempts=3,
   backoff=ExponentialBackoff)` on the adapter.** -- **REJECTED**:
   contradicts ADR-0032 D6 + D7. The SPI contract is "return a
   verdict, don't throw"; layering retry inside the adapter
   leaks the retry-budget axis ADR-0032 D7 explicitly reserved
   for P6.4. A failed `transient_failure` verdict ticks the
   metric (operator alerts on the rate), and P6.4 will decide
   the budget centrally across all channels (Slack + PagerDuty
   + Jira) rather than each adapter rolling its own.

6. **Resilience4j `@CircuitBreaker` + `@TimeLimiter` on the
   adapter.** -- **REJECTED** for the same reason as #5: P6.4
   will land the budget + breaker pattern uniformly. P6.1 keeps
   the adapter a pure HTTP call + outcome classification so the
   future P6.4 wrapper can compose without un-wiring existing
   logic.

7. **Fail closed: refuse to boot if `webhook-url` is blank.** --
   **REJECTED**: hostile to operator workflows where the Slack
   integration is provisioned after the service starts (e.g. dev
   smoke / preview environments / new tenant onboarding). Fail
   open + surface the skipped-outcome counter gives the operator
   visibility without blocking the rest of the pipeline (the
   noop default at ADR-0032 D5 sets the same precedent).

## Decision outcome

Chosen option: **#1 -- Slack Incoming Webhook + plain-text body +
`RestClient` HTTP/1.1 pin + typed outcome classification + blank
URL tolerated**.

The SPI signature is exactly the one ADR-0032 D2 + D4 + D6 froze;
no changes to the dispatcher contract. The adapter ships as
`io.cortex.remediation.dispatch.SlackRemediationDispatcher`
gated by `cortex.remediation.dispatcher.provider=slack`. The
`RestClient` it consumes is built by `SlackHttpConfig` (a
`@Configuration` class itself gated by the same property) using
the LD42 HTTP/1.1 pin + `SlackProperties.requestTimeout` (default
5 s). Body shape:

```json
{
  "text": ":rotating_light: HIGH anomaly on checkout (tenant=tenant-abc): checkout 5xx burst",
  "username": "cortex-remediation",
  "channel": "#sre-incidents"
}
```

(`username` + `channel` are optional overrides; both default to
empty and are dropped from the body when blank.)

### HTTP outcome -> `DispatchResult` table (D3)

| HTTP outcome      | `DispatchResult.outcome` | `reason`            |
|-------------------|--------------------------|---------------------|
| 2xx               | `dispatched`             | `""`                |
| 429               | `transient_failure`      | `slack:429`         |
| 5xx               | `transient_failure`      | `slack:5xx:<code>`  |
| 4xx (other)       | `permanent_failure`      | `slack:4xx:<code>`  |
| Read timeout      | `transient_failure`      | `slack:timeout`     |
| Connection error  | `transient_failure`      | `slack:transport`   |
| Other RuntimeEx   | `transient_failure`      | `slack:unknown`     |
| Blank webhook URL | `skipped`                | `slack:unconfigured`|
| Null event        | `skipped`                | `slack:null-event`  |

Per ADR-0032 D6 the adapter NEVER throws on a transient
downstream failure. The consumer acks the offset regardless and
the operator alerts on the failed-outcome metric.

### Positive consequences

- One Slack credential per channel (webhook URL) rather than one
  OAuth app per workspace; the SRE team already manages webhook
  URLs out-of-band.
- The body is human-readable on mobile out of the box; no Block
  Kit dependency, no per-workspace formatting drift.
- The HTTP outcome table is a deterministic pure function of the
  HTTP response; an operator reading the counter knows exactly
  which downstream condition triggered the increment.
- The adapter introduces zero new dependencies beyond what the
  parent POM already manages (`RestClient` is core Spring;
  WireMock is already in the parent test scope per the
  `wiremock.version` property).
- The blank-URL tolerance keeps preview / smoke environments
  green by default; operator opt-in is the env-var
  `CORTEX_REMEDIATION_SLACK_WEBHOOK_URL`.
- Retry / circuit-breaker logic stays out of the adapter so
  P6.4's retry-budget design is unconstrained.

### Negative consequences

- Plain-text format means no click-to-acknowledge buttons; that
  capability is deferred to a P6.x enhancement.
- A misconfigured webhook (e.g. revoked URL) returns 404 or 410,
  classified as `permanent_failure`; the operator must redeploy
  the env var to recover (this is intentional per D5).
- The 5 s default request timeout means a Slack-side incident
  can cause up to ~5 s of consumer-thread blocking per record.
  Acceptable because: (a) Slack webhook p99 in healthy state is
  <500 ms; (b) the consumer thread pool is sized for parse +
  dispatch latency which is dominated by the upstream Kafka poll
  cadence anyway; (c) P6.4 will land the budget breaker that
  caps cumulative slow-Slack damage.

## Verification

- Mockito-driven unit tests in
  `SlackRemediationDispatcherTest` cover every row of the
  outcome table (2xx / 429 / 5xx / 4xx-other / timeout /
  transport / blank-URL / null-event / body renderer).
- WireMock-driven IT in
  `SlackRemediationDispatcherWireMockIT` proves the same
  outcome table end-to-end against a real HTTP server on a
  dynamic port; wire-format identical to production.
- `RemediationMetricsTest` extension proves the three Slack
  bootstrap counter series register at construct-time per
  LD106 + LD112.

## Links

- ADR-0028 (Spring for Apache Kafka direct, not SCSt -- consumer)
- ADR-0030 (sinks fan-out pattern -- `RestClient` HTTP/1.1
  precedent)
- ADR-0031 (`cortex.anomalies.v1` envelope contract)
- ADR-0032 (`RemediationDispatcher` SPI -- D1, D2, D3, D4, D6, D7)
- LD42 (HTTP/1.1 pin for outbound calls)
- LD106, LD112 (bootstrap Micrometer counter families)
- LD117 (Kafka offset = durability; no outbox on the relay)
- `docs/p5-to-p6-handoff.md` (envelope contract reference)
- [Slack Incoming Webhooks docs](https://api.slack.com/messaging/webhooks)
