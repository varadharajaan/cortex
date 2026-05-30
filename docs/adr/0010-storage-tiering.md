# 0010. Storage tiering: hot Loki, warm Loki, cold Blob

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: storage, retention, loki, cost

## Context and problem statement

Log volume in any non-trivial deployment grows fast. Keeping every event
in fast storage is prohibitively expensive; deleting old events is
unacceptable for compliance, debugging, and ML training. How do we tier?

## Decision drivers

- Recent events (minutes / hours) must answer queries in < 1s.
- Mid-range events (days / weeks) must answer in < 10s.
- Old events (months / years) must be retrievable, but slower is OK.
- Cost must scale sub-linearly with retention.
- Tiering must be transparent to query callers.

## Considered options

- **Three-tier Loki**: hot SSD chunks + warm Loki Blob + cold Blob with
  glacier-style class.
- **Two-tier**: hot Loki + cold Blob; no warm.
- **Postgres TimescaleDB hypertable** + S3-compatible archive.
- **Elasticsearch hot-warm-cold** indices.

## Decision outcome

Chosen option: **Three-tier Loki**:

- **Hot** (0 - 24h): Loki chunks on local SSD on the Loki pods.
- **Warm** (1d - 30d): Loki chunks in Azure Blob Storage hot tier
  (`Standard_LRS`, hot access).
- **Cold** (30d - 365d): Same Blob container, lifecycled to the cool
  access tier at day 30 and archive at day 90.

Postgres retains only **structured metadata** (event id, tenantId,
service, level, timestamp, correlationId) for fast `getLogById` and for
analytical JOINs. The full event body and all labels live in Loki.

Quickwit retains a **full-text index** of the last 90 days; older
events are re-indexed on demand from Blob on the rare cold-read path.

### Lifecycle rules (Azure Blob)

```json
{
  "rules": [
    {
      "name": "loki-warm-to-cool",
      "enabled": true,
      "filters": { "prefixMatch": ["loki/"] },
      "actions": {
        "baseBlob": {
          "tierToCool":  { "daysAfterModificationGreaterThan": 30 },
          "tierToArchive": { "daysAfterModificationGreaterThan": 90 },
          "delete":      { "daysAfterModificationGreaterThan": 365 }
        }
      }
    }
  ]
}
```

### Cold-read SLA

A query that resolves to archived blobs returns a `202 Accepted` with a
`retry-after` header. The result is materialized within 15 minutes
(Azure Archive rehydrate SLA) and pushed to a callback URL or made
available at `GET /v1/logs/jobs/<id>`. Callers must opt in by setting
`X-Allow-Cold-Read: true`.

### Positive consequences

- Cost scales with tier, not with retention.
- Hot path stays fast; archive tier is the cheapest Azure offers.
- Loki's built-in chunk-cache layer makes warm reads almost as fast as hot.
- Postgres stays small (metadata only); JOIN queries stay fast.

### Negative consequences

- Cold reads are slow (minutes-scale rehydrate). Mitigated by explicit
  opt-in header and a clear async job model.
- Re-indexing into Quickwit on cold reads is a batch operation.
  Mitigated by a per-tenant quota.
- Two storage systems (Postgres metadata + Loki/Blob body) means a
  consistent ingest must succeed in both before acking. Handled by the
  indexer's two-phase write + dead-letter on partial failure.

## Pros and cons of the options

### Three-tier Loki (chosen)

- **Good**, native to Loki; lifecycle is declarative; cheapest cold tier.
- **Bad**, Azure-specific lifecycle policy; rehydrate latency.

### Two-tier hot Loki + Blob

- **Good**, simpler.
- **Bad**, jumps from "fast" to "slow" with no middle ground; per-query
  unpredictability for the 1d-30d range.

### Postgres TimescaleDB

- **Good**, single system; SQL everywhere.
- **Bad**, structured-only; full-text on TBs is slow; label cardinality
  blows up indexes.

### Elasticsearch hot-warm-cold

- **Good**, mature pattern.
- **Bad**, cluster ops cost; licensing concerns; mediocre at structured
  joins; more expensive than Loki for the same retention.

## Links

- [Grafana Loki object storage](https://grafana.com/docs/loki/latest/configure/storage/).
- [Azure Blob lifecycle management](https://learn.microsoft.com/azure/storage/blobs/lifecycle-management-overview).
- [ADR-0003](./0003-three-tier-search.md).
