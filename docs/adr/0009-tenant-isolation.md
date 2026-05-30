# 0009. Tenant isolation: tenantId on every record + B-tree index

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: multi-tenancy, security, postgres, loki, quickwit

## Context and problem statement

CORTEX is a multi-tenant SaaS-shaped application. Tenants must not see
each other's data, ever. We need a tenant-isolation model that works
across all three search tiers (Postgres, Loki, Quickwit), the message
bus, traces, and logs.

## Decision drivers

- Strong isolation (a query without `tenantId` returns zero rows).
- Cheap to enforce (no extra DB per tenant).
- Works identically across all three storage tiers.
- Tenant context flows automatically through HTTP, async, and traces.
- No application code is allowed to forget `tenantId`.

## Considered options

- **Shared schema + `tenant_id` column + B-tree index** + row-level
  filter enforced in the repository layer.
- **Schema-per-tenant** (Postgres `CREATE SCHEMA tenant_<uuid>`).
- **Database-per-tenant**.
- **Postgres Row-Level Security (RLS)**.

## Decision outcome

Chosen option: **Shared schema with `tenant_id UUID NOT NULL` on every
table, B-tree composite indexes leading with `tenant_id`, and a
repository-layer guard** that rejects any query missing the tenant
predicate. Optional RLS layered on top in production for defense in depth.

### Schema rule

Every table has:

```sql
tenant_id UUID NOT NULL,
PRIMARY KEY (tenant_id, id),
-- or: PRIMARY KEY (id), and:
CREATE INDEX ix_<table>_tenant_id ON <table> (tenant_id, <hot-column>);
```

### Context propagation

| Layer       | Carrier                                            |
| ----------- | -------------------------------------------------- |
| HTTP in     | `X-Tenant-Id` header (validated against JWT claim) |
| HTTP out    | Same header (Feign / RestClient interceptor)       |
| Async bus   | Message header `x-tenant-id`                       |
| Logs (MDC)  | Key `tenant_id`                                    |
| Traces      | OpenTelemetry baggage key `tenant.id`              |
| Loki        | Label `tenant_id=<uuid>` (mandatory on every push) |
| Quickwit    | Indexed field; every query auto-appends predicate  |

### Repository guard

A custom `TenantAwareRepository<T>` interface rejects any `find*` /
`update*` / `delete*` method whose query does not include `tenant_id`.
Enforced by a custom Querydsl / Specification audit at startup, plus a
Checkstyle regex check for raw `EntityManager.createQuery(` calls in
production code.

### Positive consequences

- Single shared database; cheap to operate; one set of backups.
- Strong isolation: missing predicate -> 0 rows (B-tree miss).
- Cross-cutting context (MDC, baggage, bus header) is set in one filter
  per surface; service code never touches `tenantId` manually.
- Loki label and Quickwit field naturally partition data on disk.

### Negative consequences

- A schema-level mistake (forgetting `tenant_id`) is catastrophic.
  Mitigated by Flyway migration review and a CI rule that flags any
  new `CREATE TABLE` missing `tenant_id`.
- "Noisy neighbor": a heavy tenant impacts others on the shared DB.
  Mitigated by per-tenant rate limits at the gateway and a future
  read-replica routing layer.

## Pros and cons of the options

### Shared schema + tenant_id column + B-tree (chosen)

- **Good**, cheap; one DB; one backup; uniform across tiers.
- **Bad**, requires discipline; missing predicate is dangerous.

### Schema-per-tenant

- **Good**, strong physical separation in Postgres.
- **Bad**, schema migrations become N migrations; Loki and Quickwit
  don't have a clean "schema" analog.

### Database-per-tenant

- **Good**, hardest isolation.
- **Bad**, infeasible at scale; per-tenant connection pools; per-tenant
  backups.

### RLS only

- **Good**, defense in depth.
- **Bad**, no analog in Loki or Quickwit; the chosen option already
  layers RLS on top for prod.

## Links

- [PostgreSQL Row Security Policies](https://www.postgresql.org/docs/16/ddl-rowsecurity.html).
- [ADR-0010](./0010-storage-tiering.md).
- [ARCHITECTURE.md §8](../ARCHITECTURE.md).
