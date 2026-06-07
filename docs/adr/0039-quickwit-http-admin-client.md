# ADR-0039: log-indexer-service P7.1 -- Real Quickwit HTTP admin client

* Status: Accepted
* Date: 2026-06-07
* Deciders: CORTEX maintainers
* Tracking PR: PR for #100
* Supersedes: --
* Superseded by: --

## Context

P7.0 (ADR-0038) carved the `QuickwitIndexAdmin` SPI behind the
`cortex.indexer.admin.backend` property, with a default
`NoopQuickwitIndexAdmin` (gated `matchIfMissing=true`) so the
scaffold module booted green with zero Quickwit dependency.
That was a deliberate scaffold-only landing per the LD104
scaffold-phase precedent (P3.0 + P6.0); the SPI was wired but
no real backend existed.

P7.1 lands the FIRST real backend impl: a Quickwit HTTP admin
client that talks to the Quickwit REST surface
(`POST /api/v1/indexes`, `GET /api/v1/indexes/<indexId>`,
`DELETE /api/v1/indexes/<indexId>`). This is the gate to every
follow-up phase -- P7.2 retention sweeper, P7.3 cardinality
budgets, and P7.4 search proxy all consume `QuickwitIndexAdmin`.

The P5.3 writer side (ADR-0030) already talks to Quickwit via
`POST /api/v1/<index>/ingest` from the processor module. The
two services share the same Quickwit cluster but the ownership
boundary locked in ADR-0038 D1 keeps the writer in
`log-processor-service` and the admin lifecycle in
`log-indexer-service`. P7.1 is the first lifecycle adapter.

## Decision drivers

* **D1.** Reuse the P7.0 SPI seam exactly as-is -- the real
  impl ships as a new bean alongside the noop, with the same
  `@ConditionalOnProperty` gate.
* **D2.** Match the P5.3 + P6.x HTTP shape (HTTP/1.1 pin, dual
  connect+read timeout, transport-exception classification)
  so operators see one wire-format across both services.
* **D3.** Make outcome classification reusable -- the next
  backend (hypothetical P9.x OpenSearch) should be one
  template parameter away.
* **D4.** Pick an idempotency strategy for `ensureIndex` that
  doesn't depend on parsing Quickwit error bodies.
* **D5.** Pick an idempotency strategy for `dropIndex` that
  honours the SPI contract from ADR-0038 D5.
* **D6.** Generate a Quickwit `IndexConfig` body whose
  doc-mapping matches what the P5.3 writer actually sends, so
  the indexer's create call produces a working index.
* **D7.** Honour the SPI no-throw contract -- every error
  path returns an `IndexAdminResult` with the appropriate
  outcome, never propagates.

## Decision

**Ship `QuickwitHttpAdmin` as a new bean gated
`cortex.indexer.admin.backend=quickwit`. Reuse the P6.0a
`RestDispatchTemplate` composition pattern as a mirror
`RestAdminTemplate` for outcome classification. `ensureIndex`
is GET-then-POST; `dropIndex` is DELETE-and-classify-404-as-success.
Body shape is a static schema mirroring the P5.3 `QuickwitSink`
doc fields.**

### Production code surface (new)

```
log-indexer-service/src/main/java/io/cortex/indexer/admin/quickwit/
  package-info.java
  QuickwitProperties.java        # @ConfigurationProperties(prefix="cortex.indexer.quickwit")
  QuickwitHttpConfig.java        # @Configuration; publishes the LD42+LD121 RestClient bean
  RestAdminTemplate.java         # package-private classify{Http,Transport,Unknown}
  QuickwitHttpAdmin.java         # @Component; implements QuickwitIndexAdmin
log-indexer-service/src/main/java/io/cortex/indexer/constants/
  package-info.java
  IndexerHttp.java               # TOO_MANY_REQUESTS=429, SERVER_ERROR_FLOOR=500, NOT_FOUND=404
```

### Outcome table (D3)

| Status / event                           | `IndexAdminResult.outcome()`  | `reason()` shape          |
|------------------------------------------|-------------------------------|---------------------------|
| 2xx on GET (existence probe)             | `exists`                      | `""`                      |
| 2xx on POST (create)                     | `created`                     | `""`                      |
| 2xx on DELETE                            | `dropped`                     | `""`                      |
| 404 on DELETE (idempotent)               | `dropped`                     | `""`                      |
| 404 on GET                               | (control flow -> POST create) | --                        |
| 429                                      | `transient_failure`           | `quickwit:429`            |
| 5xx (>= 500)                             | `transient_failure`           | `quickwit:5xx:<status>`   |
| other 4xx                                | `permanent_failure`           | `quickwit:4xx:<status>`   |
| `ResourceAccessException` w/ timeout cause | `transient_failure`         | `quickwit:timeout`        |
| other `ResourceAccessException`          | `transient_failure`           | `quickwit:transport`      |
| unexpected `RuntimeException`            | `transient_failure`           | `quickwit:unknown`        |
| null `IndexSpec` to `ensureIndex`        | `permanent_failure`           | `quickwit:null-spec`      |
| null/blank `indexId` to `dropIndex`      | `permanent_failure`           | `quickwit:null-index-id`  |

