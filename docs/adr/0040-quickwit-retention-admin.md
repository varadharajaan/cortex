# ADR-0040: log-indexer-service `applyRetention` via Quickwit Delete API

* Status: Accepted
* Date: 2026-06-07
* Deciders: cortex agent + project owner
* Phase: P7.2
* Tracking issue: #102

## Context

P7.0 (ADR-0038) carved the `QuickwitIndexAdmin` SPI with two
lifecycle methods -- `ensureIndex(IndexSpec)` + `dropIndex(String)`
-- and shipped the noop default. P7.1 (ADR-0039) implemented those
two methods against the real Quickwit REST admin surface with a
composition-based `RestAdminTemplate` for outcome classification.

P7.2 adds the third lifecycle operation that operators run on a
schedule against every tenant index: **retention enforcement**.
The contract the wider system needs:

> Given a tenant index and a documented TTL, delete every
> document older than `now - ttl` without dropping the index
> itself. Idempotent: re-running with the same policy at a later
> time deletes anything that newly expired since the prior call.

This is distinct from `dropIndex`: drop is the whole-index retire
(used when a tenant offboards or when a `docMappingVersion` is
bumped); retention is the per-document time-window sweep that runs
forever on a working index.

The wider tracking-file context: P7 is the indexer-service epic,
P7.0 + P7.1 are SHIPPED on `main`, P7.2 is the next sub-phase, and
the noop default still wins on every dev profile (production
enables `cortex.indexer.admin.backend=quickwit`).

## Decision drivers

* **D1.** The SPI must grow by exactly one method; the noop +
  Quickwit HTTP adapters both implement it. No new SPI files,
  no new packages.
* **D2.** Retention TTL must be an immutable, validated value
  type so the configuration-bind layer rejects null / zero /
  negative TTLs at boot time, not at the first Quickwit call.
* **D3.** Result envelope reuses `IndexAdminResult` with a new
  outcome constant `retention_applied`; the
  `IndexerMetrics.bootstrapMeters()` loop registers it so
  `/actuator/prometheus` exposes the series on the first scrape.
* **D4.** The adapter wires to Quickwit's documented Delete API:
  `POST /api/v1/{indexId}/delete-tasks` with body
  `{"query":"*","end_timestamp":<epoch_seconds>}`. Quickwit
  schedules deletion server-side for every doc where
  `timestamp_field < end_timestamp`. The 404 status here is a
  **permanent failure** (index missing == config error), NOT
  idempotent-success like `dropIndex`.
* **D5.** The cutoff timestamp computation is `clock.instant()
  .minus(policy.ttl()).getEpochSecond()` -- so the adapter
  accepts a `Clock` via a package-private test-seam constructor
  while the production Spring constructor uses `Clock.systemUTC()`.
  Mirrors the dual-ctor pattern P5.4's `AnomaliesPublisher` uses.
* **D6.** All other HTTP outcomes flow through the existing
  `RestAdminTemplate.classify*` helpers: 429 -> transient
  `quickwit:429`; 5xx -> transient `quickwit:5xx:<n>`; other 4xx
  (including 404) -> permanent `quickwit:4xx:<n>`; timeout cause
  -> transient `quickwit:timeout`; other transport -> transient
  `quickwit:transport`; unknown -> transient `quickwit:unknown`.
  Zero new classification rules.
* **D7.** The metrics counter family `cortex.indexer
  .index_admin_total{backend, outcome, tenant_id}` gains exactly
  one new outcome value (`retention_applied`); no new tag keys,
  no new counter names. Part 17 allowlist holds.

## Decision

Ship `applyRetention(IndexSpec, RetentionPolicy)` on the
`QuickwitIndexAdmin` SPI plus implementations on both the noop
default and the `QuickwitHttpAdmin` adapter.

### `RetentionPolicy` value type

```java
public record RetentionPolicy(Duration ttl) {
    public RetentionPolicy {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                "ttl must be a positive duration; was: " + ttl);
        }
    }
}
```

Strict: a missing TTL is a configuration bug, not a runtime
contingency. The validation lives in the compact constructor so
every construction path (Spring `@ConfigurationProperties` bind,
test new(), JSON deserialisation, etc.) is covered.

### SPI surface

```java
public interface QuickwitIndexAdmin {
    String backendId();
    IndexAdminResult ensureIndex(IndexSpec spec);
    IndexAdminResult dropIndex(String indexId);
    IndexAdminResult applyRetention(IndexSpec spec, RetentionPolicy policy); // NEW
}
```

### `QuickwitHttpAdmin.applyRetention` flow

```
spec == null  -> permanent_failure / quickwit:null-spec
policy == null -> permanent_failure / quickwit:null-policy

end_timestamp = clock.instant().minus(policy.ttl()).getEpochSecond()
POST /api/v1/{indexId}/delete-tasks
body = {"query":"*","end_timestamp": <end_timestamp>}

2xx                  -> retention_applied
4xx (incl. 404)      -> permanent_failure / quickwit:4xx:<n>  (D4 -- 404 == config error)
5xx                  -> transient_failure / quickwit:5xx:<n>
429                  -> transient_failure / quickwit:429
timeout cause        -> transient_failure / quickwit:timeout
other transport      -> transient_failure / quickwit:transport
unknown RuntimeEx    -> transient_failure / quickwit:unknown
```

Every terminal result ticks
`cortex.indexer.index_admin_total{backend, outcome, tenant_id}`
via `metrics.incIndexAdmin(...)` with the spec's tenant id.

### `IndexerMetrics` bootstrap

