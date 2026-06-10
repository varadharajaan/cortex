# ADR-0052: log-remediation anomaly read model

- **Status**: Accepted
- **Date**: 2026-06-09
- **Deciders**: @varadharajaan (operator), Codex
- **Tags**: log-remediation-service, anomalies, postgres, flyway, p9.3,
  p9.3a, getAnomalies

## Context

P9.3 adds the `getAnomalies` read use case. Before this ADR, anomaly
events existed only as Kafka CloudEvents:

- `log-processor-service` published valid anomalies to
  `cortex.anomalies.v1`;
- `log-remediation-service` consumed those events for notification and
  auto-remediation;
- no service owned a durable query model that could answer
  `GET /api/v1/anomalies` or future gateway REST + GraphQL
  `getAnomalies` parity.

Kafka is the right transport and replay boundary for the action path, but
it is not a tenant-scoped query store. Querying Kafka history from the
gateway or processor would couple the read API to topic retention,
consumer offsets, and replay behavior.

## Decision

Adopt a remediation-owned Postgres read model for valid anomaly events.

### D1. log-remediation-service owns the anomaly read side

The `anomalies` table and `GET /api/v1/anomalies` endpoint live in
`log-remediation-service`. This is the first service that sees the
already-classified anomaly envelope and it already owns the remediation
audit and operator-facing response path.

`log-processor-service` continues to own AI classification and anomaly
publication. `log-gateway` will later expose public REST + GraphQL parity
in P9.3b by forwarding to remediation over `lb://log-remediation-service`.

### D2. Persist only after a valid CloudEvent parse

`AnomalyConsumer` parses the CloudEvents 1.0 envelope first. Malformed
input still goes to `cortex.anomalies.v1.dlq` per ADR-0051.

Only a valid typed `AnomalyEvent` is copied into the read model. This
keeps poison input out of the query API and prevents the read table from
becoming a second DLQ.

### D3. The read-model writer is fail-open

`AnomalyReadModelWriter.persistFailOpen(...)` records the row in a short
transaction and absorbs duplicate `(tenant_id, event_id)` inserts.

A Postgres outage is logged but does not block remediation, human
fallback, outcome audit, or Kafka acknowledgment. The read model is a
query convenience; the remediation action path remains primary.

### D4. Postgres schema is service-owned

The table is created by `log-remediation-service` Flyway migration
`V2__anomalies.sql`. Version 2 is intentional: when local dev shares a
non-empty smoke database with ingest, Flyway `baseline-on-migrate`
creates a version-1 baseline, so the first remediation-owned migration
must be greater than that baseline.

- `tenant_id`
- `event_id`
- `severity`
- `reason`
- `ts`
- `level`
- `service`
- `message`
- `confidence`
- `anomaly_type`
- `remediation_key`
- `received_at`

The unique key is `(tenant_id, event_id)`. Indexes are optimized for
tenant-scoped newest-first reads and tenant/severity time slices.

There is no foreign key to ingest-owned tenant tables. Local dev may
share the smoke database, but production must provide a remediation-owned
database or schema. Flyway uses the service-specific history table
`remediation_flyway_schema_history` so local shared-database migrations
do not collide.

### D5. P9.3a direct API is explicitly tenant-scoped

P9.3a exposes:

`GET /api/v1/anomalies?tenantId=<tenant>&since=<iso>&until=<iso>&limit=<n>`

Rules:

- `tenantId` is required until gateway P9.3b forwards authenticated
  tenant context.
- `since` and `until` are optional inclusive `ts` bounds.
- `limit` defaults to 100 and clamps at 500.
- results order by `ts DESC, id DESC`.
- validation failures return HTTP 400 with
  `errorCode=VALIDATION_FAILED`.

### D6. No Kafka replay query and no DLQ-2

The read API never scans Kafka. Kafka retention remains an operational
setting, not a query correctness dependency.

Valid remediation misses are still outcome events on
`cortex.remediation.outcomes.v1` plus human fallback, not DLQ messages.
The anomaly read model records the input anomaly, not a failed-remediation
queue.

## Consequences

- P9.3 has a real query source for anomalies.
- Gateway P9.3b can stay a thin parity layer over remediation rather than
  parsing Kafka or duplicating processor logic.
- The remediation service gains a relational dependency. This is
  intentional and bounded to read-model persistence.
- A temporary Postgres outage can create query gaps because the writer is
  fail-open. Operators should alert on database health and can replay the
  anomaly topic within Kafka retention if a backfill tool is added later.
- The action path remains deterministic and does not wait on the query
  path.

## Alternatives

1. **Query Kafka from the gateway.** Rejected. It couples HTTP reads to
   topic retention, offset management, and replay semantics.
2. **Store anomalies in log-processor-service.** Rejected. Processor owns
   classification and sink fan-out; remediation is the operator-facing
   anomaly response stage and already owns anomaly decisions.
3. **Store anomalies in log-ingest-service.** Rejected. Ingest owns raw
   logs before classification. It does not know which events became
   anomalies.
4. **Make read-model persistence fail-closed.** Rejected. A query-store
   outage must not suppress incident response.
5. **Add DLQ-2 for anomalies that cannot be read or remediated.**
   Rejected. Malformed input has the anomaly DLQ; valid remediation
   decisions are audited outcomes.

## Verification

- `.\mvnw.cmd -pl log-remediation-service clean verify -B`
- `scripts\live-e2e\smoke-p9-3a.ps1 -KeepInfra -KeepService`
- `npx --yes newman run postman\log-remediation.postman_collection.json
  -e postman\log-remediation.postman_environment_local.json
  --env-var tenant_id=<printed-by-smoke>
  --env-var anomaly_event_id=<printed-by-smoke>
  --reporters cli --bail`

## References

- ADR-0031 -- processor anomaly publisher to `cortex.anomalies.v1`.
- ADR-0032 -- remediation dispatcher SPI and consumer contract.
- ADR-0051 -- remediation auto-remediation pipeline.
- `log-remediation-service/src/main/resources/db/migration/V2__anomalies.sql`
- `scripts/live-e2e/smoke-p9-3a.ps1`
