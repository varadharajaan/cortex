# CORTEX - Architecture

> Cognitive Observability Runtime Telemetry EXchange.
> AI-powered intelligent log management system.

This document is the canonical, public-facing architecture reference.
For the high-level "what is CORTEX" pitch, see the top-level
[README](../README.md). For phase-by-phase delivery sequencing, see
[PHASES.md](./PHASES.md). For the rationale behind individual choices,
see [Architecture Decision Records](./adr/).

---

## 1. System overview

CORTEX ingests structured and unstructured logs from any source, enriches
them with AI-derived semantics (anomaly score, intent, suggested remediation),
stores them across a three-tier search backend, and exposes them via both
REST and GraphQL. A self-healing loop can trigger Ansible playbooks when
high-confidence anomalies are detected.

```
                     +-------------------+
                     |   log-agent-lib   |  (Java client SDK)
                     +---------+---------+
                               | HTTPS + API key
                               v
                     +---------+---------+
                     |    log-gateway    |  (Edge: auth, rate limit, route)
                     +---------+---------+
                               |
              +----------------+----------------+
              |                |                |
              v                v                v
     +--------+------+ +-------+------+ +-------+--------+
     | log-ingest    | | log-processor| | log-remediation|
     | (REST/Kafka)  | | (AI enrich)  | | (Ansible run)  |
     +--------+------+ +-------+------+ +-------+--------+
              |                |                |
              +----------------+----------------+
                               |
                               v
                     +---------+---------+
                     |   Message Bus     |  (RabbitMQ local / Service Bus)
                     +---------+---------+
                               |
                               v
                     +---------+---------+
                     |   log-indexer     |  (Routes to 3-tier search)
                     +---------+---------+
                               |
              +----------------+----------------+
              |                |                |
              v                v                v
         +----+----+      +----+----+      +----+----+
         | Postgres|      |  Loki   |      | Quickwit|
         |  GIN    |      | hot+warm|      | full-txt|
         +---------+      +---------+      +---------+

                               ^
                               |
                     +---------+---------+
                     |  log-monitoring   |  (OTel, Micrometer, SLO)
                     +-------------------+
```

---

## 2. Modules

| Module                   | Purpose                                                 | Port (local) |
| ------------------------ | ------------------------------------------------------- | ------------ |
| `log-agent-lib`          | Thin Java client SDK. Buffer, retry, gzip, sign.        | n/a (lib)    |
| `log-gateway`            | Edge. Auth (JWT + API key), rate limit, multi-tenant.   | 8080         |
| `log-ingest-service`     | Receives logs (REST), validates, publishes to bus.      | 8081         |
| `log-processor-service`  | Consumes bus, AI enrichment (Spring AI), pushes onward. | 8082         |
| `log-remediation-service`| Detects high-confidence anomalies; runs Ansible.        | 8083         |
| `log-indexer-service`    | Persists to Postgres + Loki + Quickwit. Owns Quickwit.  | 8084         |
| `log-monitoring-service` | SLO, alerting, OTel collection, Grafana datasource.     | 8085         |

All modules share the parent POM (`cortex-parent`) and inherit the same
quality gates (Checkstyle, SpotBugs, JaCoCo, OWASP, Enforcer).

---

## 3. Three-tier search

CORTEX runs all three search backends on Day 1 (locked decision LD3):

| Tier      | Backend  | Strength                                | Owner               |
| --------- | -------- | --------------------------------------- | ------------------- |
| Tier 1    | Postgres | Structured filters, JOINs, JSONB + GIN  | `log-indexer`       |
| Tier 2    | Loki     | Label-based queries, hot/warm + Blob    | `log-indexer`       |
| Tier 3    | Quickwit | Full-text, sub-second over TB-scale     | `log-indexer`       |

The `searchLogs` API (REST + GraphQL) routes the query to the right tier
based on the query shape:

- Predicate-heavy structured filter -> Postgres.
- Label or time-range scan -> Loki.
- Free-text or fuzzy match -> Quickwit.

A natural-language query (`nlToLogQL`) translates the user prompt to
LogQL (or SQL or Quickwit DSL) via Spring AI.

---

## 4. APIs

### 4.1 REST (Day 1)

Ingestion (high throughput, REST only):
- `POST /v1/ingest`                  - batch ingest
- `POST /v1/ingest/stream`           - streamed (newline-delimited JSON)