```java
for (final QuickwitIndexAdmin admin : this.admins) {
    final String backend = admin.backendId();
    bootstrap(backend, IndexAdminResult.OUTCOME_CREATED);
    bootstrap(backend, IndexAdminResult.OUTCOME_EXISTS);
    bootstrap(backend, IndexAdminResult.OUTCOME_DROPPED);
    bootstrap(backend, IndexAdminResult.OUTCOME_RETENTION_APPLIED); // NEW
    bootstrap(backend, IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
    bootstrap(backend, IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
}
```

OCP: adding the new outcome touches exactly one line in the loop;
adding a new backend still requires zero edits.

## Consequences

### Positive

* The retention sweeper (P7.3+) can be a 5-line `@Scheduled` bean
  that calls `applyRetention(spec, policy)` on each tenant
  index. No complicated client-side query-then-delete loop, no
  state machine -- Quickwit's Delete API owns the lifecycle.
* Failures are observable on the existing counter -- no new
  Grafana wire-up required for the v0.1.0 release.
* The `Clock` test-seam keeps the cutoff computation
  deterministic; unit tests pin the epoch second so the body
  shape assertion is exact.
* 404 == permanent-failure semantic gives operators a loud
  signal when a scheduler is configured against a stale tenant
  list (e.g. tenant offboarded but config not updated). Silent
  success on a missing index would mask the bug indefinitely.
* SPI grows by one method without renaming or deprecating
  anything -- backwards compatible at the source level for any
  future external implementer that pre-dates this change (none
  exists today; this is forward-looking discipline).

### Negative

* Quickwit deletes are asynchronous server-side; the 2xx
  response means "delete task accepted", not "delete complete".
  Callers that need confirm-on-completion semantics need the
  follow-up `GET /api/v1/{indexId}/delete-tasks` poll, which is
  out of scope for P7.2 -- documented as a future enhancement
  in the README.
* The cutoff is computed adapter-side, not Quickwit-side, so
  rapid back-to-back calls produce slightly different
  `end_timestamp` values. Acceptable for retention (the window
  only moves forward) but means we shouldn't claim "exact
  cutoff" -- it's "approximate cutoff within one HTTP RTT".

### Neutral

* No new Maven dependency; reuses the existing `RestClient`,
  `ObjectMapper`, and `RestAdminTemplate` wiring.
* No new package; `RetentionPolicy` joins `IndexSpec` in
  `io.cortex.indexer.admin`.

## Alternatives considered

### Rejected: per-index retention config block in Quickwit `IndexConfig`

Quickwit's `IndexConfig` supports a top-level `retention` block
with `period` + `schedule` keys -- the engine then runs the
deletion sweep itself on a cron. Tempting because it removes the
sweeper entirely. **Rejected** because: (a) changing the retention
block requires deleting and recreating the index in Quickwit 0.7
(no in-place update), which would drop every doc; (b) the schedule
isn't observable from the cortex side -- we want the
`retention_applied` counter to land per-call so SLO dashboards can
alert when the sweep stops running; (c) per-tenant retention
overrides (future) need adapter-side policy, not server-side.

### Rejected: native Quickwit GC scheduler

Quickwit has an internal split-GC that runs at a fixed interval;
some operators rely on it instead of explicit deletes. **Rejected**
because it operates on split-level expiry (older than X days
across all docs in the split), not document-level timestamp
filters. We want document-level granularity so a tenant who
backfills old logs gets those backfills swept on the next cycle,
not preserved until the entire split ages out.

### Rejected: client-side `search + delete-by-id` loop

Build the `eventId` list via Quickwit search, then issue per-doc
deletes. **Rejected** because: (a) Quickwit doesn't expose a
delete-by-id endpoint (only the bulk Delete API used here); (b)
even if it did, the round-trip count would be O(deleted docs)
which is unbounded; (c) the server-side `DeleteQuery` is the
documented happy path.

### Rejected: external cron job (e.g. K8s CronJob shelling out to `curl`)

Run a CronJob in the cluster that POSTs the delete-task directly
to Quickwit, bypassing the indexer service. **Rejected** because:
(a) the cortex side then has no observability into the sweep --
no `cortex.indexer.index_admin_total` ticks, no Grafana metrics,
no per-tenant retry policy; (b) the cron config drifts away from
the application config (two sources of truth for the TTL); (c)
the cron has no access to the `IndexerMetrics` counter family
that downstream alerting depends on.

### Rejected: gRPC client instead of REST

Quickwit exposes a gRPC admin API alongside REST. **Rejected**
per LD42 + ADR-0039 D1: the whole cortex HTTP surface (P5.3
sinks, every P6 dispatcher, the P7.1 admin client) is pinned to
HTTP/1.1 via `JdkClientHttpRequestFactory` for wire-format
uniformity + WireMock compatibility. Adopting gRPC for a single
endpoint would break the symmetry and double the test-harness
footprint.

## References

* ADR-0038 -- P7.0 `QuickwitIndexAdmin` SPI + per-backend selection contract.
* ADR-0039 -- P7.1 `QuickwitHttpAdmin` real HTTP admin client + `RestAdminTemplate`.
* ADR-0036 -- the `RestDispatchTemplate` composition pattern this work mirrors.
* ADR-0030 -- writer-side `QuickwitSink` in `log-processor-service`.
* Quickwit Delete API documentation -- `POST /api/v1/{index_id}/delete-tasks` with `DeleteQuery` body.
* LD42 -- HTTP/1.1 pin via `JdkClientHttpRequestFactory`.
* LD121 -- dual connect+read timeout on `JdkClientHttpRequestFactory`.
* LD106 + LD112 -- Micrometer bootstrap-registration pattern.
* P5.4 / `AnomaliesPublisher` -- the dual-ctor `Clock`-injection pattern.
