# Architecture Decision Records -- INDEX

> Single-page directory of every accepted ADR in this repository.
> Format: MADR (Markdown Any Decision Record). Add new ADRs as
> `docs/adr/NNNN-short-slug.md` and append a row to the table below
> in the same PR. Status legend: **Accepted** (in force) /
> **Superseded** (replaced -- see `Supersedes` column).

Last refreshed: 2026-06-04 (P5.4, PR for #80).
Total ADRs: 31 (`0000` template + `0001` .. `0027` + `0029` + `0030` + `0031`).

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
