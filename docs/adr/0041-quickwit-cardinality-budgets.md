# ADR-0041: log-indexer-service per-tenant cardinality budgets via `ensureIndex(spec, budget)`

* Status: Accepted
* Date: 2026-06-07
* Deciders: cortex agent + project owner
* Phase: P7.3
* Tracking issue: #105

## Context

P7.0 (ADR-0038) carved the `QuickwitIndexAdmin` SPI. P7.1
(ADR-0039) shipped the real `QuickwitHttpAdmin` adapter. P7.2
(ADR-0040) added the `applyRetention` lifecycle method. Every
admin operation now ticks the
`cortex.indexer.index_admin_total{backend, outcome, tenant_id}`
counter (Part 17 allowlist) so an operator can watch tenant
churn live.

The operational gap P7.3 closes:

> A misconfigured agent can bump its `docMappingVersion` (or
> any other component of the {tenant_id, doc_mapping_version}
> tuple driving `IndexSpec#indexId()`) on every boot. With the
> P7.0..P7.2 SPI as-is, every boot triggers a fresh
> `ensureIndex` ΓåÆ `created` cycle. The Quickwit metastore
> grows by one entry per boot per affected tenant and the
> `cortex.indexer.index_admin_total{tenant_id}` cardinality
> grows in lock-step. Left unchecked, this becomes:
>   * a Quickwit metastore blow-up (every entry costs RPC + IO
>     on the control plane),
>   * a Prometheus cardinality blow-up (a runaway tenant pushes
>     `tenant_id` past Part 17's documented per-tenant cap).

The wider system needs a *cheap, adapter-level admission gate*
that rejects the (N+1)-th index for a tenant before the create
call hits the metastore -- without changing the existing
`ensureIndex(IndexSpec)` overload's contract (call sites that
don't pass a budget continue to work exactly as today).

The wider tracking-file context: P7 is the indexer-service epic;
P7.0 + P7.1 + P7.2 are SHIPPED on `main`; the noop default still
wins on every dev profile.

## Decision drivers

* **D1.** The SPI must grow by exactly one method overload --
  `ensureIndex(IndexSpec, CardinalityBudget)` -- side by side
  with the existing 1-arg overload. Zero-impact on the noop
  default's existing 4 methods; the noop simply implements the
  new overload by returning `IndexAdminResult.noop(...)`.
* **D2.** The budget must be an immutable, validated value type
  so a misconfigured ceiling (zero / negative) fails at boot,
  not as a confusing `budget-exceeded` verdict on the first
  real call. New `CardinalityBudget(int maxIndexes)` record
  with a compact-ctor positive-int guard.
* **D3.** The "indexes I own" filter is **client-side** off the
  documented Quickwit list endpoint
  (`GET /api/v1/indexes` returns a JSON array of
  `IndexMetadata`). We count entries whose
  `index_config.index_id` starts with `cortex-<tenantId>-`.
  Quickwit 0.7 does not expose a server-side
  filter-by-prefix endpoint; the list response is small enough
  (well below 10k entries even at the documented per-tenant
  cap) that the client-side filter is acceptable for the
  v0.1.0 release.
* **D4.** Result envelope reuses `IndexAdminResult.OUTCOME_PERMANENT_FAILURE`
  with a **new reason** (`quickwit:budget-exceeded`). **NO** new
  outcome constant is added: the Part 17 allowlist holds
  unchanged, the `IndexerMetrics.bootstrapMeters()` series
  count per backend stays at 7 (created / exists / dropped /
  retention_applied / transient_failure / permanent_failure /
  noop), and existing alerts that filter
  `outcome="permanent_failure"` automatically catch the new
  rejection without any Grafana edit.
* **D5.** The gate is **idempotent-safe**: if the target index
  already exists (the GET probe returns 2xx), the budget check
  is skipped entirely and the call returns `exists`. A tenant
  that is already over its budget does not get penalised for
  the next idempotent re-check of an existing index. Only the
  *create-a-new-index* path runs the gate.
* **D6.** All HTTP / transport errors on the list endpoint flow
  through the existing `RestAdminTemplate.classify*` helpers --
  no new classification rules. JSON-parse failure on a 2xx body
  is a `transient_failure / quickwit:unknown`.
* **D7.** The 1-arg `ensureIndex(IndexSpec)` overload is
  unchanged. The 2-arg overload is the explicit-opt-in path for
  callers that want the gate (the P7.x scheduler or a future
  P7.4 admin REST endpoint). This keeps the SPI backwards
  compatible while letting callers choose budget enforcement
  per-call.

## Decision

Ship a new value type + a new SPI overload + the matching adapter
implementation.

### `CardinalityBudget` value type

```java
public record CardinalityBudget(int maxIndexes) {
    public CardinalityBudget {
        if (maxIndexes <= 0) {
            throw new IllegalArgumentException(
                "maxIndexes must be strictly positive; got " + maxIndexes);
        }
    }
}
```

Strict: a non-positive ceiling is a configuration bug. The
validation lives in the compact constructor so every construction
path is covered.

### SPI surface

```java
public interface QuickwitIndexAdmin {
    String backendId();
    IndexAdminResult ensureIndex(IndexSpec spec);
    IndexAdminResult ensureIndex(IndexSpec spec, CardinalityBudget budget); // NEW
    IndexAdminResult dropIndex(String indexId);
    IndexAdminResult applyRetention(IndexSpec spec, RetentionPolicy policy);
}
```

### `QuickwitHttpAdmin.ensureIndex(spec, budget)` flow

```
spec == null   -> permanent_failure / quickwit:null-spec
budget == null -> permanent_failure / quickwit:null-budget

# Step 1: GET probe (reuses checkExists).
GET /api/v1/indexes/{indexId}
  2xx                 -> exists                (skip budget gate)
  404                 -> proceed to budget gate
  4xx                 -> permanent_failure / quickwit:4xx:<n>
  5xx                 -> transient_failure / quickwit:5xx:<n>
  ...                 -> (existing classifier table)

# Step 2: Budget gate.
GET /api/v1/indexes
  2xx body parsed as JSON array; count entries where
    node.path("index_config").path("index_id").asText("")
      .startsWith("cortex-<tenantId>-")
  count >= budget.maxIndexes() -> permanent_failure / quickwit:budget-exceeded
  count <  budget.maxIndexes() -> proceed to create
  non-array body              -> transient_failure / quickwit:unknown
  4xx / 5xx / transport / etc -> (existing classifier table)

# Step 3: Create (reuses createIndex).
POST /api/v1/indexes
  2xx                 -> created
  4xx                 -> permanent_failure / quickwit:4xx:<n>
  5xx                 -> transient_failure / quickwit:5xx:<n>
  ...                 -> (existing classifier table)
```

Every terminal result ticks
`cortex.indexer.index_admin_total{backend, outcome, tenant_id}`
via `metrics.incIndexAdmin(...)` with the spec's tenant id.

### `IndexerMetrics` bootstrap

**Unchanged.** The bootstrap loop continues to register 7 outcomes
per backend; the new `quickwit:budget-exceeded` rejection lives in
the `reason` field (not a counter tag) and bumps the existing
`permanent_failure` outcome series.

```java
for (final QuickwitIndexAdmin admin : this.admins) {
    final String backend = admin.backendId();
    bootstrap(backend, IndexAdminResult.OUTCOME_CREATED);
    bootstrap(backend, IndexAdminResult.OUTCOME_EXISTS);
    bootstrap(backend, IndexAdminResult.OUTCOME_DROPPED);
    bootstrap(backend, IndexAdminResult.OUTCOME_RETENTION_APPLIED);
    bootstrap(backend, IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
    bootstrap(backend, IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
    bootstrap(backend, IndexAdminResult.OUTCOME_NOOP);
}
```

## Consequences

### Positive

* A runaway tenant can no longer blow up the Quickwit metastore
  or the `tenant_id` cardinality of the admin counter. The
  bound is `maxIndexes` per tenant, enforced before every new
  create.
* Existing call sites (`ensureIndex(spec)` only) are untouched
  -- backwards compatible at the source level. The scheduler
  / admin REST endpoint that wants the gate opts in by passing
  the budget.
* Reusing the `permanent_failure` outcome means existing alerts
  catch the rejection automatically; the new `reason` lets an
  operator filter `quickwit:budget-exceeded` specifically when
  drilling down.
* The GET probe runs *before* the budget gate, so an idempotent
  re-check of an existing index is fast (one HTTP round-trip)
  and is not penalised for an over-budget tenant.
* No new outcome means no new Grafana panel, no new Prometheus
  series, no Part 17 allowlist change.

### Negative

* Each create call now costs **two** Quickwit round-trips (GET
  probe + GET list) before the POST create. For the steady-state
  re-check path it stays at one (GET probe returns 2xx). The
  extra cost is bounded by the create-rate, not the steady-state
  call-rate.
* The client-side prefix filter requires reading the full
  `GET /api/v1/indexes` body on every gated create. At the
  v0.1.0 cluster size (low thousands of indexes total) this is
  comfortable; at extreme scale this would warrant a server-side
  filter endpoint or a cached list -- both deferred to a future
  ADR.
* The gate enforces a ceiling on *current* index count; it does
  not unwind orphaned indexes from earlier mis-configuration.
  An operator that lands here has to drop the dead indexes via
  `dropIndex` before the next create can succeed.

### Neutral

* No new Maven dependency; reuses the existing `RestClient`,
  `ObjectMapper`, and `RestAdminTemplate` wiring.
* No new package; `CardinalityBudget` joins `IndexSpec` and
  `RetentionPolicy` in `io.cortex.indexer.admin`.
* `NoopQuickwitIndexAdmin` gains exactly one method that returns
  `IndexAdminResult.noop(...)` -- semantically identical to its
  3 existing methods.

## Alternatives considered

### Rejected: server-side Quickwit quota plugin

Push the budget into Quickwit itself (a metastore policy that
rejects index creation past N for a tag). **Rejected** because:
(a) Quickwit 0.7 does not expose a metastore policy hook; (b)
the cortex side then has zero observability into the rejection
-- no `cortex.indexer.index_admin_total` tick, no Grafana wire-up;
(c) per-tenant overrides (future) need adapter-side config, not
server-side; (d) coupling the cortex tenant model to a Quickwit
server-side construct violates the "Quickwit is a swappable
backend" P7.0 ADR-0038 contract.

### Rejected: async cleanup of orphan indexes

Run an async sweeper that scans
`GET /api/v1/indexes`, identifies indexes whose
`docMappingVersion` has been superseded by a newer create for
the same tenant, and drops them in the background. **Rejected**
because: (a) it does not stop the runaway -- the metastore still
gets a write per boot before the sweeper catches up; (b) the
heuristic for "superseded" is fragile (what if the older index
is the right one and the new boot is the bug?); (c) ADR-0040
already added `applyRetention` for document-level expiry; piling
on another async lifecycle action on day one before any operator
has run the existing one is premature complexity.

### Rejected: soft "warn-only" budget mode

Tick the counter when the budget would have been exceeded but
let the create through anyway. **Rejected** because: (a) the
whole point is to stop the metastore blow-up; a warn-only mode
leaves the blow-up in place; (b) the operator already gets the
`permanent_failure / quickwit:budget-exceeded` series on the
counter -- which is the warning signal -- *and* the protection.
Pick one mode and make it the strict one for v0.1.0; a
warn-only override can be added later if a real operator needs
it.

### Rejected: separate `enforceBudget(spec, budget)` SPI method

Add a standalone budget-check SPI method that callers invoke
*before* `ensureIndex(spec)`. **Rejected** because: (a) it would
be a TOCTOU footgun -- two callers could each pass the gate
then both create, busting the ceiling; (b) it pushes the
ordering discipline into every call site; (c) the combined
overload makes the check + create atomic from the call site's
perspective (still not server-side atomic, but the race window
shrinks from "two SPI calls apart" to "one HTTP round-trip
apart"); (d) one fewer SPI method is one fewer thing for the
noop default + future adapters to implement.

## References

* ADR-0038 -- P7.0 `QuickwitIndexAdmin` SPI + per-backend selection contract.
* ADR-0039 -- P7.1 `QuickwitHttpAdmin` real HTTP admin client + `RestAdminTemplate`.
* ADR-0040 -- P7.2 `applyRetention` via Quickwit Delete API.
* ADR-0036 -- the `RestDispatchTemplate` composition pattern this work mirrors.
* Quickwit list-indexes documentation -- `GET /api/v1/indexes` returns a JSON array of `IndexMetadata`.
* Part 17 -- metrics tag allowlist (`backend`, `outcome`, `tenant_id`).
* LD42 -- HTTP/1.1 pin via `JdkClientHttpRequestFactory`.
* LD121 -- dual connect+read timeout on `JdkClientHttpRequestFactory`.
* LD106 + LD112 -- Micrometer bootstrap-registration pattern.
