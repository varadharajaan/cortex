# 0003. Three-tier search backend on Day 1 (Postgres + Loki + Quickwit)

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: search, storage, indexer

## Context and problem statement

Log queries fall into three very different shapes:

1. Structured / predicate-heavy ("all 5xx for tenant T in the last hour
   on service `log-gateway` with HTTP method POST"). Best served by a
   relational engine.
2. Label-based / time-range ("show me everything tagged
   `service=ingest, env=prod` for the last 24h"). Best served by Loki.
3. Free-text / fuzzy ("anything mentioning `kafka rebalance` across all
   tenants in the last week"). Best served by an inverted-index engine
   like Quickwit.

No single engine wins all three. The user has explicitly required all
three from Day 1: **"I need PG GIN + loki labels + quickWit all"**.

## Decision drivers

- Cover all three query shapes without compromising on any.
- Allow query routing at the API layer (the caller doesn't pick an engine).
- Keep ingestion fan-out manageable (one write to bus -> indexer writes to all).
- Avoid premature SaaS dependencies; everything runs locally.

## Considered options

- **Three-tier**: Postgres 16 (GIN) + Loki + Quickwit.
- **Two-tier**: Postgres + Loki (defer Quickwit to a later phase).
- **Single-tier**: Elasticsearch / OpenSearch only.

## Decision outcome

Chosen option: **Three-tier**. Each engine is best-in-class for its
query shape, and all three are operationally simple to run via Docker
Compose locally and Helm in cluster.

### Routing rules (in `log-indexer-service`)

| Query shape                                                | Tier     |
| ---------------------------------------------------------- | -------- |
| `WHERE` with structured columns + optional JSONB filter    | Postgres |
| Label selector (`{service="x"}`) + optional regex          | Loki     |
| Free-text, fuzzy, or NL-derived full-text query            | Quickwit |

### Positive consequences

- Every common query shape lands on the engine that does it best.
- Postgres remains the single source of truth for "what is this
  specific log entry?" via `getLogById`.
- Loki's hot/warm tiering plus Blob backing gives cheap long retention.
- Quickwit handles TB-scale free-text in sub-second on commodity disk.

### Negative consequences

- Operational footprint: three datastores to monitor, back up, and
  upgrade. Mitigated by Helm charts and provisioned Grafana dashboards.
- Ingestion writes three places. `log-indexer-service` batches and uses
  Resilience4j to bulkhead each tier (one slow tier doesn't stall the others).
- Schema drift risk. Mitigated by a single canonical `LogEntry` Avro/
  JSON schema in `log-agent-lib`.

## Pros and cons of the options

### Three-tier

- **Good**, best engine for every query shape; no compromise.
- **Bad**, three systems to operate; more ingestion fan-out.

### Two-tier (defer Quickwit)

- **Good**, simpler ops.
- **Bad**, free-text queries either go to Postgres (slow) or are
  unsupported; user has explicitly rejected this.

### Single-tier (Elasticsearch / OpenSearch)

- **Good**, one system.
- **Bad**, expensive at scale; complex cluster ops; license concerns
  with Elasticsearch; mediocre at structured joins.

## Links

- Locked decision LD3.
- Rejected alternative RA2 (Quickwit as a P10 add-on).
- [ARCHITECTURE.md §3](../ARCHITECTURE.md).
