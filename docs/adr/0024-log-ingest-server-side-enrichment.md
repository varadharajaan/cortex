# 0024 - log-ingest server-side enrichment (correlation, tenant from JWT, label normalize, geo stub)

Status: Accepted
Date: 2026-06-01
Deciders: CORTEX core engineering
Tags: P4.3, ingest, enrichment, correlation, jwt, labels, geo

## Context

P4.1 made `log-ingest-service` durable; P4.2 made it idempotent and
PII-safe (ADR-0022, ADR-0023). P4.3 closes the four remaining gaps
the spec (Sec 5.3 "Ingestion service" and Sec 6.2 "Trace
correlation") opens between "accept a batch" and "fan it out to the
downstream queue":

- **Correlation id propagation**: every `RawLog` row MUST carry the
  caller's request id so a single line in `raw_logs` is joinable to
  the gateway access-log entry that produced it.
- **Tenant id from JWT**: the resolved tenant MUST be the
  authoritative `tid` claim from the service-to-service JWT issued
  by `log-gateway` (P3.x) when one is present. The `X-Tenant-Id`
  header remains a dev-time fallback for unit smokes but MUST be
  rejected as the source of truth once a JWT is present.
- **Label normalization**: clients vary on label casing (`Env` vs
  `env`) and whitespace (`"  region  "`); the cold-path UNIQUE on
  `(tenant_id, event_id)` and any downstream cardinality SLO would
  blow up if those variants persisted as distinct keys.
- **Geographic stub**: every row MUST carry a `geo_country` label
  so dashboards have a non-null pivot from day one, even before
  real GeoIP lookup lands in P5.

The decisions below capture the locked shape of each piece and the
rejected alternatives so future readers do not relitigate them.

## Decision drivers

- **D1. One correlation id, multiple inbound names.** The agent
  sends `X-Request-Id`; the gateway emits `X-Correlation-Id`; some
  third-party producers send neither. The service MUST accept the
  first that is present, fall back to a freshly minted `UUID.v4`,
  and ALWAYS surface the resolved value as the `trace_id` label on
  the persisted row.
- **D2. JWT is authoritative; header is a fallback.** When a
  service JWT carries a `tid` claim, that value WINS over any
  `X-Tenant-Id` header (which is then ignored, not error-ed,
  because shared agents may set both). When NO JWT is present,
  `X-Tenant-Id` is required (the dev-time path).
- **D3. Signature verification is OUT OF SCOPE.** P4.3 only reads
  the `tid` claim from the JWT payload. Cryptographic verification
  is the gateway's job (P3.x already does it on the outer hop) and
  the dedicated ingest-side verifier lands in P5.x / B7.1 once the
  KMS-backed signing key rotation contract is finalised.
- **D4. Label normalization is forward-only.** Inbound label keys
  are lower-cased (Locale.ROOT) and trimmed; blank values are
  dropped; collisions are resolved last-write-wins via an ordered
  map. This is irreversible from the server side, but the agent's
  source code is the only producer in P4 and it never relies on
  case in its own labels.
- **D5. Server-stamped labels are EXCLUDED from the event-id
  hash.** `tenant`, `trace_id`, and `geo_country` MUST NOT
  participate in the SHA-256 pre-image, or every retry would mint
  a fresh `trace_id` and dedupe would break. The hash uses ONLY
  the normalized INBOUND labels.
- **D6. Geo is a stub with a real label.** A real GeoIP lookup is
  P5 work; for P4.3 the `geo_country` label is always
  `"unknown"` (overridable via `cortex.ingest.enrichment.geo.fixed-country`).
  Having the LABEL present from day one means downstream
  consumers can write queries against `geo_country` without a
  schema migration when the real lookup lands.

## Considered options

### Option A - Single `EnrichmentService` orchestrating normalize + stamp (CHOSEN)

- One Spring bean (`EnrichmentService`) is the entry point.
  Internally it calls `LabelNormalizer.normalize(...)` first, then
  the small per-stamp enrichers (`GeoEnricher` today, future
  `AnonIpEnricher` / `RegionEnricher` later).
- Keeps the `IngestServiceImpl.persistBatchWithMasking(...)` loop
  short (Checkstyle 30-line method ceiling) and makes future
  enrichers a trivial add: implement, inject, call.

### Option B - Inline normalization + stamping in `IngestServiceImpl`

- Pros: zero indirection.
- Cons: blows the 30-line method ceiling immediately, mixes "what
  the service does" with "how labels get cleaned", and makes
  per-enricher unit tests impossible without a service-level
  Spring slice.

### Option C - Servlet filter that normalises the request body

- Pros: enrichment happens before the controller even sees the
  request.
- Cons: deserializes JSON twice (once in the filter, once in the
  controller). Filters are intentionally reserved for headers
  (`CorrelationIdFilter`) and tenant resolution
  (`TenantResolver`); body mutation is the service's job.

### Option D - Verify the JWT signature in ingest (Option REJECTED)

- Pros: defense in depth.
- Cons: duplicates the gateway's responsibility, requires the
  same KMS-backed key the gateway uses, and bloats the ingest
  startup with a JWK fetcher. Deferred to P5.x / B7.1 where it
  belongs.

### Option E - Validate that JWT and `X-Tenant-Id` agree (Option REJECTED)

- Pros: catches misconfigured agents that set the wrong header.
- Cons: the header is intentionally OPTIONAL once a JWT is
  present; rejecting a mismatch would break the dev-time path
  where agents set both as belt-and-braces. The decision in D2
  ("JWT wins, header ignored") is simpler and surfaces the same
  bug via the row's `tenant` label not matching the operator's
  expectation.

### Option F - Use the entry's own `labels.trace_id` if present

- Pros: lets a client pre-stamp its trace id.
- Cons: that field can be forged (the agent does not sign label
  values) and the service's correlation header has stronger
  provenance (the gateway adds it). Server-stamped trace_id wins
  unconditionally; any client-supplied `trace_id` label is
  overwritten.

### Option G - Real GeoIP lookup at P4.3 (Option REJECTED)

- Pros: end-to-end value sooner.
- Cons: needs a MaxMind DB, a refresh cadence, and a privacy
  review. Out of scope for P4.3; the stub label keeps the
  downstream contract stable while the real lookup is built
  separately in P5.

## Decision

- Add `EnrichmentService` (`@Service`) as the single orchestrator
  invoked once per `LogEntry` from `IngestServiceImpl.persistBatchWithMasking`.
- Pipeline order, exact:
  1. `LabelNormalizer.normalize(entry.labels())` - lowercase keys,
     trim values, drop blanks, last-write-wins.
  2. Compute `event_id` against the NORMALIZED inbound labels only
     (D5). Server stamps from step 3 are NOT in the SHA-256
     pre-image.
  3. Overwrite (or insert) the server-stamped labels in the
     normalised map:
     - `tenant`       = resolved tenant id
     - `trace_id`     = resolved correlation id
     - `geo_country`  = `GeoEnricher.resolveCountry()` (P4.3 stub
       returns the property value, default `"unknown"`)
  4. Persist the enriched map into `raw_logs.labels` (JSONB).
- `ServiceJwtClaimExtractor.extractTenantId(jwtHeader)` parses the
  middle segment via `Base64.getUrlDecoder()` and returns
  `Optional<String>` for the `tid` claim. NO signature
  verification is performed (D3).
- `TenantResolver.resolve(jwtHeader, tenantHeader)` returns the
  JWT-derived tenant id when present (D2), the header when not,
  and throws the existing `IllegalArgumentException` (mapped to
  RFC 7807 `VALIDATION_FAILED` 400) when neither is present.
- `CorrelationIdFilter` resolves the correlation id from
  `X-Request-Id` OR `X-Correlation-Id` (in that order) OR mints a
  fresh `UUID.v4`, then publishes it on the request as the
  `CorrelationIdFilter.ATTRIBUTE_CORRELATION_ID` request
  attribute AND on MDC as `traceId` (existing behaviour preserved).
- `IngestController` reads the correlation id off the request
  attribute and forwards it as a fourth argument to
  `IngestService.acceptBatch(request, tenantId, idempotencyKey,
  correlationId)`.
- `EnrichmentProperties` (`@ConfigurationProperties(
  "cortex.ingest.enrichment")`, record) exposes the
  geo stub default; `application.yml` wires
  `cortex.ingest.enrichment.geo.fixed-country:
  ${CORTEX_INGEST_GEO_FIXED_COUNTRY:unknown}` so the env-var
  override is consistent with the LD43 convention used elsewhere.

### Persisted label contract (per row)

```
{
  "tenant":      "<resolved-tenant-id>",   # always present
  "trace_id":    "<resolved-correlation>", # always present
  "geo_country": "unknown",                # always present in P4.3
  "<key>":       "<value>",                # normalised inbound labels
  ...
}
```

Server-stamped keys are RESERVED; an inbound label with the same
key is overwritten without warning. This is documented behaviour
(D2 / D5).

## Consequences

### Positive

- Every persisted row is joinable to a gateway access-log line by
  `trace_id` from day one.
- Authoritative tenant id is read from the verified-at-gateway
  JWT, eliminating the "client lied in `X-Tenant-Id`" failure
  mode in any environment that has a gateway in front.
- Downstream consumers can pivot on `geo_country` without a schema
  migration when real GeoIP lookup lands in P5.
- Label cardinality is bounded -- two clients sending `Env` and
  `env` collapse to the same key, preventing the dashboard fan-out
  surprise.
- Dedupe survives clients that vary only in label casing or
  whitespace (D4 + D5).

### Negative

- The ingest service now silently ignores a forged `X-Tenant-Id`
  when a JWT is present (Option E rejected). Operators must
  understand the precedence rule when diagnosing
  `tenant`-label-vs-header mismatches; this is called out in the
  P4.3 ADR + the Postman README matrix.
- Server-stamped `trace_id` overrides any client-supplied
  `trace_id` label (Option F rejected). This is intentional but
  could surprise a client that pre-stamps the same label name.
- `EnrichmentService` is a small new bean to maintain. Mitigated
  by the unit tests under `enrichment/` and by the fact that
  every enricher follows the same `LABEL_*` constant + single
  method pattern as `GeoEnricher`.

### Neutral

- Signature verification is now formally deferred to P5.x / B7.1
  (D3). Until then, the gateway's outer verification is the only
  signature check; ingest trusts the gateway's word.
- Geo lookup is a stub; the `geo_country` label is always
  `"unknown"` until P5 swaps in the real implementation behind
  the same `GeoEnricher` API.

## References

- Spec Sec 5.3 "Ingestion service", Sec 6.2 "Trace correlation",
  Sec 7.1 "Multi-tenant routing".
- ADR-0022 (persistence; event_id contract).
- ADR-0023 (hot-path dedupe + PII masking; D4 "mask before persist,
  hash before mask").
- plan.md row P4.3 ("Server-side enrich").
- memory.md "### P4 PLAN RATIFIED -- 2026-05-31" (enrichment list).
- log-ingest-service/src/main/java/io/cortex/ingest/enrichment/EnrichmentService.java
- log-ingest-service/src/main/java/io/cortex/ingest/enrichment/LabelNormalizer.java
- log-ingest-service/src/main/java/io/cortex/ingest/enrichment/GeoEnricher.java
- log-ingest-service/src/main/java/io/cortex/ingest/enrichment/EnrichmentProperties.java
- log-ingest-service/src/main/java/io/cortex/ingest/security/ServiceJwtClaimExtractor.java
- log-ingest-service/src/main/java/io/cortex/ingest/tenant/TenantResolver.java
- log-ingest-service/src/main/java/io/cortex/ingest/filter/CorrelationIdFilter.java
- log-ingest-service/src/main/java/io/cortex/ingest/service/impl/IngestServiceImpl.java
