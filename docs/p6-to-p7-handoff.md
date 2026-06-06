# P6 -> P7 handoff: Quickwit admin ownership boundary

> Single-page contract pinning everything the future
> `log-indexer-service` (P7 epic) needs in order to own Quickwit
> administration without colliding with the P5.3 writer side
> (`QuickwitSink`, ADR-0030). Shipped with P7.0 (scaffold for the
> P7 epic, PR for issue #98). Owner of the writer side:
> `log-processor-service` P5.3 (ADR-0030). Owner of the admin
> side: `log-indexer-service` P7 (this doc, ADR-0038).

---

## 1. The ownership boundary in one paragraph

`log-processor-service` P5.3 owns the **writer** -- it streams
parsed events into the per-tenant Quickwit index via the
`ParsedEventSink` -> `QuickwitSink` adapter (ADR-0030). It assumes
the index already exists; it never calls `POST /api/v1/indexes` or
`DELETE /api/v1/indexes/<id>`.

`log-indexer-service` P7 owns the **admin** -- it owns the index
LIFECYCLE: create (P7.1), drop (P7.1), retention sweep (P7.2),
per-tenant cardinality budgets (P7.3), and the search proxy
(P7.4). It never writes documents into an index; it never
references `QuickwitSink` in source.

Mechanically the boundary is enforced in three places:

1. **This ADR + ADR-0038** (the writing).
2. **The indexer module's pom.xml** -- zero dependency on the
   processor module.
3. **The ArchUnit layered contract** on the indexer side
   (`io.cortex.indexer.admin..` is self-contained) + the
   `dependencyConvergence` enforcer at the parent.

---

## 2. The SPI shape (locked in P7.0, ADR-0038)

| Element                            | Detail                                                                                          |
|------------------------------------|-------------------------------------------------------------------------------------------------|
| Package                            | `io.cortex.indexer.admin`                                                                       |
| Interface                          | `QuickwitIndexAdmin`                                                                            |
| Methods                            | `IndexAdminResult ensureIndex(IndexSpec)`, `IndexAdminResult dropIndex(String)`, `String backendId()` |
| Verdict record                     | `IndexAdminResult(String backend, String outcome, String reason)`                               |
| Input record                       | `IndexSpec(String tenantId, String indexId, String docMappingVersion)` (canonical-constructor validated against null + blank) |
| Default impl                       | `NoopQuickwitIndexAdmin` (gated `cortex.indexer.admin.backend=noop`, `matchIfMissing=true`)     |
| Binder gate                        | `cortex.indexer.admin.backend` resolved by `@ConditionalOnProperty` on each backend impl        |
| Throws contract                    | MUST NOT throw on transient downstream failure; MUST return `OUTCOME_TRANSIENT_FAILURE` verdict |

`IndexAdminResult` bounded outcome surface:

- `OUTCOME_NOOP` -- noop admin returned without action (P7.0).
- `OUTCOME_CREATED` -- new index created.
- `OUTCOME_EXISTS` -- index was already present (idempotent `ensureIndex`).
- `OUTCOME_DROPPED` -- index successfully dropped.
- `OUTCOME_TRANSIENT_FAILURE` -- downstream returned 429 / 5xx / timeout.
- `OUTCOME_PERMANENT_FAILURE` -- downstream returned 4xx other than 429.

`IndexAdminResult` bounded backend surface:

- `BACKEND_NOOP` -- noop admin (P7.0).
- `BACKEND_QUICKWIT` -- real HTTP admin (P7.1+).

---

## 3. Metric contract (locked in P7.0, ADR-0038)

| Metric                                  | Tags                            | Description                                                                                    |
|-----------------------------------------|---------------------------------|------------------------------------------------------------------------------------------------|
| `cortex.indexer.index_admin_total`      | `backend, outcome, tenant_id`   | Index admin calls handled by the active `QuickwitIndexAdmin` per backend per outcome per tenant |

Bootstrap-registered at `@PostConstruct` per LD106 + LD112 with
all-`unknown` placeholder values so the `/actuator/prometheus`
scrape exposes the family on the first scrape. The OCP-flipped
loop in `IndexerMetrics.bootstrapMeters()` walks the injected
`List<QuickwitIndexAdmin>` and bootstraps
`{created, exists, dropped, transient_failure, permanent_failure}`
for each `backendId()`. Adding a new admin backend in P7.1+ ships
ZERO edits in `IndexerMetrics`.

---

## 4. Health contract (locked in P7.0, ADR-0038)

| Endpoint                              | P7.0 behaviour                                          | P7.1+ behaviour                                |
|---------------------------------------|---------------------------------------------------------|------------------------------------------------|
| `GET /actuator/health/quickwit`       | `UP` with detail `{"backend":"noop"}`                   | Probes `GET /api/v1/health` on the real Quickwit endpoint via the bound HTTP admin client |

Aggregated into `/actuator/health` by Spring Boot's default
aggregator + exposed to Kubernetes via
`/actuator/health/readiness` so a Quickwit outage flips readiness
to `NOT_READY` and lifts indexer pods out of the gateway routing
pool.

---

## 5. Port + Eureka coordinates

| Field                | Value                                                                       |
|----------------------|-----------------------------------------------------------------------------|
| Port                 | `:8097` (LD92: next free after `:8090` gateway, `:8092` ingest, `:8093` echo, `:8094` WireMock, `:8095` processor, `:8096` remediation) |
| Eureka application-id| `log-indexer-service`                                                       |
| Eureka registry      | `http://localhost:8761/eureka/` (local-dev default)                         |

---

## 6. What the writer side (P5.3) must keep doing

- Keep streaming documents to the per-tenant Quickwit index via
  `QuickwitSink` per ADR-0030. The writer assumes the index
  exists.
- DO NOT take a dependency on `io.cortex.indexer.admin..`. The
  writer should never call `ensureIndex`; it should fail the
  document publish if the index is missing and let the
  indexer's admin surface heal the gap on the next admin call.
- DO NOT call `POST /api/v1/indexes` or `DELETE
  /api/v1/indexes/<id>` from the writer side. The admin
  surface is OWNED by the indexer.

---

## 7. What the admin side (P7.0..P7.4) must do

- Own `POST /api/v1/indexes` (via P7.1 `QuickwitHttpIndexAdmin`).
- Own `DELETE /api/v1/indexes/<id>` (via P7.1 `QuickwitHttpIndexAdmin`
  + P7.2 retention sweeper).
- Own per-tenant cardinality budget enforcement on `ensureIndex`
  (P7.3 -- reject when the tenant is over budget with
  `OUTCOME_PERMANENT_FAILURE` + reason `tenant:over-budget`).
- Own tenant-scoped query routing against the Quickwit search
  API (P7.4 search proxy).
- DO NOT take a dependency on `io.cortex.processor.sink..`. The
  admin side is not aware of the writer side at source level.

---

## 8. Open items for P7 (not blockers for handoff)

- The full Quickwit doc-mapping JSON template per
  `docMappingVersion` is a P7.1 concern. P7.0 carries only the
  schema-version label.
- Per-tenant cardinality budget defaults (e.g. 100 indexes per
  tenant) are a P7.3 config decision; ADR-0038 leaves the value
  to the implementer.
- Retention window defaults (e.g. 30 days hot, then drop split)
  are a P7.2 config decision.
- The P7.4 search proxy's REST surface (`POST /api/v1/search`
  vs GraphQL `query { search(...) }`) will be settled in its
  own ADR.

---

## 9. References

- **ADR-0038** -- `docs/adr/0038-log-indexer-service-quickwit-admin.md`
  -- this scaffold's design, 8 decision drivers, 7 considered
  options.
- **ADR-0030** -- `docs/adr/0030-loki-quickwit-fanout-sinks.md`
  -- the writer-side `QuickwitSink` ADR that this doc carves
  the boundary against.
- **ADR-0032** -- `docs/adr/0032-log-remediation-dispatcher.md`
  -- the SPI-shape precedent (one-tier-up). ADR-0038
  intentionally mirrors single-method + single-verdict +
  binder-gate + default-noop.
- **LD3** (`memory.md`) -- the indexer is the Quickwit owner.
- **LD92** (`memory.md`) -- port allocation up to `:8097`.
- **LD104** (`memory.md`) -- closer-pattern (scaffold phase =
  Leg A only).
- **LD106 + LD112** (`memory.md`) -- counter bootstrap-
  registration + `unknown` placeholder for null tenants.
- **P7.0 PR** -- ship of `log-indexer-service` scaffold +
  `QuickwitIndexAdmin` SPI (this PR).
