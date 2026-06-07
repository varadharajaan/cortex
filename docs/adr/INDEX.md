# Architecture Decision Records -- INDEX

> Single-page directory of every accepted ADR in this repository.
> Format: MADR (Markdown Any Decision Record). Add new ADRs as
> `docs/adr/NNNN-short-slug.md` and append a row to the table below
> in the same PR. Status legend: **Accepted** (in force) /
> **Superseded** (replaced -- see `Supersedes` column).

Last refreshed: 2026-06-07 (P7.4 log-indexer-service tenant-scoped Quickwit search proxy via `LogSearchClient` SPI, PR for #107).
Total ADRs: 42 (`0000` template + `0001` .. `0027` + `0029` + `0030` + `0031` + `0032` + `0033` + `0034` + `0035` + `0036` + `0037` + `0038` + `0039` + `0040` + `0041` + `0042`).

---

## Foundations (P0 .. P1)

| ADR | Title | Status | Scope | Supersedes / Superseded by |
| --- | --- | --- | --- | --- |
| [0000](0000-template.md) | MADR template | Template | All future ADRs | -- |
| [0001](0001-language-and-runtime.md) | Java 17 LTS (no virtual threads) | Accepted | Whole project | -- |
| [0002](0002-monorepo-modules.md) | Single repo, 7 service modules + shared lib | Accepted | Repo layout | -- |
| [0003](0003-three-tier-search.md) | Postgres GIN + Loki labels + Quickwit full-text | Accepted | Search architecture | -- |
| [0004](0004-rest-and-graphql.md) | REST + GraphQL parity on 4 queries, no GraphQL mutations | Accepted | API surface | -- |
| [0005](0005-message-bus.md) | RabbitMQ local, Azure Service Bus prod via Spring Cloud Stream binders | Accepted | Messaging | partially superseded by 0026 + 0027 for ingest -> Kafka direct |
| [0006](0006-ai-provider-abstraction.md) | Ollama local, Azure OpenAI prod via Spring AI | Accepted | AI provider | -- |
| [0007](0007-self-healing-playbooks.md) | Declarative YAML playbooks for remediation | Accepted | log-remediation-service | -- |
| [0008](0008-resilience-strategy.md) | Resilience4j + circuit breakers + retry/timeout policy | Accepted | All services | -- |
| [0009](0009-tenant-isolation.md) | Row-level + token-level multi-tenancy (`X-Tenant-Id`) | Accepted | Whole project | -- |
| [0010](0010-storage-tiering.md) | Hot Postgres -> warm Loki -> cold MinIO/Blob -> Quickwit cold reads | Accepted | Storage | -- |
| [0011](0011-observability.md) | OpenTelemetry + Micrometer + Prometheus exposition | Accepted | Whole project | -- |
| [0012](0012-build-and-quality-gates.md) | Checkstyle + SpotBugs + JaCoCo 80% + OWASP CVSS>=8 fail | Accepted | Build pipeline | -- |

## Shared agent SDK (P2)

| ADR | Title | Status | Scope | Supersedes / Superseded by |
| --- | --- | --- | --- | --- |
| [0013](0013-log-agent-lib-is-lombok-free.md) | `log-agent-lib` is Lombok-free | Accepted | log-agent-lib | -- |

## Gateway (P3)

| ADR | Title | Status | Scope | Supersedes / Superseded by |
| --- | --- | --- | --- | --- |
| [0014](0014-log-gateway-uses-spring-cloud-gateway-mvc.md) | Use Spring Cloud Gateway MVC (servlet) not WebFlux | Accepted | log-gateway | -- |
| [0015](0015-log-gateway-jwt-resource-server.md) | JWT resource server with custom converter | Accepted | log-gateway | -- |
| [0016](0016-cortex-uses-eureka-for-local-discovery.md) | Eureka :8761 for local discovery; Azure Service Discovery in prod | Accepted | All services | -- |
| [0017](0017-cortex-rate-limit-bucket4j-lettuce-redis.md) | Bucket4j + Lettuce Redis for rate limiting | Accepted | log-gateway | -- |
| [0018](0018-cortex-nl-to-logql-spring-ai-ollama-wiremock.md) | NL->LogQL via Spring AI + Ollama (local) / WireMock (smoke) | Accepted | log-gateway | -- |
| [0019](0019-cortex-transport-protocols-grpc-http2-http3.md) | gRPC + HTTP/2 + HTTP/3 transport stance | Accepted | All services | -- |
| [0020](0020-cortex-zero-trust-mtls-service-identity.md) | Zero-trust mTLS service identity (prod) | Accepted | All services | -- |
| [0021](0021-custom-annotation-pattern-rate-limit-feature.md) | Custom `@RateLimitFeature` annotation + `RouteLocator` pattern | Accepted | log-gateway | -- |

## Ingest pipeline (P4)

| ADR | Title | Status | Scope | Supersedes / Superseded by |
| --- | --- | --- | --- | --- |
| [0022](0022-persistence-in-log-ingest-service.md) | Persistence in log-ingest-service (raw_logs + Flyway) | Accepted | log-ingest-service | -- |
| [0023](0023-log-ingest-dedupe-redis-and-pii-masking.md) | Redis hot-path dedupe + PII masking before hash | Accepted | log-ingest-service | -- |
| [0024](0024-log-ingest-server-side-enrichment.md) | Server-side enrichment (X-Tenant-Id, X-Request-Id, JWT `tid`) | Accepted | log-ingest-service | -- |
| [0025](0025-log-ingest-outbox-pattern.md) | Transactional outbox pattern (`outbox_events` + poller) | Accepted | log-ingest-service | partially superseded by 0026 (SCSt -> direct KafkaTemplate) |
| [0026](0026-log-ingest-scst-kafka-cloudevents.md) | Direct `KafkaTemplate<byte[],byte[]>` + CloudEvents 1.0 envelope (NOT SCSt outbound) | Accepted | log-ingest-service | supersedes the SCSt outbound part of 0005 + 0025 |
| [0027](0027-log-ingest-dlq-counters-binder.md) | DLQ topic + Service Bus binder stub + `cortex.ingest.outbox.{published,failed,dlq}` counters | Accepted | log-ingest-service | -- |

---

## Processor pipeline (P5)

| ADR | Title | Status | Scope | Supersedes / Superseded by |
| --- | --- | --- | --- | --- |
| [0029](0029-log-processor-spring-ai-anomaly-classifier.md) | Spring AI 1.0 anomaly classifier (Ollama dev / Azure OpenAI prod) | Accepted | log-processor-service | -- |
| [0030](0030-loki-quickwit-fanout-sinks.md) | `ParsedEventSink` fan-out to Grafana Loki + Quickwit (HTTP/1.1, per-sink feature gates) | Accepted | log-processor-service | -- |
| [0031](0031-log-processor-anomalies-publisher.md) | Synchronous `cortex.anomalies.v1` CloudEvents publisher (no outbox; Kafka offset is the durability mechanism per LD117) | Accepted | log-processor-service | -- |

---

## Remediation pipeline (P6)

| ADR | Title | Status | Scope | Supersedes / Superseded by |
| --- | --- | --- | --- | --- |
| [0032](0032-log-remediation-dispatcher.md) | `RemediationDispatcher` SPI + per-channel adapter contract (one bean per profile via `@ConditionalOnProperty`; default `NoopRemediationDispatcher`) | Accepted | log-remediation-service | -- |
| [0033](0033-slack-remediation-adapter.md) | Slack `RemediationDispatcher` adapter -- Incoming Webhook + plain-text body + `RestClient` HTTP/1.1 pin + typed outcome classification (no in-adapter retry; defers to P6.4 retry-budget) | Accepted | log-remediation-service | -- |
| [0034](0034-pagerduty-remediation-adapter.md) | PagerDuty `RemediationDispatcher` adapter -- Events API v2 enqueue + trigger-only `event_action` + deterministic `{tenantId}:{eventId}` dedup-key + `RestClient` HTTP/1.1 pin (LD42 + LD121 dual-timeout) + typed outcome classification + severity-mapping fallback (no in-adapter retry; defers to P6.4 retry-budget) | Accepted | log-remediation-service | -- |
| [0035](0035-jira-remediation-adapter.md) | Jira `RemediationDispatcher` adapter -- REST API v3 create-issue + Basic-auth-with-API-token + ADF description + label-based severity (`anomaly-severity-<lower>`) + create-issue-only + `RestClient` HTTP/1.1 pin (LD42 + LD121 dual-timeout) + typed outcome classification + four-field blank-credential tolerance (no in-adapter retry; defers to P6.4 retry-budget) | Accepted | log-remediation-service | -- |
| [0036](0036-log-remediation-strict-rules-cleanup.md) | log-remediation-service P6.0a strict-rules cleanup -- `@Validated` on the three `*Properties` records + composition-based `RestDispatchTemplate` helper (shared try/catch + 429/5xx/4xx/timeout classification) consumed by every REST adapter via `template.dispatch(event, ::isConfigured, ::executePost)` + OCP-flipped `RemediationMetrics` (injects `List<RemediationDispatcher>`, loops over `channelId()` to bootstrap outcome series) + Lombok `@RequiredArgsConstructor` + `@Slf4j` adoption + `io.cortex.remediation.constants` package (`RemediationHttp.TOO_MANY_REQUESTS`) + LD5 universal-Javadoc enforcer SUPERSEDED by A2.3 (private-method Javadoc forbidden; `checkstyle.xml` scope flipped private -> public) | Accepted | log-remediation-service | supersedes the decision portion of memory.md LD5 |
| [0037](0037-log-remediation-cross-phase-closer.md) | log-remediation-service P6.1a cross-phase closer -- singleton Testcontainers Kafka + in-process WireMock base + 3 `@SpringBootTest` subclasses (`Slack/PagerDuty/Jira CrossPhaseIT`) each on its own per-channel topic `cortex.anomalies.v1.cross-phase.<channel>` + neutral LD123 test credentials + full-stack PowerShell boot smoke `scripts/smoke-p6-1a.ps1` (per-channel topic isolation per LD125, docker cp + sh -c publish, `CORTEX_REMEDIATION_DISPATCHER_PROVIDER` env, InvariantCulture ISO 8601 timestamps, order-independent Prom counter scrape) + Postman collection `postman/log-remediation.postman_collection.json` (4 folders / 10 requests / 25 assertions, mirrors smoke 1:1, `pm.execution.skipRequest()` gate on `wiremock_base_url`) + ADR-0037 + INDEX bump 36 -> 37 + CHANGELOG + README banner flip; captures LD125 (per-channel kafka topic isolation) + LD126 (Javadoc `*/` terminator trap -- JDT does not catch, only javac does) | Accepted | log-remediation-service | -- |

---

## Indexer pipeline (P7)

| ADR | Title | Status | Scope | Supersedes / Superseded by |
| --- | --- | --- | --- | --- |
| [0038](0038-log-indexer-service-quickwit-admin.md) | log-indexer-service P7.0 scaffold + `QuickwitIndexAdmin` SPI + per-backend admin contract -- single backend bean per profile selected by `cortex.indexer.admin.backend` + `@ConditionalOnProperty`; default `NoopQuickwitIndexAdmin` gated `matchIfMissing=true` so the scaffold boots green with no Quickwit dependency; bootstrap-registered `cortex.indexer.index_admin_total{backend, outcome, tenant_id}` counter family via OCP-flipped `IndexerMetrics` loop over `List<QuickwitIndexAdmin>`; `QuickwitHealthIndicator` bound to `/actuator/health/quickwit`; ArchUnit App/Admin/Metrics/Health layered contract; carves the ownership boundary against `log-processor-service` P5.3 + ADR-0030 (writer-side `QuickwitSink` stays in P5.3; this service owns admin / lifecycle / future search proxy) | Accepted | log-indexer-service | -- |
| [0039](0039-quickwit-http-admin-client.md) | log-indexer-service P7.1 real `QuickwitHttpAdmin` HTTP client -- first real impl behind the P7.0 SPI gated `cortex.indexer.admin.backend=quickwit`; mutually exclusive with the noop default via `@ConditionalOnProperty`; `ensureIndex` is GET-then-POST (D4 -- avoids parsing Quickwit's unstable `IndexAlreadyExists` 400 body); `dropIndex` is DELETE-and-classify-404-as-success per the ADR-0038 D5 SPI idempotence contract; composition-based `RestAdminTemplate` mirrors the P6.0a `RestDispatchTemplate` outcome classification (429/5xx/4xx/timeout/transport/unknown buckets -- D3); HTTP/1.1 pin (LD42) + dual connect+read timeout (LD121) via `JdkClientHttpRequestFactory`; static Quickwit `IndexConfig` v0.7 body mirrors the P5.3 `QuickwitSink.renderDoc` field set (D6); zero SPI edits required -- IndexerMetrics OCP bootstrap loop picks up the new backend's `backendId()=quickwit` automatically; WireMock 3.9.2 IT covers full outcome table for both `ensureIndex` + `dropIndex` including LD120 `Fault.CONNECTION_RESET_BY_PEER` transport-fault injection | Accepted | log-indexer-service | -- |
| [0040](0040-quickwit-retention-admin.md) | log-indexer-service P7.2 `applyRetention` via Quickwit Delete API -- third SPI method on `QuickwitIndexAdmin` (P7.0 / ADR-0038); new `RetentionPolicy(Duration ttl)` immutable value type with compact-ctor null/zero/negative rejection (D2); new `IndexAdminResult.OUTCOME_RETENTION_APPLIED = "retention_applied"` constant + `retentionApplied(backend)` factory; `QuickwitHttpAdmin.applyRetention(spec, policy)` POSTs `{"query":"*","end_timestamp":<epoch_seconds>}` to `/api/v1/{indexId}/delete-tasks` with cutoff `clock.instant().minus(ttl).getEpochSecond()` (D4 + D5 -- dual-ctor `Clock` test-seam mirroring P5.4 `AnomaliesPublisher`); 2xx -> `retention_applied`; 4xx (incl. 404) -> permanent `quickwit:4xx:<n>` (D4 -- 404 == config error, NOT idempotent like `dropIndex`); 5xx/429/timeout/transport via the existing `RestAdminTemplate` helpers (D6 -- zero new classification rules); `IndexerMetrics.bootstrapMeters()` loop gains the new outcome (D7 -- Part 17 allowlist holds; one new outcome value, zero new tag keys) | Accepted | log-indexer-service | -- |
| [0041](0041-quickwit-cardinality-budgets.md) | log-indexer-service P7.3 per-tenant cardinality budgets via `ensureIndex(spec, budget)` -- new `CardinalityBudget(int maxIndexes)` value type with compact-ctor positive-int rejection (D2); new SPI overload `QuickwitIndexAdmin.ensureIndex(IndexSpec, CardinalityBudget)` (D1 -- 1-arg overload unchanged for backwards compatibility); `QuickwitHttpAdmin` implementation runs the existing `checkExists` probe first (D5 -- idempotent re-check of existing indexes skips the gate), then on 404 fetches `GET /api/v1/indexes` and counts entries whose `index_config.index_id` starts with `cortex-<tenantId>-` (D3 -- client-side prefix filter; Quickwit 0.7 has no server-side filter endpoint); count >= ceiling -> `permanent_failure / quickwit:budget-exceeded` reusing the existing outcome with a new reason (D4 -- **NO** new outcome constant, Part 17 allowlist holds, `IndexerMetrics` bootstrap loop unchanged at 7 series per backend); 5xx/429/timeout/transport on the list endpoint flow through existing `RestAdminTemplate.classify*` helpers (D6); 4 new WireMock IT cases (budget-clear creates; budget-exceeded rejects; existing index short-circuits without list call; list 500 returns transient) | Accepted | log-indexer-service | -- |
| [0042](0042-quickwit-search-proxy.md) | log-indexer-service P7.4 tenant-scoped Quickwit search proxy via `LogSearchClient` SPI -- new read-side SPI `LogSearchClient` (mirror of P7.0 `QuickwitIndexAdmin`) in new `io.cortex.indexer.search` package with `String backendId()` + `SearchResult search(SearchRequest)`; new `SearchRequest(tenantId, indexId, query, maxHits)` immutable record with compact-ctor null/blank/non-positive rejection on every field (D2); new `SearchResult(backend, outcome, reason, numHits, hits)` immutable envelope with constants `BACKEND_NOOP/QUICKWIT`, `OUTCOME_NOOP/SEARCH_OK/TRANSIENT_FAILURE/PERMANENT_FAILURE`, factories `noop/searchOk/transientFailure/permanentFailure`, defensive `List.copyOf(hits)` (D1); `NoopLogSearchClient` `@ConditionalOnProperty(matchIfMissing=true)` default + `QuickwitHttpSearch` `@ConditionalOnProperty(backend=quickwit)` real adapter posting to `/api/v1/{indexId}/search` with body `{"query":"...","max_hits":N}` and parsing `{"num_hits":N,"hits":[...]}` response (D4); **client-side tenant-routing guardrail** -- `indexId` MUST `startsWith("cortex-" + tenantId + "-")` or the adapter returns `permanent_failure / quickwit:tenant-mismatch` WITHOUT contacting Quickwit (D3 -- fail-closed default, lowest-cost stop on cross-tenant leak); shares the P7.1 `quickwitAdminRestClient` bean (HTTP/1.1 + dual timeout per LD42 + LD121) -- zero new beans (D4); full outcome table mirrors ADR-0039/0040 with one explicit deviation -- 404 is permanent here (NOT idempotent-success like `dropIndex`), a missing index at search time is a caller-side config bug (D5); SPI MUST NOT throw -- every error path funneled into the envelope (D6); new sibling counter `cortex.indexer.search_total{backend, outcome, tenant_id}` joins the existing `index_admin_total` family on the SAME Part 17 allowlist; `IndexerMetrics` ctor gains `List<LogSearchClient>` injection + bootstrap loop registers `search_ok/transient_failure/permanent_failure` per backend (D7); ArchUnit gains `Search` layer (sibling of `Admin`); WireMock 3.9.2 IT covers happy 200 + 404 permanent + 500 transient + 429 transient + `Fault.CONNECTION_RESET_BY_PEER` transport per LD120 | Accepted | log-indexer-service | -- |

---

## Conventions

- **One ADR per architectural decision.** No bundling multiple decisions.
- **Sequential 4-digit prefix.** Take the next free number; never recycle.
- **MADR shape.** Keep `Context`, `Decision`, `Consequences`, `Alternatives`.
- **Status transitions.** `Proposed` -> `Accepted` -> `Superseded`. A
  superseded ADR is never deleted -- it gets a `Superseded by ADR-NNNN`
  pointer at the top and stays in the table above.
- **Tracking PR.** Every ADR is shipped in the same PR as the code that
  implements it (or the closer PR that retires the prior decision).

## How to add the next ADR (template)

```bash
cp docs/adr/0000-template.md docs/adr/0028-<slug>.md
# edit the new file: Title, Status=Proposed, Context, Decision,
# Consequences, Alternatives
# append the row to the right phase table in this INDEX.md
git add docs/adr/0028-<slug>.md docs/adr/INDEX.md
git commit -m "docs(adr): adr-0028 <short title>"
```
