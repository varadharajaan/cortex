# ADR-0042: log-indexer-service tenant-scoped Quickwit search proxy via `LogSearchClient` SPI

* Status: Accepted
* Date: 2026-06-07
* Deciders: cortex agent + project owner
* Phase: P7.4
* Tracking issue: #107

## Context

P7.0 (ADR-0038) carved the `QuickwitIndexAdmin` SPI for the
**admin-side** of the indexer-service. P7.1 (ADR-0039) shipped the
real `QuickwitHttpAdmin` adapter against the index lifecycle
endpoints (`POST/GET/DELETE /api/v1/indexes`). P7.2 (ADR-0040)
added `applyRetention` via the Quickwit Delete API. P7.3
(ADR-0041) added per-tenant cardinality budgets on `ensureIndex`.

The operator can now create, drop, retention-sweep, and budget-cap
tenant indexes — but the service still has **no read path**. The
roadmap in `log-indexer-service/README.md` section 10 calls for a
P7.4 "search proxy + tenant-scoped query routing against the
Quickwit search API". That is what this ADR covers.

The shape we need:

> A new read-side SPI `LogSearchClient` alongside the existing
> write-side `QuickwitIndexAdmin`, with a noop default for dev
> profiles and a real `QuickwitHttpAdmin`-mirroring HTTP adapter
> gated by `cortex.indexer.search.backend=quickwit`. Forwards a
> `SearchRequest{tenantId, indexId, query, maxHits}` to
> `POST /api/v1/{indexId}/search` and returns a bounded
> `SearchResult{backend, outcome, reason, numHits, hits}`
> envelope. Strict client-side tenant-prefix guardrail
> (`indexId` MUST start with `cortex-<tenantId>-`) prevents
> cross-tenant query leaks.

The wider tracking-file context: P7.0 + P7.1 + P7.2 + P7.3 are
SHIPPED on `main`; the noop default still wins on every dev
profile; P7.1a cross-phase closer is deferred and ships after
P7.4 per LD104.

## Decision drivers

* **D1.** **Symmetry with P7.0.** A new SPI `LogSearchClient`
  (read side) mirrors the existing `QuickwitIndexAdmin`
  (write side): one `String backendId()` + one
  `SearchResult search(SearchRequest)` method. The interface
  lives in `io.cortex.indexer.search` so the existing
  `io.cortex.indexer.admin` package is not touched. A noop
  default `NoopLogSearchClient` ships gated
  `matchIfMissing=true` so the scaffold boots green with zero
  Quickwit dependency.
