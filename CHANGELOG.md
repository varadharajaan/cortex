# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- P6.0a: log-remediation-service strict-rules cleanup
  (PR for #95, ADR-0036; no behavioural change — refactor only).
  Brings `log-remediation-service` to the A2 / A3 / A4 / A6 / A7
  conformance bar BEFORE the P6.1a closer ships, so the closer
  cross-phase IT runs against the cleaned-up dispatcher surface
  (LD104 closer-pattern).
  - **A6.1 — `@Validated`** added to all three
    `@ConfigurationProperties` records
    (`SlackProperties`, `PagerDutyProperties`, `JiraProperties`)
    so Spring's validation hook is wired at the configuration
    boundary even though no field-level constraints exist yet.
  - **A3.1 / A3.2 / DRY — composition over inheritance**: new
    package-private `dispatch/RestDispatchTemplate` helper
    (Effective Java item 18) owns the outer try/catch + HTTP
    outcome classification (429 -> `transient`; 5xx ->
    `transient`; other 4xx -> `permanent`; timeout ->
    `transient`; IO -> `transient`; unknown -> `transient`) +
    transport classification + no-throw-on-transient discipline
    (ADR-0032 D6). Every real-channel adapter
    (`Slack/PagerDuty/Jira RemediationDispatcher`) is now
    `final class` with its `dispatch(event)` body collapsed to
    `return template.dispatch(event, this::isConfigured, this::executePost);`.
    Each adapter carries only its channel-specific concerns
    (configured-check, endpoint, body builder, auth header).
    Behaviour preserved bit-for-bit -- every WireMock IT
    `DispatchResult` reason string is byte-identical to P6.3.
  - **A3.2 — OCP `RemediationMetrics` refactor**: constructor
    signature flipped to
    `RemediationMetrics(MeterRegistry, List<RemediationDispatcher>)`;
    `@PostConstruct bootstrapMeters()` loops over the injected
    dispatcher list and calls `bootstrap(d.channelId(), OUTCOME_*)`
    for each of the three failable outcomes. The hand-coded
    9-call bootstrap block is gone. Adding the future P6.4
    retry/DLQ channel now requires zero edits to this class.
  - **A4.2 — Lombok constructor injection**: `AnomalyConsumer`,
    `AnomalyEnvelopeParser`, `RemediationMetrics`, and all
    three real-channel adapters now use
    `@RequiredArgsConstructor` (+ `@Slf4j` where the logger
    field was hand-rolled). Hand-rolled public constructors +
    `this.x = x` blocks deleted.
  - **A2.3 — Javadoc placement / LD5 SUPERSEDED**: every
    private-method Javadoc block across the module deleted
    (was an artifact of the universal-Javadoc enforcer recorded
    as `memory.md` LD5). The Checkstyle enforcer itself
    (`checkstyle.xml`) updated to honour A2.3: scope on
    `MissingJavadocMethod` / `MissingJavadocType` / `JavadocType`
    raised from `private` to `public`/`protected`,
    `JavadocMethod.accessModifiers` reduced to
    `"public,protected"`. Header comment cites A2.3 + the LD5
    supersede.
  - **A7 — constants centralisation**: new package
    `io.cortex.remediation.constants` with `RemediationHttp.java`
    (final utility class, private `UnsupportedOperationException`
    ctor, single constant `TOO_MANY_REQUESTS = 429`) +
    Part-9.5-shaped `package-info.java`. Removes the duplicated
    `HTTP_TOO_MANY_REQUESTS = 429` declarations from each
    real-channel adapter.
  - **SPI extension**: new method
    `String channelId()` on `RemediationDispatcher`; every
    adapter returns its `DispatchResult.CHANNEL_*` constant.
    Used by the OCP-flipped `RemediationMetrics` bootstrap
    loop.
  - **Test surface**: `RemediationMetricsTest` rewritten (8
    tests) with a `fakeDispatcher(channelId)` helper +
    `bootstrapIteratesOverMultipleDispatchers` regression pin
    for the OCP loop semantics. `AnomalyConsumerTest` +
    `AnomalyConsumerKafkaIT` updated to construct
    `RemediationMetrics` with the new
    `(registry, List<RemediationDispatcher>)` signature.
    `ArchitectureTest` extended with a `Constants` ArchUnit
    layer accessible only by `Dispatch`. All 16 ITs + every
    unit test green; 0 Checkstyle errors; JaCoCo BUNDLE
    0.80/0.80 met.
  - ADR-0036 -- documents the supersede, the composition vs
    inheritance decision, the OCP flip, and the four rejected
    alternatives (abstract base, utility class, Spring AOP
    aspect, hand-coded list with TODO). `docs/adr/INDEX.md`
    row + count bump 35 -> 36 + refreshed-on date.

### Added

- P6.3: log-remediation-service Jira Cloud REST API v3 adapter
  (PR for #91, ADR-0035; LD104 closer-pattern -- Legs B-E still
  roll forward to the P6.1a closer that ships smoke + Postman +
  cross-phase regression ONCE for Slack + PagerDuty + Jira
  together after this ship).
  - New `dispatch/JiraProperties` `@ConfigurationProperties`
    record (`cortex.remediation.jira.{base-url, email, api-token,
    request-timeout, project-key, issue-type,
    severity-label-prefix}`); blank `base-url` / `email` /
    `api-token` / `project-key` tolerated per ADR-0035 D7 so
    preview/smoke envs boot green. Compact ctor coerces null/blank
    inputs to documented defaults for every field EXCEPT the four
    credential / target fields (which stay blank as the
    unconfigured signal). Default `issue-type` is `Bug`; default
    `severity-label-prefix` is `anomaly-severity` (joined to the
    lowercased AnomalyEvent severity via `-`).
  - New `dispatch/JiraHttpConfig` `@Configuration` providing the
    `jiraRestClient` bean wired with HTTP/1.1-pinned
    `JdkClientHttpRequestFactory` (LD42 symmetry with
    `LokiSink`/`QuickwitSink`/`SlackHttpConfig`/`PagerDutyHttpConfig`)
    AND pinning BOTH `HttpClient.connectTimeout(...)` and
    `factory.setReadTimeout(...)` per LD121; gated by
    `cortex.remediation.dispatcher.provider=jira` +
    `@EnableConfigurationProperties(JiraProperties.class)`
    (the boot app deliberately does NOT carry
    `@ConfigurationPropertiesScan`).
  - New `dispatch/JiraRemediationDispatcher` -- third real
    `RemediationDispatcher` implementation; posts the REST API v3
    create-issue envelope (`{fields:{project:{key}, summary,
    description{ADF doc}, issuetype:{name}, labels}}`) to
    `{baseUrl}/rest/api/3/issue` with an `Authorization: Basic
    <Base64(email:apiToken)>` header per ADR-0035 D2 and the
    ADR-0035 D3 HTTP outcome -> `DispatchResult` classification
    table (2xx -> `dispatched`; 429 ->
    `transient_failure/jira:429`; 5xx ->
    `transient_failure/jira:5xx:<code>`; other 4xx ->
    `permanent_failure/jira:4xx:<code>`; timeout ->
    `transient_failure/jira:timeout`; transport ->
    `transient_failure/jira:transport`; unknown ->
    `transient_failure/jira:unknown`; blank credentials ->
    `skipped/jira:unconfigured`; null event ->
    `skipped/jira:null-event`). ADF description is built as one
    paragraph node per non-blank `AnomalyEvent` field so Jira's
    UI renders each detail on its own line. Labels rendered as
    `["cortex-remediation", "tenant:<tenantId>",
    "<severityLabelPrefix>-<severity-lowercased>"]`. Honours
    ADR-0032 D6 (never throws on transient) + D7 (stays agnostic
    to future P6.4 retry-budget).
  - `DispatchResult` extension: `CHANNEL_JIRA` constant sits
    beside the existing `CHANNEL_SLACK` + `CHANNEL_PAGERDUTY`.
  - `RemediationMetrics` extension: bootstrap-register the three
    Jira outcome series (`{channel=jira,
    outcome=dispatched|transient_failure|permanent_failure,
    tenant_id=unknown}`) at construct time per LD106 + LD112.
  - `application.yml` + `src/test/resources/application.yml`:
    `cortex.remediation.jira.*` block with env-var defaults;
    main yml uses blank env-var default for `base-url` / `email` /
    `api-token` / `project-key`; test yml uses literal `""`
    values so the boot stays green even when the Jira provider is
    selected by an opt-in test slice.
  - Test surface: `JiraPropertiesTest` (4 tests -- compact-ctor
    null-coerce, blank-coerce, verbatim round-trip, default
    request timeout), `JiraRemediationDispatcherTest` (13 Mockito
    tests covering every outcome-table row + body renderer + ADF
    description shape + Basic-auth header build + labels;
    LD119-compliant `doReturn(bodySpec).when(bodySpec).body(any(Object.class))`
    self-type stub for `RequestBodySpec`),
    `JiraRemediationDispatcherWireMockIT` (5 IT tests against an
    in-process WireMock server on a dynamic port -- happy 201,
    401, 429, 500, transport-fault via LD120
    `Fault.CONNECTION_RESET_BY_PEER`; happy-path asserts both
    the `Authorization: Basic <Base64>` header and JSON-path
    matchers on `$.fields.project.key`, `$.fields.summary`,
    `$.fields.description.type`, `$.fields.issuetype.name`, and
    the three labels), plus 1 new
    `bootstrapRegistersAllThreeJiraOutcomeSeries` test in
    `RemediationMetricsTest`.
  - ADR-0035 -- Jira `RemediationDispatcher` adapter (REST API v3
    create-issue + Basic-auth-with-API-token + ADF description +
    label-based severity + create-issue-only + outcome
    classification); 8 rejected alternatives (Jira Service
    Management REST endpoints, Jira Cloud OAuth 2.0, Spring
    Retry, Resilience4j `@CircuitBreaker`, fail-closed boot,
    plain-text description vs ADF, auto-assign by component
    owner, account-level API token vs per-user). `docs/adr/INDEX.md`
    row + count bump 34 -> 35 + refreshed-on date.
  - `log-remediation-service/README.md` status banner bump
    (`P6.0..P6.2 SHIPPED` -> `P6.0..P6.3 SHIPPED`) +
    "Channel adapters -> Jira (P6.3, ADR-0035)" section.

- P6.2: log-remediation-service PagerDuty Events API v2 adapter
  (PR for #89, ADR-0034; LD104 closer-pattern -- Legs B-E still
  roll forward to the P6.1a closer that ships smoke + Postman +
  cross-phase regression ONCE for Slack + PagerDuty + Jira
  together after P6.3).
  - New `dispatch/PagerDutyProperties` `@ConfigurationProperties`
    record (`cortex.remediation.pagerduty.{routing-key,
    request-timeout, events-url, dedup-key-template, source,
    severity-default}`); blank routing key tolerated per
    ADR-0034 D7 so preview/smoke envs boot green. Compact ctor
    coerces null/blank inputs to documented defaults for every
    field EXCEPT `routingKey` (which stays blank as the
    unconfigured signal). Default dedup-key template is the
    single-brace literal `{tenantId}:{eventId}` (NOT Spring
    `${...}` syntax) to dodge the property-placeholder parser at
    boot.
  - New `dispatch/PagerDutyHttpConfig` `@Configuration` providing
    the `pagerDutyRestClient` bean wired with HTTP/1.1-pinned
    `JdkClientHttpRequestFactory` (LD42 symmetry with
    `LokiSink`/`QuickwitSink`/`SlackHttpConfig`) AND pinning BOTH
    `HttpClient.connectTimeout(...)` and
    `factory.setReadTimeout(...)` per LD121; gated by
    `cortex.remediation.dispatcher.provider=pagerduty` +
    `@EnableConfigurationProperties(PagerDutyProperties.class)`
    (the boot app deliberately does NOT carry
    `@ConfigurationPropertiesScan`).
  - New `dispatch/PagerDutyRemediationDispatcher` -- second real
    `RemediationDispatcher` implementation; posts the Events API
    v2 envelope (`{routing_key, event_action:"trigger",
    dedup_key, payload:{summary, severity, source,
    custom_details}}`) to `https://events.pagerduty.com/v2/enqueue`
    with the ADR-0034 D3 HTTP outcome -> `DispatchResult`
    classification table (2xx -> `dispatched`; 429 ->
    `transient_failure/pagerduty:429`; 5xx ->
    `transient_failure/pagerduty:5xx:<code>`; other 4xx ->
    `permanent_failure/pagerduty:4xx:<code>`; timeout ->
    `transient_failure/pagerduty:timeout`; transport ->
    `transient_failure/pagerduty:transport`; unknown ->
    `transient_failure/pagerduty:unknown`; blank routing key ->
    `skipped/pagerduty:unconfigured`; null event ->
    `skipped/pagerduty:null-event`). Severity mapping per
    ADR-0034 D6: pass-through `critical|error|warning|info`,
    else fall back to `severity-default` (default `"error"`)
    with the raw upstream value preserved in
    `payload.custom_details.rawSeverity`. Dedup-key substitution
    via two `String.replace` calls against `{tenantId}` +
    `{eventId}`. Honours ADR-0032 D6 (never throws on transient)
    + D7 (stays agnostic to future P6.4 retry-budget).
  - `DispatchResult` extension: `CHANNEL_PAGERDUTY` constant
    sits beside the existing `CHANNEL_SLACK`.
  - `RemediationMetrics` extension: bootstrap-register the three
    PagerDuty outcome series
    (`{channel=pagerduty, outcome=dispatched|transient_failure|permanent_failure,
    tenant_id=unknown}`) at construct time per LD106 + LD112.
  - `application.yml` + `src/test/resources/application.yml`:
    `cortex.remediation.pagerduty.*` block with env-var defaults;
    main yml uses blank env-var default for `dedup-key-template`
    (compact-ctor coerces) to dodge the Spring `${...}` parser's
    inner-colon ambiguity; test yml uses the literal quoted
    `"{tenantId}:{eventId}"`.
  - Test surface: `PagerDutyPropertiesTest` (4 tests --
    compact-ctor null-coerce, blank-coerce, verbatim round-trip,
    default request timeout), `PagerDutyRemediationDispatcherTest`
    (14 Mockito tests covering every outcome-table row + body
    renderer + severity pass-through + severity fallback;
    LD119-compliant `doReturn(bodySpec).when(bodySpec).body(any(Object.class))`
    self-type stub for `RequestBodySpec`),
    `PagerDutyRemediationDispatcherWireMockIT` (5 IT tests
    against an in-process WireMock server on a dynamic port --
    happy 202, 429, 500, 400, transport-fault via LD120
    `Fault.CONNECTION_RESET_BY_PEER`), plus 1 new
    `bootstrapRegistersAllThreePagerDutyOutcomeSeries` test in
    `RemediationMetricsTest`.
  - ADR-0034 -- PagerDuty `RemediationDispatcher` adapter
    (Events API v2 enqueue + trigger-only + deterministic
    dedup-key + outcome classification + severity-mapping
    fallback); 8 rejected alternatives (REST API v2 Incidents
    endpoint, full trigger/ack/resolve workflow, PagerDuty Apps
    OAuth, Spring Retry `@Retryable`, Resilience4j
    `@CircuitBreaker`, fail-closed boot, raise on unknown
    severity). `docs/adr/INDEX.md` row + count bump 33 -> 34 +
    refreshed-on date.
  - `log-remediation-service/README.md` status banner bump
    (`P6.0..P6.1 SHIPPED` -> `P6.0..P6.2 SHIPPED`) +
    "Channel adapters -> PagerDuty (P6.2, ADR-0034)" section.

- P6.1: log-remediation-service Slack webhook adapter (PR for #87,
  ADR-0033; LD104 closer-pattern -- Legs B-E roll forward to the
  P6.1a closer that ships smoke + Postman + cross-phase regression
  ONCE for Slack + PagerDuty + Jira together after P6.2 + P6.3).
  - New `dispatch/SlackProperties` `@ConfigurationProperties` record
    (`cortex.remediation.slack.{webhook-url, request-timeout,
    username, channel-override}`); blank webhook URL tolerated per
    ADR-0033 D5 so preview/smoke envs boot green.
  - New `dispatch/SlackHttpConfig` `@Configuration` providing the
    `slackRestClient` bean wired with HTTP/1.1-pinned
    `JdkClientHttpRequestFactory` (LD42 symmetry with
    `LokiSink`/`QuickwitSink`); gated by
    `cortex.remediation.dispatcher.provider=slack`.
  - New `dispatch/SlackRemediationDispatcher` -- first real
    `RemediationDispatcher` implementation; posts plain-text JSON
    body to Slack Incoming Webhook with the ADR-0033 D3 HTTP
    outcome -> `DispatchResult` classification table (2xx ->
    `dispatched`; 429 -> `transient_failure/slack:429`; 5xx ->
    `transient_failure/slack:5xx:<code>`; other 4xx ->
    `permanent_failure/slack:4xx:<code>`; timeout ->
    `transient_failure/slack:timeout`; transport ->
    `transient_failure/slack:transport`; blank URL ->
    `skipped/slack:unconfigured`; null event ->
    `skipped/slack:null-event`). Honours ADR-0032 D6 (never throws
    on transient) + D7 (stays agnostic to future P6.4 retry-budget).
  - `DispatchResult` extension: `CHANNEL_SLACK` constant +
    `dispatched(channel)` / `transientFailure(channel, reason)` /
    `permanentFailure(channel, reason)` factory methods.
  - `RemediationMetrics` extension: bootstrap-register the three
    Slack outcome series
    (`{channel=slack, outcome=dispatched|transient_failure|permanent_failure,
    tenant_id=unknown}`) at construct time per LD106 + LD112.
  - `application.yml` + `src/test/resources/application.yml`:
    `cortex.remediation.slack.*` block with env-var defaults; blank
    webhook URL keeps both prod + test boot green.
  - Test surface: `SlackPropertiesTest` (3 tests -- compact-ctor
    defaults + verbatim round-trip + default constant),
    `SlackRemediationDispatcherTest` (12 Mockito tests covering
    every outcome-table row + body renderer username/channel
    overrides), `SlackRemediationDispatcherWireMockIT` (5 IT tests
    against an in-process WireMock server on a dynamic port --
    happy 200, 429, 500, 400, slow-timeout), plus 2 new assertions
    in `RemediationMetricsTest` for the Slack bootstrap series +
    the null-tag fallback path.
  - New parent-managed test dep usage: `org.wiremock:wiremock-
    standalone` (first Java-test consumer in this module).
  - ADR-0033 -- Slack `RemediationDispatcher` adapter (Incoming
    Webhook + plain-text body + outcome classification + no
    in-adapter retry); 6 rejected alternatives (OAuth
    `chat.postMessage`, Slack Workflow Builder webhooks, Block
    Kit rich layout, Spring Retry `@Retryable`, Resilience4j
    `@CircuitBreaker`, fail-closed boot). `docs/adr/INDEX.md` row
    + count bump 32 -> 33 + refreshed-on date.
  - `log-remediation-service/README.md` status banner bump +
    "Channel adapters -> Slack (P6.1)" section.

- P6.0: log-remediation-service scaffold (PR for #84, ADR-0032).
  - New Maven module `log-remediation-service` on port `:8096`
    (parent `pom.xml` `<module>` block uncommented).
  - `CortexRemediationApplication` `@SpringBootApplication`
    `@EnableKafka` `@EnableDiscoveryClient` boot class.
  - `consume/AnomalyConsumer` `@KafkaListener` on
    `${cortex.remediation.topic}` (default
    `cortex.anomalies.v1`) with manual `Acknowledgment` per
    LD79 + ADR-0028 D1 symmetry.
  - `parse/AnomalyEnvelopeParser` -- decodes CloudEvents 1.0
    structured-mode JSON via `cloudevents-json-jackson`;
    enforces `specversion="1.0"` + `type="io.cortex.anomaly.v1"`
    per `docs/p5-to-p6-handoff.md` section 3.
  - `parse/AnomalyEvent` 8-field record (eventId, tenantId,
    severity, reason, ts, level, service, message) matching
    the producer-side ADR-0031 contract.
  - `parse/FailureReason` enum (`INVALID_ENVELOPE`,
    `WRONG_TYPE`, `MISSING_DATA`) + `parse/ParseException`.
  - `dispatch/RemediationDispatcher` SPI +
    `dispatch/DispatchResult` record + default
    `dispatch/NoopRemediationDispatcher` gated by
    `cortex.remediation.dispatcher.provider=noop`
    (`matchIfMissing=true`).
  - `metrics/RemediationMetrics`
    `cortex.remediation.dispatched_total{channel, outcome,
    tenant_id}` counter, bootstrap-registered at construct time
    with all-`unknown` placeholder tags per LD106 + LD112
    (Part 17 tag-key allowlist).
  - Test surface: 23 tests across `ArchitectureTest`,
    `CortexRemediationApplicationTests` (`@SpringBootTest
    @EmbeddedKafka`), `AnomalyConsumerTest` (Mockito unit, 7
    tests), `AnomalyConsumerKafkaIT` (Testcontainers Kafka
    3.8.0 IT), `NoopRemediationDispatcherTest`,
    `RemediationMetricsTest`, `AnomalyEnvelopeParserTest` (8
    tests covering every branch).
  - ADR-0032 -- `RemediationDispatcher` SPI + per-channel
    adapter contract (Slack/PagerDuty/Jira); 6 rejected
    alternatives documented (reactive SPI, native channel
    SDKs, single shared HTTP adapter, server-side per-channel
    topics, Spring AI tools framework). `docs/adr/INDEX.md`
    row + count bump 31 -> 32 + new "Remediation pipeline
    (P6)" section.
  - `log-remediation-service/README.md` ten-section pattern
    mirroring `log-processor-service/README.md`.
  - `docs/p5-to-p6-handoff.md` section 2 heading typo fixed
    (`binary mode` -> `structured-mode JSON`; body was
    already correct).

- P0: Repository bootstrap.
  - Parent Maven POM (Java 17, Spring Boot 3.3.5, Spring Cloud 2023.0.4,
    Spring AI 1.0.0).
  - Maven wrapper (script-only) pinned to Maven 3.9.9.
  - Universal Javadoc enforcement via Checkstyle (Rule 0.1.6).
  - SpotBugs + FindSecBugs at High threshold.
  - JaCoCo with 80% line + branch gates.
  - OWASP Dependency-Check (CVSS >= 8 fails build).
  - CycloneDX SBOM generation.
  - Maven Enforcer: Java 17, dependency convergence, ban duplicate versions.
  - Renovate config for weekly dependency updates.
  - Conventional Commits enforcement via commitlint.
  - LF line endings via `.gitattributes` and `.editorconfig`.
  - `.github/CODEOWNERS` and PR template.
  - Apache License 2.0.

- P1: Documentation and Architecture Decision Records.
  - `docs/ARCHITECTURE.md` - canonical architecture reference with module
    map, three-tier search routing, API surfaces, and tenant isolation.
  - `docs/PHASES.md` - public phase plan mirroring GitHub milestones.
  - `docs/adr/0000-template.md` - MADR template for future ADRs.
  - ADR-0001: Java 17 LTS runtime (no virtual threads).
  - ADR-0002: Single repo with seven service modules.
  - ADR-0003: Three-tier search (Postgres GIN + Loki + Quickwit) Day 1.
  - ADR-0004: REST + GraphQL parity on four query operations; no
    GraphQL mutations.
  - ADR-0005: RabbitMQ locally, Azure Service Bus in production via
    Spring Cloud Stream binders.
  - ADR-0006: AI provider abstraction (Ollama local, Azure OpenAI prod).
  - ADR-0007: Self-healing via runnable Ansible playbooks with two-step
    dry-run gate and per-tenant kill-switch.
  - ADR-0008: Resilience4j on every egress call (circuit breaker,
    retry, time limiter, rate limiter, fallback).
  - ADR-0009: Tenant isolation (`tenant_id` column + B-tree composite
    index + propagation through MDC, OTel baggage, bus headers).
  - ADR-0010: Storage tiering (hot Loki -> warm Blob -> archive Blob)
    with explicit `X-Allow-Cold-Read` opt-in for archive reads.
  - ADR-0011: Observability stack (OpenTelemetry traces + Micrometer
    metrics + loki4j self-logs).
  - ADR-0012: Build and quality gates (universal Javadoc + JaCoCo 80%
    + OWASP DC + SBOM, all enforced by `./mvnw verify`).

- P2: Shared agent SDK (`log-agent-lib`, PR #20-#27).
  - Lombok-free DTO contracts (`IngestBatch`, `IngestEntry`,
    `PiiMasker`) consumed by every Spring Boot service downstream.
  - Logback JSON appender with MDC propagation (`traceId`,
    `requestId`, `tenantId`, `userId`) per ADR-0011 + Rule 26.10.6.
  - ADR-0013: `log-agent-lib` is Lombok-free.

- P3.0: `log-gateway` scaffold (PR #28-#32).
  - Spring Cloud Gateway MVC (servlet, not WebFlux) per ADR-0014.
  - Eureka discovery client (`lb://` routing) per ADR-0016.
  - Actuator + Prometheus + OpenAPI v3 wired.

- P3.1: Gateway JWT auth chain (PR #33-#37).
  - HMAC-256 JWT resource server with custom converter (ADR-0015).
  - Bearer access tokens + opaque single-use refresh tokens (rule B7.5).
  - RFC 7807 problem responses with `errorCode` enum + `traceId`.
  - 22-test smoke-p3-1.ps1 covers bad-creds 401, blank 400,
    refresh rotation, tampered-token 401, OpenAPI listing, etc.

- P3.2: Bucket4j + Lettuce Redis rate limit (PR #38-#42).
  - ADR-0017 captures: global bucket per principal + anon sub-bucket +
    per-feature sub-buckets via `@RateLimitFeature` annotation.
  - `X-RateLimit-{Limit,Remaining,Retry-After}` headers on every
    rate-limited path (LD47: sub-bucket emits the global headers).
  - Excluded paths (`/api/v1/health`, swagger, prometheus) emit no
    rate-limit headers.

- P3.3: NL->LogQL via Spring AI + WireMock (PR #43-#46).
  - ADR-0018 + ADR-0006: Spring AI 1.0.0 + Ollama (local) /
    Azure OpenAI (prod) / WireMock (smoke). Auto-binder selection
    via `@ConditionalOnProperty` on `cortex.ai.provider`.
  - JSON-strict response contract `{ logql, confidence, explanation }`.
  - 422 errorCodes `NL_QUERY_INVALID` + `NL_QUERY_REFUSED` for schema
    miss / refusal; 502 `NL_QUERY_UPSTREAM_FAILED` for transport.

- P3.4: SCG RouteLocator + `@RateLimitFeature` end-to-end (PR #47-#49).
  - ADR-0021: custom annotation + RouteLocator pattern unifies
    declarative routes with per-feature rate limits.
  - Three live routes: `/api/v1/logs/**` and `/api/v1/search/**`
    (both `lb://log-echo-service`) + `/api/v1/auth/login` (sub-bucket
    5/PT10M to gate brute-force).
  - LD68: `Find-CortexLogErrors` filter knows the 6 Eureka boot-race
    INFO/WARN patterns and does NOT report them as ERROR.

- P4.0: `log-ingest-service` scaffold + RFC 7807 contract (PR #57).
  - `POST /api/v1/ingest/batch` with Bean Validation on the request
    body (`@Valid` + jakarta annotations).
  - `GlobalExceptionHandler` returning RFC 7807 envelopes with
    `errorCode` enum (`VALIDATION_FAILED`, `BAD_REQUEST`, `NOT_FOUND`,
    `INTERNAL_ERROR`, ...).
  - Eureka registration + actuator + prometheus.

- P4.1: Raw-log persistence + cold-path dedupe (PR #48).
  - ADR-0022: `raw_logs` schema with server-computed `event_id`
    (SHA-256 of canonical pre-image), tenant-FK, JSONB `labels`,
    `received_at` server timestamp, `UNIQUE (tenant_id, event_id)`.
  - JSONB roundtrip via `AbstractJdbcConfiguration.userConverters` +
    `JdbcValue.of(PGobject, JDBCType.OTHER)`.
  - Cold-path dedupe: `DbActionExecutionException` -> unique-violation
    unwrap -> 202 absorbed.
  - JaCoCo 0.80 line + branch gate ON for `log-ingest-service`.

- P4.2: Hot-path Redis dedupe + server-side PII masking (PR #51).
  - ADR-0023: Redis SETNX with PT24H TTL keyed
    `cortex:ingest:idem:{tenantId}:{idempotencyKey}`, fail-open on
    `DataAccessException`, `@ConditionalOnProperty` gate.
  - Hash-before-mask sequence so masked output does not change the
    `event_id` (LD63).
  - 3 Micrometer counters: dedupe.hit.hot, dedupe.hit.cold,
    mask.applied.total.

- P4.3: Server-side enrichment (PR #55 + fix(postman) PR #56).
  - ADR-0024: 7 decisions D1-D7. Single correlation id under three
    header aliases (`X-Request-Id` / `X-Correlation-Id` / `traceparent`),
    JWT `tid` wins over `X-Tenant-Id`, label normalization (lowercase
    keys via `Locale.ROOT`, trim values, drop blanks, last-write-wins),
    server-stamped labels excluded from `event_id` pre-image, geo
    enrichment stub (`labels.geo_country`).
  - LD73: 5-leg triangle gate (mvn verify + smoke-pN + regression
    default + regression rate-burst + newman) mandatory BEFORE
    squash-merge.

- P4.4a: Transactional outbox foundation (PR #58).
  - ADR-0025: V3 Flyway migration adds `outbox_events (id,
    tenant_id, event_id, payload_bytes, status, attempts,
    last_error, created_at, last_attempt_at)` with
    `UNIQUE (tenant_id, event_id)`.
  - `RawLogTransactionalWriter` writes `raw_logs` + `outbox_events`
    in the SAME `@Transactional(propagation=REQUIRES_NEW)` boundary
    so a Kafka publish failure can never leak `raw_logs` rows.

- P4.4b: CloudEvents 1.0 on Kafka (PR #59 + PR #60).
  - ADR-0026: SCSt outbound silently dropped sends (LD79); pivoted
    to direct `KafkaTemplate` with explicit producer contract
    (acks=all + enable.idempotence=true + retries=MAX_VALUE +
    max.in.flight=5 + compression=zstd + request.timeout=30s +
    delivery.timeout=120s).
  - CloudEvents 1.0 structured-mode JSON envelope
    (`specversion=1.0`, `type=io.cortex.logs.event.v1`,
    `source=/cortex/log-ingest-service`, `subject=tenant_id`,
    `id=event_id`, `time=ingest_time`,
    `datacontenttype=application/json`).
  - `OutboxPoller` @Scheduled fixedDelay=1s with master-switch
    short-circuit, per-row sync `send().get(10s)`, exponential
    backoff cap 5 min.
  - Detour PR #61: README architecture diagram rewrite to match
    P3+P4 shipped reality (Kafka box, agent-lib promotion,
    Control plane row, `[E]` / `[M]` tags).

- P4.4c: DLQ + Service Bus binder gate + outbox metrics (PR #63).
  - ADR-0027 D1-D6: DLQ topic `cortex.logs.events.v1.dlq` + two
    Kafka headers (`x-orig-topic`, `x-failure-reason`); retry
    exhaustion via doubling-with-cap backoff
    (`OutboxPollerProperties.isRetryExhausted`); 3 counters
    `cortex.ingest.outbox.{published,failed,dlq}_total` tagged by
    topic + tenant_id + reason; Service Bus binder profile gate via
    `@ConditionalOnProperty(name="cortex.outbox.publisher",
    havingValue="kafka", matchIfMissing=true)` with stub
    `ServiceBusOutboxPublisher` deferred to P10.
  - `OutboxEventPublisher` SPI + `KafkaOutboxPublisher` (default) +
    `ServiceBusOutboxPublisher` stub (`UnsupportedOperationException`).
  - `FailureReason` enum allowlist (5 strings) is the Prometheus
    cardinality guard for the `reason` tag.
  - `OutboxStatus.DEAD` + V4 Flyway migration
    (`outbox_events_status_dead.sql`).
  - Tests: 130 unit (+14) + 14 IT (+1 poison-row), JaCoCo LINE 0.93
    BRANCH 0.84.
  - LD79 + LD80 + LD85 + LD86 + LD87 + LD88 captured in `memory.md`.

- P4.5: P4 epic closer (PR #65, `2af00d1`, 2026-06-03).
  - `docs/adr/INDEX.md` -- single-page directory of all 28 ADRs
    (`0000` template + `0001` .. `0027`) grouped by phase.
  - `scripts/p4-5/smoke-p4-5.ps1` -- cross-phase regression wrapper
    around `smoke-all.ps1` enforcing the LD69 union contract (every
    smoke passes in at least one mode AND no smoke FAILs in the mode
    it is compatible with).
  - `.github/PULL_REQUEST_TEMPLATE.md` -- adds Part 26.10 / 26.11 +
    LD73 / LD86 / LD89 checklist items (triangle gate, scripts-first,
    LD89 false-ship antidote).
  - `README.md` -- top-line status flipped to "P0..P4 SHIPPED",
    What's-working bullets cover every shipped sub-phase through
    P4.4c, tech-stack table cites every PR, repo layout points at
    `docs/adr/INDEX.md` + `scripts/p4-{4c,5}/`.
  - `CHANGELOG.md` (this entry) -- gap-fills P2 / P3.x / P4.x and
    P4.5 closer.
  - LD89 (false-ship hallucination antidote): never claim a `gh` /
    `git` operation succeeded without quoting the actual CLI stdout
    in the same turn. Inline `Test-Path` proof for every file-system
    operation.
  - Atomic 4-file flip: P4.4c + P4.5 both -> SHIPPED, P5 -> IN
    PROGRESS. Compound flip in the same edit batch per LD46.

- P5.0: `log-processor-service` scaffold + Kafka consumer + classifier
  SPI + metrics (PR #67, `068a3f8`, 2026-06-03).
  - New Maven module `log-processor-service` with `pom.xml`, Spring Boot
    main, `application.yml` on port `:8095` (LD92 multi-NIC port pick to
    avoid the `:8094` WireMock collision shipped in P3.3).
  - Direct `@KafkaListener` with manual offset commit on
    `cortex.logs.events.v1`, container factory wired with
    `ContainerProperties.AckMode.MANUAL_IMMEDIATE`; ADR-0028 over the
    SCSt + binder approach attempted in P4.4b (LD79 carry-forward).
  - `LogEventClassifier` SPI with a `noop` default implementation
    (every record routes to `outcome=normal`), behind
    `cortex.processor.classifier.provider` so the P5.0 scaffold ships
    without any AI dependency on the hot path.
  - Micrometer counters
    `cortex.processor.events.{consumed,parsed,classified,dlq_replay}_total`
    with the Part 17 allowlist tags `topic`, `tenant_id`, `outcome`
    (allowlist enforced by `MicrometerCardinalityFilter`).
  - Eureka registration + actuator (`health,info,metrics,prometheus,beans`).
  - JaCoCo BUNDLE gate 0.80 line + 0.80 branch ON for this module from
    day one (LD23).

- P5.0a: Closer follow-ups (PR #68, `a8e539c`, 2026-06-03).
  - `log-agent-lib` `CloudEventEnvelope` + builder + JSON
    `(de)serializer` so producer + consumer agree on the envelope shape
    (`specversion=1.0`, `type=io.cortex.logs.event.v1`,
    `source=/cortex/log-ingest-service`, `subject=tenant_id`,
    `id=event_id`, `time=ingest_time`,
    `datacontenttype=application/json`).
  - log-gateway `/api/v1/logs/**` route flipped from the retired
    `lb://log-echo-service` placeholder to `lb://log-ingest-service`
    with a rewrite to `/api/v1/ingest/batch`, so external producers can
    push through the gateway and the resulting CloudEvent flows
    end-to-end through ingest -> Kafka -> processor.
  - `log-processor-service` port migration from the originally proposed
    `:8094` to `:8095` (LD92) because WireMock owns `:8094` in every
    smoke profile shipped from P3.3 forward.

- multi-NIC eureka loopback fix (PR #71, `f8146a9`, 2026-06-03).
  - `eureka.instance.preferIpAddress=true` +
    `eureka.instance.ipAddress=127.0.0.1` in the `dev` profile of every
    Spring Boot service so multi-NIC dev hosts (corporate VPN +
    Hyper-V switch + Docker bridge) register the loopback IP instead
    of an unreachable virtual adapter and `lb://` resolution stops
    racing.
  - LD93 added: dev profile must pin Eureka instance to loopback on
    multi-NIC dev hosts; otherwise `lb://log-processor-service`
    sporadically resolves to a stale virtual NIC address that no
    smoke can reach.

- P5.1: Parser + JSON-schema validator + DLQ publisher (PR #70,
  `65e2ab8`, 2026-06-03).
  - `CloudEventEnvelopeParser` walks the structured-mode JSON envelope
    from `cortex.logs.events.v1` and unmarshals the `data` payload to
    the canonical `LogEvent` POJO; rejects unknown `type`, unknown
    `specversion`, or missing required envelope fields.
  - `LogEventSchemaValidator` enforces required fields
    (`tenant_id`, `event_id`, `timestamp`, `level`, `message`) +
    `level` enum allowlist + `timestamp` ISO-8601 parsing; failures
    bubble as `LogEventSchemaException` with a
    `FailureReason` enum value used as the Prometheus `reason` tag
    (carry-forward of ADR-0027's cardinality guard).
  - `DlqPublisher` writes rejected envelopes to
    `cortex.logs.events.v1.dlq` with `x-orig-topic` +
    `x-failure-reason` headers (mirror of ingest-side ADR-0027
    contract) and increments
    `cortex.processor.events.dlq_replay_total{reason}`.
  - The consumer commits the offset AFTER the DLQ publish succeeds so a
    DLQ publish failure becomes a Kafka redelivery (LD94 ordering
    invariant); successful parses bump
    `cortex.processor.events.parsed_total` and successful classifies
    bump `cortex.processor.events.classified_total{outcome=...}`.
  - Tests: parser IT + schema validator IT + DLQ publisher IT using
    Kafka 3.8.0 KRaft single-node Testcontainer (`cortex-smoke-kafka`);
    JaCoCo BUNDLE LINE >= 0.80 BRANCH >= 0.80 holds.

- P5.2: Spring AI 1.0 GA anomaly classifier (PR #73, `e92efaf`,
  2026-06-03).
  - `SpringAiAnomalyClassifier` implements `LogEventClassifier` and
    calls `ChatClient.call(...)` against the
    `spring-ai-starter-model-ollama` binder; the prompt template is
    rendered via literal `.replace("{tenant_id}", ...)` (LD42) instead
    of ST4 so curly-brace tokens inside the user log message cannot
    crash the renderer.
  - ADR-0029 captures the provider matrix: Ollama in dev,
    Azure OpenAI in prod, WireMock-stubbed Ollama on `:8094` in smoke;
    selection is via `cortex.processor.classifier.provider` +
    `spring.ai.ollama.base-url` so the same code path is exercised in
    every environment.
  - LD42 carry-forward: `OllamaApi` is pinned to HTTP/1.1 via
    `JdkClientHttpRequestFactory` so the OkHttp default does not
    silently downgrade HTTP/2 stream resets to retried 5xx responses.
  - Classifier outcome tagged on
    `cortex.processor.events.classified_total{outcome=anomaly|normal|error|skipped}`:
    classifier errors fall back to `outcome=error` (the event is still
    parsed + committed, just not classified), the AI call timeout maps
    to `outcome=skipped`.
  - 5-leg gate (LD73) green: `mvn verify` + `smoke-p5-2.ps1` +
    `smoke-all.ps1 -Mode default` + `smoke-all.ps1 -Mode rate-burst`
    + Newman log-gateway collection. LD100 captured: the post-merge
    atomic 4-file flip MUST cite the actual squash-merge SHA per LD89
    not the pre-merge feature-branch SHA.

- P5.2a: Postman + docs gap-fill closer (this PR, `feat/p5-2a-postman-docs-closer`).
  - `postman/log-processor.postman_collection.json` (NEW): Auth ->
    Admin (actuator: health/liveness/readiness/info/metrics/prometheus)
    -> Metrics-Baseline -> Pipeline (publish via gateway logs route)
    -> Metrics-After (assert `cortex.processor.events.consumed_total`
    and `classified_total{outcome=anomaly}` both incremented) -> Error
    Scenarios. 13 requests / 30+ assertions, runs with `--bail`.
  - `postman/log-ingest.postman_collection.json` (BUMP): name flipped
    from "P4.3 enrich" to "P4.4c outbox + P5.0a logs route"; +2 new
    items asserting `cortex_ingest_outbox_{published,failed,dlq}_total`
    counter families are registered on `/actuator/prometheus`, +1 item
    asserting `cortex_ingest_outbox_published_total` strictly increases
    after a `/api/v1/ingest/batch` round-trip with a 4-second settle
    window for the `@Scheduled(fixedDelay=1s)` outbox poller. Total
    is now 18 top-level items.
  - `postman/log-processor.postman_environment_{local,staging,prod}.json`
    (NEW) + `postman/log-ingest.postman_environment_{staging,prod}.json`
    (NEW): closes the 7.6 / 25.2 / 26.6.1 staging+prod env gap.
  - `log-processor-service/README.md` (NEW): Part 4 D1 ten-section
    format (overview, architecture, tech stack, design decisions,
    SOLID + Clean Code notes, logging, run locally, docker, API docs,
    future improvements).
  - `README.md` (PATCH): SHA `21b25b9` -> `2af00d1` (LD90 false-SHA
    fix for PR #65); phase status bumped from "P0..P4 SHIPPED" to
    "P0..P5 SHIPPED" with the five new PR rows; new "log-processor"
    bullet under "What's working today on main"; project layout note
    flipped to "P5.0..P5.2 SHIPPED; P5.3 next"; scripts/ layout adds
    `p5-{0,1,2,2a}`.
  - `CHANGELOG.md` (GAP-FILL): retroactive entries for P5.0, P5.0a,
    multi-NIC, P5.1, P5.2, P5.2a with real SHAs verified via
    `git log --grep '#NN'` per LD89.
  - `scripts/p5-2a/` (NEW): five-script bundle per Part 26.10.8.3
    (`boot-full-stack.ps1`, `smoke-p5-2a.ps1`, `newman-leg-c.ps1`,
    `teardown-full-stack.ps1`, `README.md`). The full stack runs
    Eureka + gateway + ingest + echo + processor with the
    spring-ai classifier and WireMock-stubbed Ollama so the Pipeline
    folder in the Newman collection observes a deterministic anomaly
    verdict.
  - LD101 (NEW): Newman is Leg C of the LD73 5-leg gate. A smoke
    script substituted for Newman does not satisfy the gate; the
    Newman run against the per-phase collection must execute as its
    own step with `--bail` and EXIT 0.
  - LD102 (NEW): autopilot mode bans "shall I ship X?" pauses
    after a plan-first gate has been cleared. Standing approval
    covers every step that is already mandated by
    `agent-strict-rules-prompts.md`.
  - Part 26.11.8 throughput stamp on the previous shipped block
    (P5.2): "~140 tool calls / 1 subagent / 5 scripts / 19 files
    +1558/-51 / LD99+LD100".

- P5.3: `ParsedEventSink` fan-out to Loki + Quickwit
  (this PR, `feat/76-p5-3-loki-quickwit-sinks`).
  - `log-processor-service/src/main/java/io/cortex/processor/sink/`
    (NEW package, 5 production files):
    - `ParsedEventSink` -- SPI:
      `void send(RawLogEvent, Classification)` + `String name()`.
      Contract: implementations MUST NOT throw; they MUST tick
      `SinkMetrics.*Failed(...)` on every failure category and
      return.
    - `SinkProperties` --
      `@ConfigurationProperties("cortex.processor.sinks")` record
      with nested `Loki(enabled, baseUrl, requestTimeout)` +
      `Quickwit(enabled, baseUrl, index, requestTimeout)` records.
      Defensive defaults on canonical ctors so blank yml entries
      do not NPE. Both sinks default to `enabled=false`.
    - `SinkMetrics` -- `@Component("cortexSinkMetrics")`. Two
      counter families per sink:
      `cortex.processor.sink.{loki|quickwit}.published_total{tenant_id}`
      and
      `cortex.processor.sink.{loki|quickwit}.failed_total{tenant_id, reason}`
      where `reason` is the bounded enum
      `{HTTP_STATUS, TIMEOUT, TRANSPORT, SERIALIZATION, UNKNOWN}`.
      Counters lazy-registered through a `ConcurrentMap` keyed by
      `metric|tenant|reason` per LD106.
      `@EnableConfigurationProperties(SinkProperties.class)` is
      placed on `SinkMetrics` (always loaded) so the properties
      bind even when both sinks are disabled.
    - `LokiSink` -- `@Component @ConditionalOnProperty("cortex.processor.sinks.loki.enabled" = true)`.
      Posts `{streams:[{stream:{tenant_id,level,anomaly}, values:[[tsNanos,line]]}]}`
      to `{base-url}/loki/api/v1/push`. HTTP/1.1-pinned `RestClient`
      via `JdkClientHttpRequestFactory` per LD42.
    - `QuickwitSink` -- `@Component @ConditionalOnProperty("cortex.processor.sinks.quickwit.enabled" = true)`.
      Posts an NDJSON doc per event to
      `{base-url}/api/v1/{index}/ingest`. `id = event.eventId()` for
      server-side dedupe on Kafka rebalance redelivery (ADR-0030 D6).
  - `log-processor-service/src/main/java/io/cortex/processor/consume/LogEventConsumer.java`
    (MOD): ctor gains `List<ParsedEventSink> sinks` (null-safe to
    empty list); new private `fanOut(...)` method iterates the list
    inside a `try { ... } catch (RuntimeException)` so a defective
    sink can never bubble up and rewind the Kafka offset. Called
    inline AFTER the `events.classified_total{outcome}` tick and
    BEFORE `ack.acknowledge()` on the success branch.
  - `log-processor-service/src/test/java/io/cortex/processor/sink/`
    (NEW, 4 unit tests):
    - `SinkPropertiesTest` -- defensive default coverage.
    - `SinkMetricsTest` -- lazy registration + per-(tenant,reason)
      series isolation + null reason -> UNKNOWN + blank tenant ->
      unknown.
    - `LokiSinkTest` -- in-process JDK `HttpServer` stand-in;
      asserts happy-path body shape, anomaly suffix, http_status,
      transport, null event no-op, null tenant coercion,
      `name()=="loki"`.
    - `QuickwitSinkTest` -- in-process JDK `HttpServer` stand-in;
      asserts NDJSON shape, `id=eventId` dedupe key, http_status,
      transport, anomaly fields, `name()=="quickwit"`.
  - `log-processor-service/src/test/java/io/cortex/processor/consume/LogEventConsumerTest.java`
    (MOD): two `Mockito.mock(ParsedEventSink.class)` instances
    injected; existing test cases extended with sink-call
    assertions; +3 new tests:
    `sinkExceptionDoesNotBlockAckOrFanoutToPeer`,
    `emptySinkListIsTolerated`, `nullSinkListIsTolerated`.
  - `log-processor-service/src/main/resources/application.yml`
    (MOD) + `log-processor-service/src/test/resources/application.yml`
    (MOD per LD100): new `cortex.processor.sinks.{loki,quickwit}.*`
    block with env-var overrides + `enabled=false` defaults.
  - `infra/local/wiremock/mappings/loki-push.json` (NEW): WireMock
    stub `POST /loki/api/v1/push -> 204`.
  - `infra/local/wiremock/mappings/quickwit-ingest.json` (NEW):
    WireMock stub `POST /api/v1/{index}/ingest -> 200`.
  - `scripts/p5-3/` (NEW per Part 26.10.8.3, gitignored per LD86):
    `boot-full-stack.ps1` (mirrors P5.2a but flips
    `CORTEX_PROCESSOR_SINKS_{LOKI,QUICKWIT}_ENABLED=true` and points
    both base-urls at WireMock :8094), `smoke-p5-3.ps1` (extends
    P5.2a smoke with sink published_total counter delta + WireMock
    journal assertions on the two sink endpoints), `newman-leg-c.ps1`,
    `teardown-full-stack.ps1`, `README.md`.
  - `docs/adr/0030-loki-quickwit-fanout-sinks.md` (NEW): D1..D6
    plus five rejected alternatives (Kafka Connect, Vector forwarder,
    two-binder approach, synchronous blocking sinks, dedicated ML
    service is rejected via ADR-0006 cross-ref).
  - `docs/adr/INDEX.md` (BUMP): 29 -> 30; new row under "Processor
    pipeline (P5)".
  - `log-processor-service/README.md` (PATCH): banner block now
    mentions P5.3; ADR pointers section adds ADR-0030; Run locally
    section points at `scripts\p5-3\boot-full-stack.ps1`; Future
    improvements section P5.3 bullet is replaced with a P5.4 outbox
    bullet (since P5.3 is now shipped).

- P5.4: Synchronous `cortex.anomalies.v1` CloudEvents publisher
  for the future P6 `log-remediation-service` handoff
  (this PR, `feat/80-p5-4-anomalies-publisher`).
  - `log-processor-service/src/main/java/io/cortex/processor/consume/AnomaliesPublisher.java`
    (NEW): mirrors the `DlqPublisher` shape exactly. Public
    `@Autowired` Spring ctor + package-private test-seam ctor
    taking an explicit `Clock`. Reuses the existing
    `KafkaTemplate<byte[], byte[]>` bean from
    `ProcessorKafkaProducerConfig` (no new producer factory; the
    P5.1 byte[]/byte[] producer with `acks=all` +
    `enable.idempotence=true` already satisfies the contract).
    `publish(RawLogEvent, Classification)` builds a CloudEvents
    1.0 structured-mode JSON envelope (`id=eventId`,
    `source=/cortex/log-processor-service`,
    `type=io.cortex.anomaly.v1`, `subject=tenantId`,
    `datacontenttype=application/json`, `data` map of
    `{eventId, tenantId, severity, reason, ts, level, service,
    message}` in deterministic field order). Sends synchronously
    with `kafkaTemplate.send(record).get(10, SECONDS)`; on
    interrupt / NACK / timeout throws `IllegalStateException` so
    the consumer's catch leaves the source record un-acked and
    Kafka rebalance redelivery re-attempts the publish on the next
    poll. Two Kafka headers per ADR-0031 D4:
    `content-type=application/cloudevents+json` +
    `x-source-topic=${cortex.processor.topic}`. Record key =
    `eventId` bytes for downstream dedupe.
  - `log-processor-service/src/main/java/io/cortex/processor/consume/LogEventConsumer.java`
    (MOD): ctor gains a 7th argument `AnomaliesPublisher` placed
    between `dlqPublisher` and `sinks`
    (`@SuppressWarnings("checkstyle:ParameterNumber")` because the
    six cooperating collaborators of the Kafka consumer pipeline
    are the intended design). Anomaly branch becomes
    `metrics.incAnomaliesPublished(...) ->
    anomaliesPublisher.publish(...) -> fanOut(...) ->
    ack.acknowledge()`. A publish failure surfaces as
    `IllegalStateException`, is logged at ERROR with
    `eventId+tenantId`, and the method returns early WITHOUT
    calling `ack.acknowledge()` so Kafka redelivery retries the
    publish.
  - `log-processor-service/src/main/java/io/cortex/processor/metrics/ProcessorMetrics.java`
    (MOD): new counter
    `cortex.processor.anomalies.published_total{topic, tenant_id}`
    bootstrap-registered at construct-time with
    `topic=cortex.anomalies.v1` + `tenant_id=unknown` per LD106 +
    LD112 so the counter family is visible on the very first
    Prometheus scrape (Postman / smoke baseline + delta is
    unconditional; no family-presence gate required).
  - `log-processor-service/src/main/java/io/cortex/processor/config/ProcessorKafkaProducerConfig.java`
    (MOD): Javadoc bumped to reflect the second consumer of the
    same `KafkaTemplate` bean; bean wiring unchanged.
  - `log-processor-service/src/main/resources/application.yml`
    (MOD) + `log-processor-service/src/test/resources/application.yml`
    (MOD per LD100): new
    `cortex.processor.anomalies.topic: ${CORTEX_PROCESSOR_ANOMALIES_TOPIC:cortex.anomalies.v1}`.
  - `log-processor-service/src/test/java/io/cortex/processor/consume/AnomaliesPublisherTest.java`
    (NEW): builds the envelope with an in-memory `ObjectMapper`
    + a fixed `Clock`; asserts the payload bytes round-trip back
    through `JsonFormat.deserialize` to a CloudEvent with the
    documented attributes + the same `data` JSON object.
  - `log-processor-service/src/test/java/io/cortex/processor/consume/LogEventConsumerTest.java`
    (MOD): every existing test extended with a
    `Mockito.mock(AnomaliesPublisher.class)`; verifies the anomaly
    branch invokes `publish(...)` exactly once and the
    non-anomaly branches NEVER invoke it. +1 new test
    `anomalyPublishFailureLeavesRecordUnacked` proving that an
    `IllegalStateException` from the publisher prevents
    `ack.acknowledge()` from being called.
  - `log-processor-service/src/test/java/io/cortex/processor/consume/LogEventConsumerKafkaIT.java`
    (MOD): new `@Order(4) errorEnvelopeIsClassifiedAndPublishedToAnomaliesTopic()`
    + new `@TestConfiguration StubAnomalyClassifierConfig` that
    forces `level=ERROR` -> verdict `severity=HIGH`. The IT
    pre-creates `cortex.anomalies.v1` on the Testcontainers
    broker, publishes an ERROR-level envelope to the source topic,
    drains the anomalies topic with a dedicated consumer, and
    asserts the envelope shape + the two headers + the record key
    + the `data` field values + the
    `cortex.processor.anomalies.published_total` counter delta.
  - `docs/adr/0031-log-processor-anomalies-publisher.md` (NEW):
    D1..D6 plus five rejected alternatives (Postgres outbox
    table + poller, async fire-and-forget, direct StreamBridge,
    reuse the P5.3 `ParsedEventSink` SPI, schema registry
    binding).
  - `docs/adr/INDEX.md` (BUMP): 30 -> 31; new row under
    "Processor pipeline (P5)".
  - `log-processor-service/README.md` (PATCH): banner block now
    mentions P5.4; ADR pointers section adds ADR-0031 +
    cross-references LD117 (no-outbox rule for Kafka -> Kafka
    relay services); tech-stack row for the new publisher; Run
    locally section points at `scripts\p5-4\boot-full-stack.ps1`;
    Future improvements section drops the P5.4 outbox bullet and
    adds a P5.5 epic-closer bullet.
  - `scripts/p5-4/` (NEW per Part 26.10.8.3, gitignored per
    LD86): `boot-full-stack.ps1` (mirrors P5.3 + adds an
    `anomalies-drain.ps1` helper that consumes the new topic for
    smoke validation), `smoke-p5-4.ps1` (extends P5.3 smoke with
    `cortex_processor_anomalies_published_total` counter delta +
    a one-shot Kafka topic read on `cortex.anomalies.v1` that
    asserts a CloudEvent envelope with the documented two
    headers + record key + `data` fields landed),
    `newman-leg-c.ps1`, `teardown-full-stack.ps1`, `README.md`.
  - LD117 (NEW): for Kafka consumer -> Kafka producer relay
    services, the Kafka offset itself IS the durability mechanism
    -- no outbox table is needed unless the source of the verdict
    is non-Kafka. Synchronous publish on the consumer thread with
    `KafkaTemplate.send().get(timeout)` + on failure throw
    `IllegalStateException` + don't `ack.acknowledge()` lets
    Kafka rebalance redelivery retry the publish. P4.4 needed an
    outbox because HTTP ingest returns 202 Accepted to the client
    BEFORE durable persistence; P5.4 does not have that
    asymmetry.

- P5.5: Close the P5 epic (this PR, `chore/82-p5-5-close-epic`,
  closes #6 + #66 + #69 + #72 + #76 + #79 + #82).
  Docs-only closer; zero production code changes; Leg A
  `mvn verify` is the only safety check.
  - `log-processor-service/README.md` (PATCH): banner block
    flipped to `Status: P0..P5 SHIPPED` with the full P5.0..P5.5
    PR + merge SHA chain (P5.0 #67/`068a3f8`, P5.0a #68/`a8e539c`,
    P5.1 #70/`65e2ab8`, P5.2 #73/`e92efaf`, P5.2a #75/`43a94e9`,
    P5.3 #77/`6e2f51c`, P5.3a #78/`5579186`, P5.4 #81/`d2e6acc`).
    Future improvements section drops the P5.5 bullet (now done)
    and adds an explicit pointer to `docs/p5-to-p6-handoff.md`
    from the P6 bullet.
  - `docs/adr/INDEX.md` (PATCH): `Last refreshed:` bumped from
    "2026-06-04 (P5.4, PR for #80)" to "2026-06-04
    (P5.5 close-epic, PR for #82)". Total ADR count unchanged
    (31; this is a docs-only closer).
  - `docs/p5-to-p6-handoff.md` (NEW): single-page contract that
    pins everything the future P6 `log-remediation-service`
    consumer needs to subscribe to `cortex.anomalies.v1` without
    reading the P5 producer source. Covers Kafka coordinates
    (topic name, source topic, producer mode, partitions, key,
    mandatory headers), the CloudEvents 1.0 envelope shape (per
    ADR-0031), the `data` payload schema, six concrete consumer
    guidance items (idempotency, deserialization, schema
    enforcement, bootstrap counter, header forwarding, DLQ), and
    cross-references to ADR-0027 / 0029 / 0030 / 0031 + LD117 +
    PR #81. No new ADR -- the contract content already lives in
    ADR-0031; this is a navigation aid for the P6 author.

