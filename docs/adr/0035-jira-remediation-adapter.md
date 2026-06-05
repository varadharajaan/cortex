# 0035. Jira `RemediationDispatcher` adapter -- REST API v3 + create-issue-only

- Status: accepted
- Date: 2026-06-05
- Deciders: @varadharajaan
- Tags: remediation, dispatcher, jira, rest-api-v3, http

## Context and problem statement

P6.0 / ADR-0032 locked the `RemediationDispatcher` SPI and shipped
the noop default. P6.1 / ADR-0033 landed the Slack adapter; P6.2 /
ADR-0034 landed the PagerDuty adapter. P6.3 lands the third
channel: Jira Cloud. Operators want confirmed anomaly verdicts off
`cortex.anomalies.v1` to land as auditable issues in the same
Jira project the SRE team already files post-incident retros into,
so the platform creates a durable record of every classifier
verdict that survives the on-call rotation handoff. The Jira API
surface is wide (REST API v3, REST API v2 legacy, JSM customer
portal, Jira Cloud OAuth 2.0 apps, etc.); this ADR records the
design choices the P6.3 adapter takes so P6.4 (DLQ + retry budget)
inherits a stable adapter contract.

Open questions this ADR settles:

1. Which Jira API to call (REST API v3 vs REST API v2 legacy vs
   Jira Service Management REST endpoints vs Jira Cloud OAuth
   2.0 apps).
2. Which scope of write to perform (create-issue-only vs full
   create / transition / resolve workflow inside the adapter).
3. How to classify HTTP outcomes (201 / 4xx / 429 / 5xx / IO)
   onto the `DispatchResult.OUTCOME_*` constants ADR-0032 D3
   froze.
4. How to authenticate (Basic-auth-with-API-token vs OAuth 2.0
   3LO vs OAuth 2.0 app-token).
5. Whether the issue description should be plain text or
   Atlassian Document Format (ADF).
6. How to map upstream `AnomalyEvent.severity` onto Jira metadata
   (priority field vs label vs custom field).
7. Whether to auto-assign the issue to a component owner.
8. How many credential / target fields are required before the
   adapter can dispatch (and what happens when any are blank).

## Decision drivers

- D1 -- the adapter MUST work with a single per-user API token
  (the Atlassian-minted API token paired with the
  user's email) and MUST NOT require an OAuth app install per
  deployment; the API token is the smallest credential surface
  that respects Jira's permission model (the issue is created as
  the configured user, inheriting that user's project + issue-type
  permissions). The four-field unconfigured signal (blank
  `baseUrl` / `email` / `apiToken` / `projectKey`) folds into one
  `jira:unconfigured` outcome rather than four distinct ones
  because operators only care that the adapter is OFF, not which
  field is missing -- a multi-line warning log on first dispatch
  attempt names the missing field for the operator.
- D2 -- the adapter MUST send create-issue only and MUST NOT
  transition or resolve issues from the pipeline. Transition
  belongs to the human responder (or a separate operator
  workflow); coupling auto-transition into the dispatcher would
  race with the on-call workflow and silently close issues the
  human is still triaging. Same rule ADR-0034 D2 enforced for
  PagerDuty.
- D3 -- the HTTP outcome -> `DispatchResult` mapping MUST be a
  pure function the operator can read from a single table; no
  hidden retries, no swallowed exceptions; every category ticks
  exactly one Micrometer counter row with the right
  `{channel=jira, outcome=...}` tags. Jira's create-issue
  success code is **201 Created** (not 200), so the 2xx
  classifier must not narrow to `==200`.
- D4 -- the description MUST be an Atlassian Document Format
  (ADF) doc, not plain text. Jira REST API v3 rejects plain-text
  `description` strings (returns 400 with a schema-violation
  error message); ADF is the documented v3 body shape and is
  what every Jira Cloud integration (Datadog, Sentry, PagerDuty
  webhook -> Jira) emits today.
- D5 -- severity MUST be expressed as a label (e.g.
  `anomaly-severity-high`) rather than the Jira priority field
  or a custom field. Labels are universally available across
  every project's issue-type scheme (priority is not -- some
  projects disable the priority field; custom fields require
  per-project administration). The configurable
  `severity-label-prefix` (default `anomaly-severity`) lets the
  operator pick a different namespace if `anomaly-severity-*`
  collides with an existing label scheme.
- D6 -- the adapter MUST honour ADR-0032 D6 (never throw on
  transient failures) and D7 (stay agnostic to the future P6.4
  DLQ + retry-budget axis); returning a typed `transient_failure`
  verdict satisfies both without breaking the SPI -- same
  contract Slack + PagerDuty accept in ADR-0033 + ADR-0034.