### `ensureIndex` flow (D4)

Two-step (GET then POST) rather than POST-and-classify-409-as-exists:

```
GET /api/v1/indexes/<indexId>
  200 -> return IndexAdminResult.exists(BACKEND_QUICKWIT)
  404 -> POST /api/v1/indexes (body = Quickwit IndexConfig)
            200 -> return IndexAdminResult.created(BACKEND_QUICKWIT)
            non-2xx -> RestAdminTemplate.classify*
  other non-2xx -> RestAdminTemplate.classify*
```

The Quickwit create surface returns HTTP 400 with an
`IndexAlreadyExists` body for the duplicate case rather than a
stable 409. Parsing the error body to discriminate "already
exists" from "bad mapping" is fragile (the body format is
unstable across Quickwit releases) and would couple the
adapter to a specific Quickwit version. GET-then-POST is
two round-trips on the cold path (first-ever ensure) but one
round-trip on the steady-state path (every subsequent ensure
hits the GET 200 short-circuit).

### `dropIndex` flow (D5)

```
DELETE /api/v1/indexes/<indexId>
  200 -> return IndexAdminResult.dropped(BACKEND_QUICKWIT)
  404 -> return IndexAdminResult.dropped(BACKEND_QUICKWIT)   # idempotent per ADR-0038 D5
  non-2xx -> RestAdminTemplate.classify*
```

The SPI contract from ADR-0038 D5 requires `dropIndex` to be
idempotent: dropping an absent index is a successful no-op,
not a permanent failure. 404 maps to `dropped`.

### `IndexConfig` body (D6)

Static schema (version `0.7`) mirroring the P5.3
`QuickwitSink.renderDoc` field set:

```json
{
  "version": "0.7",
  "index_id": "<spec.indexId>",
  "doc_mapping": {
    "field_mappings": [
      {"name": "id", "type": "text", "tokenizer": "raw"},
      {"name": "tenant_id", "type": "text", "tokenizer": "raw"},
      {"name": "event_id", "type": "text", "tokenizer": "raw"},
      {"name": "ts", "type": "datetime", "fast": true,
       "input_formats": ["rfc3339"], "output_format": "rfc3339"},
      {"name": "level", "type": "text"},
      {"name": "service", "type": "text", "tokenizer": "raw"},
      {"name": "message", "type": "text"},
      {"name": "anomaly", "type": "bool", "fast": true},
      {"name": "severity", "type": "text", "tokenizer": "raw"},
      {"name": "reason", "type": "text"}
    ],
    "timestamp_field": "ts"
  },
  "search_settings": {
    "default_search_fields": ["message", "service", "reason"]
  }
}
```

When the doc-mapping needs to evolve, the
`docMappingVersion` field of `IndexSpec` (carried into the
`index_id` per ADR-0038 D2 as `cortex-<tenantId>-<v>`) lets
the two schemas coexist as separate Quickwit indexes. P7.4's
search proxy will fan-out across all live versions for a
tenant.

### HTTP client (D2)

Same shape as the P5.3 `QuickwitSink` + every P6 dispatcher:
`JdkClientHttpRequestFactory` with
`HttpClient.newBuilder().version(HTTP_1_1).connectTimeout(...).build()`
plus `factory.setReadTimeout(...)` (LD42 + LD121 dual-timeout).
The `RestClient.baseUrl()` is set to
`QuickwitProperties.baseUrl()` so call sites can use path-only
URIs.

### Bean activation (D1)

```
NoopQuickwitIndexAdmin    : @ConditionalOnProperty(backend="noop", matchIfMissing=true)
QuickwitHttpAdmin         : @ConditionalOnProperty(backend="quickwit")
```

Mutually exclusive: when `backend=quickwit` the noop is
disabled (its `havingValue=noop` does not match), and only
the new bean is active. The OCP bootstrap loop in
`IndexerMetrics` (ADR-0038 D3) picks up the new bean's
`backendId()=quickwit` automatically -- zero edits in
`IndexerMetrics`.

### SPI no-throw contract (D7)