* **D2.** **Bounded request shape.** `SearchRequest(String
  tenantId, String indexId, String query, int maxHits)` is an
  immutable record with compact-ctor null/blank rejection on
  every String field plus `maxHits > 0` rejection. The tenant
  prefix invariant is intentionally NOT enforced in the ctor
  (an adapter that doesn't talk to Quickwit may not care);
  the `QuickwitHttpSearch` adapter is the gatekeeper.
* **D3.** **Tenant-routing guardrail at the adapter, not at
  the controller.** The `QuickwitHttpSearch` adapter rejects
  any request whose `indexId` does not begin with
  `cortex-<tenantId>-` (mirror of the
  `QuickwitHttpAdmin.INDEX_ID_PREFIX="cortex-"` contract).
  This stops a tenant — accidentally or maliciously — from
  querying another tenant's splits even if a future
  controller forgets to validate. Mismatch returns
  `permanent_failure / quickwit:tenant-mismatch` WITHOUT
  contacting Quickwit at all (lowest-cost, fail-closed
  default).
* **D4.** **Wire shape:** `POST /api/v1/{indexId}/search` with
  body `{"query":"<user query>","max_hits":<N>}`; response
  parsed as `{"num_hits":<N>,"hits":[{...}, ...]}`. Mirror of
  the Quickwit 0.7 search API documented at
  <https://quickwit.io/docs/reference/rest-api>. The
  `RestClient` consumed is the SAME `quickwitAdminRestClient`
  bean published by `QuickwitHttpConfig` in P7.1 — HTTP/1.1
  pin (LD42) + dual connect+read timeout (LD121) — so we get
  the production wire posture for free. No new `RestClient`
  bean; no new config property.
* **D5.** **Outcome table** mirrors the
  `QuickwitHttpAdmin` table from ADR-0039 / ADR-0040, with
  one explicit deviation: a **404** on the search API is
  permanent (`quickwit:4xx:404`), NOT idempotent-success
  like `dropIndex`. A missing index at search time is a
  caller-side configuration bug and must surface loudly to
  the operator. Full table:
  * HTTP 200 + parseable body -> `search_ok`.
  * HTTP 429 -> transient `quickwit:429`.
  * HTTP 5xx -> transient `quickwit:5xx:<n>`.
  * Other 4xx (incl. 404) -> permanent `quickwit:4xx:<n>`.
  * `HttpTimeoutException`/`TimeoutException` cause ->
    transient `quickwit:timeout`.
  * Other `ResourceAccessException` -> transient
    `quickwit:transport`.
  * Unexpected `RuntimeException` -> transient
    `quickwit:unknown`.
  * Tenant-mismatch (D3) -> permanent
    `quickwit:tenant-mismatch`.
  * `null` request -> permanent `quickwit:null-request`.
  * JSON-serialise failure on the request body -> transient
    `quickwit:unknown`.
* **D6.** **SPI MUST NOT throw.** Every error path through
  `QuickwitHttpSearch.search()` is funneled into a
  `SearchResult.transientFailure(...)` or
  `SearchResult.permanentFailure(...)` verdict. The contract
  mirrors `QuickwitIndexAdmin` (ADR-0038 D5) and lets future
  callers (controllers, schedulers) chain `LogSearchClient`
  calls without a try/catch wrapper.
* **D7.** **Metrics surface.** A new sibling counter
  `cortex.indexer.search_total{backend, outcome, tenant_id}`
  joins the existing
  `cortex.indexer.index_admin_total{...}` family. Same Part 17
  allowlist (3 tag keys). The bootstrap loop in
  `IndexerMetrics` now injects `List<LogSearchClient>` in
  addition to `List<QuickwitIndexAdmin>` and registers
  `search_ok / transient_failure / permanent_failure` per
  backend's `backendId()` (plus one all-`unknown` placeholder
  per LD106 + LD112) so `/actuator/prometheus` exposes the
  family on the very first scrape. Existing
  `index_admin_total` bootstrap unchanged.

## Decision

Ship a new SPI + value types + the matching Quickwit HTTP adapter
in a new `io.cortex.indexer.search` package.

### `SearchRequest` value type

```java
public record SearchRequest(
        String tenantId,
        String indexId,
        String query,
        int maxHits) {
    public SearchRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (indexId == null || indexId.isBlank()) {
            throw new IllegalArgumentException("indexId must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (maxHits <= 0) {
            throw new IllegalArgumentException(
                "maxHits must be strictly positive; got " + maxHits);
        }
    }
}
```

### `SearchResult` envelope

```java
public record SearchResult(
        String backend,
        String outcome,
        String reason,
        long numHits,
        List<Map<String, Object>> hits) {
    public static final String BACKEND_NOOP = "noop";
    public static final String BACKEND_QUICKWIT = "quickwit";

    public static final String OUTCOME_NOOP = "noop";
    public static final String OUTCOME_SEARCH_OK = "search_ok";
    public static final String OUTCOME_TRANSIENT_FAILURE = "transient_failure";
    public static final String OUTCOME_PERMANENT_FAILURE = "permanent_failure";

    public SearchResult {
        // Defensive copy + null coerce; never throws.
        hits = (hits == null) ? List.of() : List.copyOf(hits);
        if (numHits < 0L) {
            numHits = 0L;
        }
        if (backend == null || backend.isBlank()) {
            backend = BACKEND_NOOP;
        }
        if (outcome == null || outcome.isBlank()) {
            outcome = OUTCOME_NOOP;
        }
        if (reason == null) {
            reason = "";
        }
    }

    public static SearchResult noop(String reason) { ... }
    public static SearchResult searchOk(String backend, long numHits,
                                        List<Map<String, Object>> hits) { ... }
    public static SearchResult transientFailure(String backend, String reason) { ... }
    public static SearchResult permanentFailure(String backend, String reason) { ... }
}
```