- D7 -- the adapter MUST tolerate blank credentials at boot so
  profiles that select the Jira provider without supplying the
  four secrets still boot green (the operator sees the
  `outcome=skipped, reason=jira:unconfigured` counter climb
  instead of CrashLoopBackOff) -- mirrors ADR-0033 D5 + ADR-0034
  D7.
- D8 -- outbound HTTP MUST use the same `RestClient` +
  HTTP/1.1-pinned `JdkClientHttpRequestFactory` pattern P5.3
  (`LokiSink` / `QuickwitSink`, LD42 + ADR-0030), P6.1
  (`SlackRemediationDispatcher`, ADR-0033 D6) and P6.2
  (`PagerDutyRemediationDispatcher`, ADR-0034 D8)
  standardised; both the connect-timeout AND the read-timeout
  MUST be pinned per LD121.

## Considered options

1. **Jira Cloud REST API v3 create-issue (`POST
   {baseUrl}/rest/api/3/issue`) + Basic-auth-with-API-token
   header + ADF description + label-based severity +
   create-issue-only + `RestClient` HTTP/1.1 pin (LD42 + LD121)
   + typed `DispatchResult` outcome classification table; blank
   credentials tolerated.** -- **ACCEPTED** (chosen option).

2. **Jira Service Management (JSM) REST endpoints (`POST
   {baseUrl}/rest/servicedeskapi/request`) to create customer
   requests in a JSM project.** -- **REJECTED**: JSM is the
   customer-portal surface; requests appear on the customer
   portal queue rather than the standard issue search Jira
   Software users live in. Most SRE teams file post-incident
   work in a standard Jira Software project, not a JSM service
   desk; the v3 create-issue endpoint is the right surface for
   internal automation-generated work items.

3. **Jira Cloud OAuth 2.0 (3LO) flow with per-tenant app token
   refresh.** -- **REJECTED**: same shape as PagerDuty OAuth
   rejected in ADR-0034 alternative #4. Higher credential
   surface (one OAuth app install per Jira tenant + per-tenant
   refresh-token rotation + a hosted callback URL) for zero
   functional benefit over a per-user API token the SRE team
   already manages via the Atlassian id.atlassian.com profile
   page. The API token is rotatable in the same UI.

4. **Spring Retry `@Retryable(maxAttempts=3,
   backoff=ExponentialBackoff)` on the adapter.** --
   **REJECTED**: contradicts ADR-0032 D6 + D7 (same reason
   ADR-0033 alternative #5 + ADR-0034 alternative #5 rejected it
   for Slack + PagerDuty). The SPI contract is "return a
   verdict, don't throw"; layering retry inside the adapter
   leaks the retry-budget axis ADR-0032 D7 explicitly reserved
   for P6.4. A failed `transient_failure` verdict ticks the
   metric (operator alerts on the rate) and P6.4 will decide the
   budget centrally across all channels.

5. **Resilience4j `@CircuitBreaker` + `@TimeLimiter` on the
   adapter.** -- **REJECTED** for the same reason as #4: P6.4
   will land the budget + breaker pattern uniformly across all
   three channels.

6. **Fail closed: refuse to boot if any of the four
   `baseUrl` / `email` / `apiToken` / `projectKey` fields are
   blank.** -- **REJECTED** per D7 + ADR-0033 D5 + ADR-0034 D7
   precedent: hostile to operator workflows where the Jira
   integration is provisioned after the service starts (e.g.
   dev smoke / preview environments / new tenant onboarding
   before the SRE team has minted an API token for the project).
   Fail open + surface the `jira:unconfigured` counter gives the
   operator visibility without blocking the rest of the
   pipeline.

7. **Plain-text `description` field (legacy v2 shape) instead of
   ADF.** -- **REJECTED** per D4: Jira REST API v3 rejects
   plain-text strings (the field type is required to be an ADF
   document); calling the v2 endpoint for the description-only
   field would split the create-issue call across two API
   versions and double the failure surface for no benefit. ADF
   is mandatory in v3 and the renderer cost is one nested-map
   helper.

8. **Auto-assign by component owner (look up the component's
   lead from `{baseUrl}/rest/api/3/component/{id}` and set
   `fields.assignee.accountId` accordingly).** -- **REJECTED**:
   adds a second HTTP round-trip per dispatch (doubling latency
   + failure surface), introduces a stateful component->owner
   cache the adapter would have to invalidate, and the on-call
   rotation already handles triage assignment via Jira's own
   automation rules (which can fire on the
   `cortex-remediation` label). Defer auto-assignment to Jira
   Automation; the adapter creates an unassigned issue with the
   right labels for the rule to pick up.

## Decision outcome