Every catch arm in `QuickwitHttpAdmin` returns an
`IndexAdminResult`; no exception leaves the adapter. Unchecked
`RuntimeException` falls through to
`template.classifyUnknown(ex)` -> `transient_failure /
quickwit:unknown` so the caller (P7.2 retention sweeper) can
log + retry without a try/catch.

## Consequences

### Positive

* P7.0's SPI shape is proven by the first real backend
  landing behind it with zero SPI edits -- OCP/LSP works.
* Every adapter (P5.3 + P6 + P7.1) reaches Quickwit with the
  same wire-format shape (HTTP/1.1, dual timeout). Operators
  see one transport pattern across two services.
* `RestAdminTemplate` is reusable: the next backend (e.g. a
  hypothetical OpenSearch admin in P9.x) plugs in by passing
  its own backend id to the same classification helpers.
* `ensureIndex` steady-state cost is ONE round-trip (GET 200)
  per call. Only the cold-start path pays the two round-trip
  GET-then-POST cost.
* `dropIndex` is idempotent per the SPI contract; the P7.2
  retention sweeper can call it without pre-checking
  existence.
* The Micrometer counter family bootstrapped at P7.0 picks up
  the new backend immediately because the metric tag set
  already pre-registers all 5 outcomes for every admin in
  the `List<QuickwitIndexAdmin>`.

### Negative

* Cold-start ensure cost is TWO Quickwit round-trips. That's
  fine for the indexer's expected QPS (admin calls are rare;
  the writer side handles all data-path traffic).
* The Quickwit IndexConfig schema is hard-coded at version
  `0.7`. If the Quickwit team ships a 0.8 schema with a
  breaking shape change, this adapter needs an update. The
  `QUICKWIT_INDEX_CONFIG_VERSION` constant pins it.
* The doc-mapping field set is duplicated between P5.3
  `QuickwitSink.renderDoc` and P7.1 `renderCreateBody`. The
  duplication is intentional (no shared module) because LD3
  forbids cross-service code sharing for the data path.
  Future drift between the two would be caught by the P7.1a
  closer cross-phase IT (Testcontainers Quickwit boots, P5.3
  writes, P7.1 reads back).

### Neutral

* WireMock 3.9.2 (parent-managed `${wiremock.version}`)
  enters the indexer test scope. Same dep already present in
  log-remediation-service + log-gateway.
* ArchUnit layered contract gains the `Admin -> Metrics`
  edge (was App-only at P7.0). Comment in the test file
  documents the P7.1 update.

## Alternatives considered

### 1. Reactive WebClient instead of `RestClient`

REJECTED. The whole cluster is HTTP/1.1 pinned via
`JdkClientHttpRequestFactory` (LD42); adding a Netty
reactive client just for admin calls would split the wire
posture. Admin QPS is low; back-pressure is a non-issue.

### 2. Spring Boot auto-config bean discovery

REJECTED. The current `@ConditionalOnProperty` gate is one
line per bean and works fine. An auto-configuration class
would add a `META-INF/spring/...imports` entry + bean
discovery indirection for zero benefit at two impls (noop +
quickwit). Revisit at three+ impls.

### 3. Throw-on-failure semantics, retry in the caller

REJECTED. The ADR-0038 D6 SPI contract explicitly says
admins MUST NOT throw -- the caller decides retry policy
from the outcome verdict. Throwing would break P7.2's
retention sweeper which expects to iterate over all tenants
without try/catch wrapping every call.

### 4. POST-and-classify-409-as-exists `ensureIndex`

REJECTED. Quickwit returns HTTP 400 with an `IndexAlreadyExists`
body for the duplicate case, not a stable 409. Parsing the
error body to discriminate "already exists" from "bad
mapping" would couple us to a specific Quickwit version's
body format. GET-then-POST is robust and the cold-path cost
is acceptable.

### 5. Native Quickwit Java SDK

REJECTED. No official Java SDK exists at Quickwit 0.7. A
generated OpenAPI client would add a code-gen step + a
generated-sources directory + a maintenance burden for an
adapter that only needs three REST calls. Hand-rolled
`RestClient` is the simplest thing that works.

## References

* ADR-0030 (P5.3 `QuickwitSink` writer side, body schema
  source of truth).
* ADR-0036 (P6.0a `RestDispatchTemplate` composition
  pattern; mirrored by `RestAdminTemplate`).
* ADR-0038 (P7.0 `QuickwitIndexAdmin` SPI seam).
* PR #99 (P7.0 scaffold merge commit).
* LD42, LD120, LD121, LD123, LD104.
* Quickwit REST API: https://quickwit.io/docs/0.7/reference/rest-api