The factories never throw and never return `null`. The compact
constructor's defensive `List.copyOf(hits)` means callers cannot
mutate the published list and a future caller adding to the input
list after construction does not leak into the verdict.

### SPI surface

```java
public interface LogSearchClient {
    String backendId();
    SearchResult search(SearchRequest request);
}
```

* `backendId()` returns one of `SearchResult.BACKEND_*`
  (`"noop"` or `"quickwit"`).
* `search(request)` MUST NOT throw. Tenant-mismatch and
  transport failures both surface in the verdict envelope.

### Default `NoopLogSearchClient`

```java
@Component
@ConditionalOnProperty(
        prefix = "cortex.indexer.search",
        name = "backend",
        havingValue = "noop",
        matchIfMissing = true)
public class NoopLogSearchClient implements LogSearchClient {
    @Override public String backendId() { return SearchResult.BACKEND_NOOP; }
    @Override public SearchResult search(SearchRequest request) {
        return SearchResult.noop("noop search backend");
    }
}
```

Default-wins. A profile that wants the real backend sets
`cortex.indexer.search.backend=quickwit`.

### `QuickwitHttpSearch` adapter

```java
@Component
@ConditionalOnProperty(
        prefix = "cortex.indexer.search",
        name = "backend",
        havingValue = "quickwit")
public final class QuickwitHttpSearch implements LogSearchClient {
    static final String SEARCH_PATH = "/api/v1/{indexId}/search";
    static final String INDEX_ID_PREFIX = "cortex-";
    static final String BODY_KEY_QUERY = "query";
    static final String BODY_KEY_MAX_HITS = "max_hits";
    static final String RESPONSE_KEY_NUM_HITS = "num_hits";
    static final String RESPONSE_KEY_HITS = "hits";

    @Autowired public QuickwitHttpSearch(QuickwitProperties properties,
                                         RestClient restClient,
                                         IndexerMetrics metrics,
                                         ObjectMapper mapper) { ... }
}
```

Outbound HTTP shares the `quickwitAdminRestClient` bean from
P7.1 (`QuickwitHttpConfig`). No new bean.

### Adapter flow (mirror of ADR-0039 D3)

```
request == null                              -> permanent / quickwit:null-request
indexId NOT startsWith "cortex-<tenantId>-"  -> permanent / quickwit:tenant-mismatch
                                              (no Quickwit call)
JSON serialise of request body fails         -> transient / quickwit:unknown

POST /api/v1/{indexId}/search
  body {"query": "...", "max_hits": N}
  200          -> parse body -> search_ok(numHits, hits)
                  (null/blank body -> search_ok(0L, [])
                   parse failure  -> transient / quickwit:unknown)
  429          -> transient  / quickwit:429
  5xx          -> transient  / quickwit:5xx:<n>
  other 4xx    -> permanent  / quickwit:4xx:<n>    (incl. 404)
  HttpTimeout  -> transient  / quickwit:timeout
  transport    -> transient  / quickwit:transport
  RuntimeEx    -> transient  / quickwit:unknown
```

Every terminal point ticks
`metrics.incSearch(result.backend(), result.outcome(), tenantId)`.

### `IndexerMetrics` bootstrap

```java
@PostConstruct
void bootstrapMeters() {
    bootstrap(UNKNOWN, UNKNOWN);
    for (final QuickwitIndexAdmin admin : this.admins) {
        // ... existing admin bootstrap loop unchanged ...
    }
    bootstrapSearch(UNKNOWN, UNKNOWN);
    for (final LogSearchClient client : this.searchClients) {
        final String backend = client.backendId();
        bootstrapSearch(backend, SearchResult.OUTCOME_SEARCH_OK);
        bootstrapSearch(backend, SearchResult.OUTCOME_TRANSIENT_FAILURE);
        bootstrapSearch(backend, SearchResult.OUTCOME_PERMANENT_FAILURE);
    }
}
```