Chosen option: **#1 -- Jira Cloud REST API v3 create-issue +
Basic-auth-with-API-token + ADF description + label-based
severity + create-issue-only + `RestClient` HTTP/1.1 pin + typed
outcome classification + blank credentials tolerated**.

The SPI signature is exactly the one ADR-0032 D2 + D4 + D6 froze;
no changes to the dispatcher contract. The adapter ships as
`io.cortex.remediation.dispatch.JiraRemediationDispatcher` gated
by `cortex.remediation.dispatcher.provider=jira`. The `RestClient`
it consumes is built by `JiraHttpConfig` (a `@Configuration` class
itself gated by the same property) using the LD42 HTTP/1.1 pin +
LD121 dual-timeout pin (both `HttpClient.connectTimeout(...)` AND
`factory.setReadTimeout(...)`) + `JiraProperties.requestTimeout`
(default 5 s). Body shape:

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
          { "type": "text", "text": "severity: HIGH" } ] },
        { "type": "paragraph", "content": [
          { "type": "text", "text": "reason: checkout 5xx burst" } ] },
        { "type": "paragraph", "content": [
          { "type": "text", "text": "ts: 2026-06-04T15:00:00Z" } ] },
        { "type": "paragraph", "content": [
          { "type": "text", "text": "level: ERROR" } ] },
        { "type": "paragraph", "content": [
          { "type": "text", "text": "service: checkout" } ] },
        { "type": "paragraph", "content": [
          { "type": "text", "text": "message: 503 from /pay endpoint" } ] }
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