Query (REST + GraphQL parity):
- `GET  /v1/logs/search?...`         - searchLogs
- `GET  /v1/logs/{id}`               - getLogById
- `POST /v1/logs/nl-to-logql`        - nlToLogQL
- `GET  /v1/anomalies?...`           - getAnomalies

### 4.2 GraphQL (Day 1)

Single endpoint: `POST /graphql`. Four query operations only:

```graphql
type Query {
  searchLogs(input: LogSearchInput!): LogSearchResult!
  getLogById(id: ID!): LogEntry
  nlToLogQL(prompt: String!): String!
  getAnomalies(input: AnomalyFilter!): [Anomaly!]!
}
```

No GraphQL mutations Day 1 (LD4 + RA5). Ingestion remains REST-only
because it is throughput-dominant and uses API-key auth, not OAuth.

---

## 5. AI provider abstraction

The `log-processor-service` and the `nlToLogQL` endpoint use Spring AI
(1.0.0) behind a thin `AiProvider` interface. Two implementations Day 1:

- **Ollama** (local dev) - free, offline, model = `llama3.1:8b`.
- **Azure OpenAI** (prod) - gpt-4o-mini for enrichment, gpt-4o for NL->LogQL.

Selection is per-tenant via configuration; Resilience4j wraps every call.
See [ADR-0006](./adr/0006-ai-provider-abstraction.md).

---

## 6. Self-healing

When `log-processor-service` produces an anomaly with
`confidence >= 0.85` AND `severity in (HIGH, CRITICAL)`,
`log-remediation-service` looks up a matching playbook in
`infra/ansible/playbooks/` and runs it via `ansible-runner`.

A dry-run is always performed first; a successful dry-run plus an
explicit `auto_apply: true` flag on the playbook is required to actually
apply remediation. Every action emits an audit event.

See [ADR-0007](./adr/0007-self-healing-playbooks.md).

---

## 7. Observability

- **Tracing**: OpenTelemetry SDK on every service, OTLP -> Tempo.
- **Metrics**: Micrometer -> Prometheus.
- **Logs (self)**: Logback `loki4j` appender pushes service logs to the
  same Loki cluster CORTEX serves (dog-fooding).
- **Dashboards + SLOs**: Grafana, provisioned via `infra/grafana/`.

See [ADR-0011](./adr/0011-observability.md).

---

## 8. Multi-tenancy

Every record carries `tenantId` (UUID v4). B-tree index on every table.
Tenant context is propagated through:

- HTTP header `X-Tenant-Id` (validated by gateway against JWT claim).
- MDC (`tenant_id`) for logs.
- OTel baggage for traces.
- Message bus header for async hops.

See [ADR-0009](./adr/0009-tenant-isolation.md).

---

## 9. Tech stack at a glance

| Layer        | Choice                                                          |
| ------------ | --------------------------------------------------------------- |
| Language     | Java 17 LTS (no virtual threads; LD1)                           |
| Framework    | Spring Boot 3.3.5, Spring Cloud 2023.0.4, Spring AI 1.0.0       |
| Build        | Maven 3.9.9 (script-only wrapper)                               |
| Persistence  | Postgres 16 (GIN), Loki 3.x, Quickwit 0.8                       |
| Message bus  | RabbitMQ 3.13 (local), Azure Service Bus (prod)                 |
| AI           | Ollama (local), Azure OpenAI (prod), Spring AI abstraction      |
| Resilience   | Resilience4j 2.2.0 on every egress                              |
| Observability| OpenTelemetry 1.43.0, Micrometer, Grafana, Tempo, Loki          |
| Container    | Distroless base, multi-stage Dockerfile                         |
| Orchestration| Helm 3 charts, Kubernetes 1.30+                                 |
| IaC          | Terraform (Azure), Ansible (configuration + remediation)        |
| Tests        | JUnit 5, Testcontainers 1.20, REST Assured, Postman + Newman    |
| Quality gates| Checkstyle, SpotBugs + FindSecBugs, JaCoCo 80%, OWASP DC, SBOM  |

For each choice, see the corresponding ADR in [docs/adr/](./adr/).

---

## 10. Reading order

If you're new to CORTEX:

1. This document (architecture).
2. [PHASES.md](./PHASES.md) - delivery sequencing.
3. [ADR-0001](./adr/0001-language-and-runtime.md) onward, in numeric order.
4. The README's "Quick start" for local bring-up.