Constructor accepts `List<LogSearchClient> searchClients` in
addition to the existing `List<QuickwitIndexAdmin> admins`. OCP
holds — adding a future Loki/Postgres/Elasticsearch search
backend requires zero edits to `IndexerMetrics`.

### `ArchUnit` layered contract

A new `Search` layer joins the existing `App / Admin / Metrics /
Health`. `Admin` and `Search` are siblings and do not reference
each other. `Metrics` may be reached from `App, Admin, Search`.

## Consequences

### Positive

* The read path lands behind the SAME SPI shape as the write
  path: a future Loki / Postgres / Elasticsearch / hybrid
  backend ships as one new `@ConditionalOnProperty` adapter
  with zero edits to existing code (OCP holds at the SPI and
  the metrics bootstrap).
* Tenant-mismatch is a client-side, no-network rejection —
  the lowest-cost fail-closed default. A future controller
  forgetting to validate the input cannot leak across
  tenants.
* The wire posture (HTTP/1.1 pin + dual timeout) is inherited
  from the P7.1 `quickwitAdminRestClient` bean for free.
* The Prometheus surface gains exactly one new counter
  family with the SAME tag allowlist; existing
  `index_admin_total` panels continue to work, and a new
  search panel is a copy-paste away.
* The compact-ctor defensive `List.copyOf(hits)` on
  `SearchResult` makes the envelope safe to publish across
  thread boundaries without further synchronisation.

### Negative

* Two SPI registries on `IndexerMetrics` (`List<QuickwitIndexAdmin>`
  + `List<LogSearchClient>`) means the constructor parameter
  list grew from 2 to 3. Within the Checkstyle
  `ParameterNumber` ceiling of 6 (LD74), but worth noting that
  the next collaborator would push us to a facade.
* The tenant-prefix invariant is enforced at the adapter, not
  at the value type. A test or future caller that constructs
  a `SearchRequest("tenant-A", "cortex-tenant-B-v1", ...)` is
  syntactically valid; only the adapter's `search()` call will
  reject it. This is intentional (D3) but a non-Quickwit
  adapter would have to enforce its own equivalent.
