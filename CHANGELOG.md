# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

- P4.5: P4 epic closer (PR #65, `21b25b9`, 2026-06-03).
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