The Basic-auth header is built per request via
`Base64.getEncoder().encodeToString((email + ":" + apiToken)
.getBytes(StandardCharsets.UTF_8))`; the resulting value is
`Authorization: Basic <Base64>`. Atlassian's docs explicitly
recommend constructing the header per request rather than caching
because the API token is rotatable (and the lifetime is bounded
by whatever the operator's enterprise policy says); building it
per call also lets the LD22 secret-handling rules keep the token
out of logs (we never serialise the full credential block).

### HTTP outcome -> `DispatchResult` table (D3)

| HTTP outcome                                | `DispatchResult.outcome` | `reason`              |
|---------------------------------------------|--------------------------|-----------------------|
| 201                                         | `dispatched`             | `""`                  |
| 2xx (other)                                 | `dispatched`             | `""`                  |
| 429                                         | `transient_failure`      | `jira:429`            |
| 5xx                                         | `transient_failure`      | `jira:5xx:<code>`     |
| 4xx (other -- 400 / 401 / 403 / 404 / ...) | `permanent_failure`      | `jira:4xx:<code>`     |
| Read timeout                                | `transient_failure`      | `jira:timeout`        |
| Connection error                            | `transient_failure`      | `jira:transport`      |
| Other RuntimeEx                             | `transient_failure`      | `jira:unknown`        |
| Blank `baseUrl`/`email`/`apiToken`/`projectKey` | `skipped`            | `jira:unconfigured`   |
| Null event                                  | `skipped`                | `jira:null-event`     |

Per ADR-0032 D6 the adapter NEVER throws on a transient
downstream failure. The consumer acks the offset regardless and
the operator alerts on the failed-outcome metric. The
`HTTP_TOO_MANY_REQUESTS=429` constant lives in
`JiraRemediationDispatcher` because the Checkstyle MagicNumber
rule wants a named constant; the 4xx-catch-all classifies 401 /
403 / 404 as `permanent_failure` because the operator must fix
the credential / project-key config out-of-band before retrying
(a retry would just burn API-token rate-limit budget).

### Severity mapping (D5)

The upstream `AnomalyEvent.severity` is a free-form ALL-CAPS
string the classifier emits (e.g. `HIGH`, `MEDIUM`, `LOW`,
`CRITICAL`). The Jira mapping rule is:

1. Lowercase the upstream value.
2. Prepend the configurable `severity-label-prefix` (default
   `anomaly-severity`) with a `-` separator.
3. Emit the resulting string as a Jira label (e.g.
   `anomaly-severity-high`).

If the upstream severity is blank, no severity label is emitted
(the issue still carries the static `cortex-remediation` +
`tenant:<id>` labels). Jira labels MUST NOT contain spaces; the
lowercased severity is single-word ALL-CAPS-or-not so this
constraint is naturally satisfied. The label-based approach
sidesteps every per-project priority-scheme issue while still
letting operators build Jira filter queries like
`labels = "anomaly-severity-high"` for the on-call dashboard.

### Positive consequences

- One Jira credential pair per service (email + API token)
  rather than one OAuth app per Jira tenant; the SRE team already
  manages API tokens via id.atlassian.com.
- The body lands as a real issue in the operator's existing
  project search; no JSM customer-portal indirection; no
  per-project UI configuration required.
- The HTTP outcome table is a deterministic pure function of
  the HTTP response; an operator reading the counter knows
  exactly which downstream condition triggered the increment.
- ADF description gives the operator a structured per-field
  view of the anomaly (one paragraph per field) that renders
  cleanly in Jira's web UI, mobile app, and Confluence
  embeds.
- The label-based severity model works on EVERY project's
  issue-type scheme without per-project administration; the
  `severity-label-prefix` config knob lets operators rename
  the namespace if it collides.
- The adapter introduces zero new dependencies beyond what
  the parent POM already manages (`RestClient` is core
  Spring; WireMock is already in the parent test scope via
  the `wiremock.version` property).
- The blank-credential tolerance keeps preview / smoke
  environments green by default; operator opt-in is the four
  env vars (`CORTEX_REMEDIATION_JIRA_BASE_URL`,
  `..._EMAIL`, `..._API_TOKEN`, `..._PROJECT_KEY`).
- Retry / circuit-breaker logic stays out of the adapter so
  P6.4's retry-budget design is unconstrained.
- Create-issue-only means no race with the on-call human's
  triage workflow; downstream Jira Automation can fire on
  the `cortex-remediation` label to drive assignment +
  transition without the adapter holding state.

### Negative consequences

- Create-issue-only means the dispatcher cannot
  auto-transition issues (e.g. close on follow-up "all clear"
  verdict). Intentional per D2 but is a tradeoff vs a future
  "issue lifecycle" workflow inside the adapter.
- The 5 s default request timeout means a Jira-side latency
  spike can cause up to ~5 s of consumer-thread blocking per
  record. Acceptable because: (a) REST API v3 create-issue
  p99 in healthy state is well under 2 s; (b) the consumer
  thread pool is sized for parse + dispatch latency
  dominated by upstream Kafka poll cadence anyway; (c) P6.4
  will land the budget breaker that caps cumulative slow-Jira
  damage.
- The label-based severity model is less visually prominent
  than the Jira priority field (which renders with coloured
  icons in the issue list). Acceptable because (a) priority
  is not universally available across projects per D5; (b)
  operators can layer a Jira Automation rule that sets
  `priority` from `labels` if they want the visual; (c) the
  on-call dashboard query (`labels = "anomaly-severity-high"
  AND statusCategory != Done`) reads identically.
- Issues are unassigned by default; the operator must
  configure Jira Automation (or accept that the on-call
  rotation picks them up on dashboard scan) to drive
  assignment. Acceptable per the alternative #8 rejection
  rationale.

## Verification

- Mockito-driven unit tests in
  `JiraRemediationDispatcherTest` cover every row of the
  outcome table (201 / 429 / 5xx / 4xx-other / 401 / 404 /
  timeout / transport / unknown / blank-credentials (all four
  fields) / null-event / body renderer + ADF description shape
  / Basic-auth header build / labels).
- Property-coercion tests in `JiraPropertiesTest` prove the
  compact-ctor defaults + round-trip for all seven fields.
- WireMock-driven IT in
  `JiraRemediationDispatcherWireMockIT` proves the same
  outcome table end-to-end against a real HTTP server on a
  dynamic port; wire-format identical to production
  (Authorization header equality + JSON-path matchers on
  `$.fields.project.key`, `$.fields.summary`,
  `$.fields.description.type`, `$.fields.issuetype.name`, and
  the three labels). The transport-fault IT uses
  `Fault.CONNECTION_RESET_BY_PEER` per LD120 (NOT
  `withFixedDelay`) for deterministic transport-fault injection
  and asserts the result reason is in
  `["jira:timeout","jira:transport"]` to absorb the JDK
  HttpClient's reset-vs-timeout classification jitter.
- `RemediationMetricsTest` extension proves the three Jira
  bootstrap counter series register at construct-time per
  LD106 + LD112.

## Links

- ADR-0028 (Spring for Apache Kafka direct, not SCSt -- consumer)
- ADR-0030 (sinks fan-out pattern -- `RestClient` HTTP/1.1
  precedent)
- ADR-0031 (`cortex.anomalies.v1` envelope contract)
- ADR-0032 (`RemediationDispatcher` SPI -- D1, D2, D3, D4, D6, D7)
- ADR-0033 (Slack adapter -- precedent for outcome table shape,
  blank-secret tolerance, body-renderer style)
- ADR-0034 (PagerDuty adapter -- precedent for severity mapping
  shape, dual-timeout pin, dedup semantics)
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
- [Jira Cloud REST API v3 -- Create issue](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/#api-rest-api-3-issue-post)
- [Atlassian Document Format (ADF)](https://developer.atlassian.com/cloud/jira/platform/apis/document/structure/)
- [Jira Cloud Basic auth with API tokens](https://developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/)