* The 404-is-permanent decision means an operator who deletes
  an index out-of-band gets a noisy
  `permanent_failure / quickwit:4xx:404` series. This is the
  correct behaviour (don't paper over config errors) but
  diverges from the `dropIndex` 404-is-success contract; the
  ADR-0040 D4 deviation pattern repeats here.

### Neutral

* No new Maven dependency. Reuses
  `QuickwitProperties` / `QuickwitHttpConfig` /
  `IndexerMetrics` from P7.1.
* New package `io.cortex.indexer.search`; existing
  `io.cortex.indexer.admin` is untouched.
* `NoopLogSearchClient` is the default; existing profiles
  see no behaviour change until they flip
  `cortex.indexer.search.backend=quickwit`.

## Alternatives considered

### Rejected: native Quickwit Java SDK

Use the official Quickwit Java client (if/when published) as
the HTTP transport. **Rejected** because: (a) Quickwit 0.7 does
not publish an official Java SDK; (b) the current Rust+REST
posture is the documented, supported integration surface; (c)
the P7.1 `RestClient` + `RestAdminTemplate` stack is already
production-proven on the admin path and shares 100% of the
wire posture (HTTP/1.1 pin, dual timeout, outcome classifier);
(d) adopting an unofficial SDK couples cortex to a third-party
release cadence we do not control.

### Rejected: gRPC search client

Talk to Quickwit over its native gRPC search endpoint instead
of REST. **Rejected** because: (a) the cortex stack is REST-end-
to-end (gateway, ingest, processor, remediation all REST); (b)
the operational tooling (curl, Postman, Newman) is REST-tuned;
(c) the LD42 HTTP/1.1 pin would not carry over (gRPC requires
HTTP/2), and a profile-conditional second transport stack adds
operational drift; (d) the latency budget is comfortable on
REST — Quickwit's REST endpoint adds <5ms vs gRPC at our
expected QPS; (e) gRPC streaming is not needed for the
single-shot search pattern P7.4 ships.

### Rejected: direct passthrough without tenant resolver

Skip the `cortex-<tenantId>-` guardrail; let the caller pick
any `indexId`. **Rejected** because: (a) the cross-tenant
leak risk is real — one missed validation in a future
controller silently exposes another tenant's data; (b) the
guardrail is cheap (one `String.startsWith` call) and runs
BEFORE the Quickwit network call so adds zero observable
latency; (c) the failure mode is **fail-closed** with an
explicit `quickwit:tenant-mismatch` verdict, not silent —
operators can alert on it; (d) the symmetric ADR-0041
client-side filter on cardinality budgets already established
this pattern; (e) Quickwit itself has no per-tenant ACL
mechanism in 0.7, so the client side is the only place to
enforce the invariant.

### Rejected: server-side multi-tenant view

Build a Quickwit "tenant view" feature in Quickwit itself
(metastore-level tenant ACLs). **Rejected** because: (a)
Quickwit 0.7 has no such surface; (b) the cortex side then
has zero observability into rejected cross-tenant queries —
no `cortex.indexer.search_total{outcome=permanent_failure}`
tick, no Grafana wire-up; (c) coupling the cortex tenant
model to a Quickwit server-side construct violates the
"Quickwit is a swappable backend" P7.0 ADR-0038 contract;
(d) ADR-0041 already rejected the same shape for
cardinality budgets.

### Rejected: separate "validation" SPI method

Add a `validate(SearchRequest)` method on `LogSearchClient`
that callers invoke before `search()`. **Rejected** because:
(a) two-call API is a TOCTOU footgun — a caller could pass
validation then cross-tenant by mutating the request; (b)
the adapter's `search()` is already the gatekeeper and
catches every path with a single `String.startsWith` check;
(c) one fewer SPI method is one fewer thing for the noop
default + future adapters to implement; (d) the symmetric
ADR-0041 already rejected the same shape for budgets.

## References

* ADR-0038 — P7.0 `QuickwitIndexAdmin` SPI + per-backend selection contract.
* ADR-0039 — P7.1 `QuickwitHttpAdmin` real HTTP admin client + `RestAdminTemplate`.
* ADR-0040 — P7.2 `applyRetention` via Quickwit Delete API.
* ADR-0041 — P7.3 per-tenant cardinality budgets via `ensureIndex(spec, budget)`.
* ADR-0036 — the `RestDispatchTemplate` composition pattern this work mirrors.
* Quickwit search-API documentation — `POST /api/v1/{indexId}/search`.
* Part 17 — metrics tag allowlist (`backend`, `outcome`, `tenant_id`).
* LD42 — HTTP/1.1 pin via `JdkClientHttpRequestFactory`.
* LD120 — `Fault.CONNECTION_RESET_BY_PEER` for deterministic transport-fault WireMock IT.
* LD121 — dual connect+read timeout on `JdkClientHttpRequestFactory`.
* LD106 + LD112 — Micrometer bootstrap-registration pattern.
* LD104 — scaffold-phase / Leg A only; B/C/D/E deferred to P7.1a closer.

## Amendment 1 — P9.1a tenant-scoped REST search surface

**Date**: 2026-06-08. **Status**: Accepted (extends, does not
supersede, the original P7.4 SPI decisions).

### Context

P7.4 (this ADR) shipped the `LogSearchClient` SPI but deliberately
exposed NO REST surface — the indexer served only actuator endpoints.
P9.1 (`searchLogs` GraphQL query + REST backer) needs a queryable
read surface. Per the P9.1 architecture decision (memory LD147), the
gateway must NOT proxy search via the SCG route table (that would
split the REST and GraphQL code paths and defeat the ADR-0049 parity
contract). Instead the owning service exposes the SPI over REST once,
and the gateway shares a service both surfaces delegate to. P9.1a is
that owning-service REST surface; P9.1b is the gateway parity layer.

### Decision

Add a thin `SearchController` (`io.cortex.indexer.controller`) over
the existing `LogSearchClient` SPI. It owns NO search logic — the
active SPI implementation (noop default, or `QuickwitHttpSearch`
when `cortex.indexer.search.backend=quickwit`) performs the query,
enforces the D3 tenant-prefix guardrail, and ticks
`cortex.indexer.search_total`. The controller's sole job is the HTTP
boundary.

- **Endpoint**: `POST /api/v1/search`, JSON in/out.
- **Tenant source of truth**: the required `X-Tenant-Id` header
  (set by the P9.1b gateway after it authenticates the caller). The
  request BODY carries only `indexId`, `query`, and an optional
  `maxHits` — so a body field can never spoof another tenant. The
  controller builds the domain `SearchRequest(tenantId, indexId,
  query, maxHits)` from header + body.
- **`maxHits`**: nullable in the body; the controller applies a
  server default (50) when omitted and clamps an over-large value to
  a hard ceiling (1000) rather than rejecting it.

### Verdict → HTTP mapping (D8, new)

Because the SPI never throws (D6), the mapping is verdict-driven,
not exception-driven:

| `SearchResult` verdict | HTTP |
|---|---|
| `search_ok` / `noop` | `200` + `{numHits, hits}` body |
| `permanent_failure` reason `quickwit:tenant-mismatch` | `403 Forbidden` |
| `permanent_failure` reason ending `:404` | `404 Not Found` |
| other `permanent_failure` (e.g. `quickwit:4xx:<n>`) | `422 Unprocessable Entity` |
| `transient_failure` reason `quickwit:429` | `429 Too Many Requests` + `Retry-After` |
| other `transient_failure` (`:5xx:<n>` / `:timeout` / `:transport` / `:unknown`) | `503 Service Unavailable` |

Validation failures (blank `indexId`/`query`, missing `X-Tenant-Id`,
malformed body) map to `400 Bad Request` via a `SearchControllerAdvice`
`@RestControllerAdvice`, all as RFC 7807 `ProblemDetail` bodies. The
happy-path response exposes only `numHits` + `hits`; the verdict's
internal `backend`/`outcome`/`reason` are never leaked on the wire
(they surface as the status + problem body on failure).

### Rejected alternatives

1. **`GET /api/v1/logs/search?...` with query params (ADR-0004's
   literal REST shape).** Deferred to the P9.1b GATEWAY surface
   (which faces external clients and matches ADR-0004). The internal
   indexer endpoint is `POST` because the Quickwit query string can
   be long/structured and is cleaner in a JSON body than URL-encoded;
   the gateway translates the external `GET` to this internal `POST`.
2. **Tenant id in the body.** Rejected — a body field is caller-
   controlled and could target another tenant's index; the header is
   set by the trusted gateway after auth. Defence-in-depth: even if a
   spoofed `indexId` slipped through, the SPI's D3 `cortex-<tenantId>-`
   prefix check (keyed off the header tenant) still fails closed.
3. **Mapping every `permanent_failure` to 400.** Rejected — the
   guardrail trip is semantically `403`, a missing index is `404`,
   and a malformed-but-routed query is `422`; collapsing them loses
   operator signal.

### Verification

Leg A: `SearchControllerTest` `@WebMvcTest` slice (14 tests, mocked
SPI, full verdict→status table + validation) + `SearchControllerWireMockIT`
`@SpringBootTest(RANDOM_PORT)` Failsafe IT (4 tests, real
`QuickwitHttpSearch` against a WireMock Quickwit). `mvn verify` GREEN
(Surefire 120, Failsafe 45, Checkstyle 0, SpotBugs 0, JaCoCo met).
ArchUnit `LAYERING` extended with a `Controller` layer. Live smoke +
Postman + Newman deferred to the P9.1b gateway closer per LD104 (the
P7.0..P7.4 precedent — an internal endpoint with no operator-facing
client of its own yet).

