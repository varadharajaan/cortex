# ADR-0049: log-gateway P9.0 GraphQL rollout pattern (schema + nlToLogQL)

- **Status**: Accepted
- **Date**: 2026-06-08
- **Deciders**: @varadharajaan (operator)
- **Tags**: log-gateway, graphql, api-surface, rest-parity, schema-first,
  P9, ADR-0004, LD104

## Context

ADR-0004 commits the project to a **dual-surface** API for read-side
operations:

- **REST** is the primary surface (auth, ingest, all read endpoints
  today).
- **GraphQL** mirrors REST on a fixed list of **four queries** for
  client convenience -- `searchLogs`, `getLogById`, `nlToLogQL`,
  `getAnomalies`. No GraphQL mutations are exposed in any phase
  (ADR-0004 RA5: ingestion stays REST-only; mutations through GraphQL
  are forever rejected to preserve the JWT + rate-limit + idempotency
  posture and to keep the threat surface flat).

Until P9.0 the GraphQL pillar was a contract on paper -- there was no
schema, no resolver, no dependency on the classpath, and `/graphql`
returned `404`. Of the four ADR-0004 queries, only `nlToLogQL` already
had a working REST surface backed by a real service (P3.3 /
ADR-0018 -- `NlQueryController POST /api/v1/query/nl` ->
`NlQueryService.translate(NlQueryRequest, String principalName)`).
The other three queries (`searchLogs`, `getLogById`, `getAnomalies`)
depend on read paths that have not been built yet.

P9.0 is therefore the **GraphQL scaffold** sub-phase: introduce
Spring for GraphQL, ship a minimal but production-shaped schema
covering the **one** query whose REST counterpart already works
(`nlToLogQL`), and lock in the conventions that subsequent P9.x
sub-phases (`searchLogs` etc.) will follow. The remaining queries
ship as their REST backers ship; this ADR fixes the pattern so each
addition is a mechanical extension and not a fresh debate.

Operator-visible default posture: `/graphql` is exposed on the same
port as REST, JWT-gated (any authenticated user), rate-limited by the
existing global Bucket4j filter, GraphiQL **off**, introspection
**off** -- production-safe defaults that can be flipped per
environment via documented `CORTEX_GATEWAY_GRAPHQL_*` env vars.

## Decision

Adopt the following six-part GraphQL rollout pattern across the
`log-gateway` module. Every future `P9.x` sub-phase that adds a new
GraphQL query MUST follow each point unless this ADR is explicitly
amended.

### D1. Bundled Spring for GraphQL via `spring-boot-starter-graphql`

Pull GraphQL in through the Spring Boot 3.3.6 BOM:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-graphql</artifactId>
</dependency>
```

No explicit `<version>` -- the BOM pins it. Boot 3.3.6 ships GraphQL
1.3.x and `graphql-java` 22.x. `spring-graphql-test` is added in
`test` scope for slice testing (`@GraphQlTest`).

Rejected alternatives: pulling `graphql-java-tools` (legacy
schema-parsing library; Boot's auto-config supersedes it), pulling
`spring-boot-starter-webflux` for the `WebFlux` GraphQL transport
(log-gateway is servlet-MVC per ADR-0014 -- adding WebFlux would
introduce a second reactor stack and break MockMvc-only ITs).

### D2. Schema-first under `src/main/resources/graphql/`

GraphQL is **schema-first**, not code-first. The schema lives at
`log-gateway/src/main/resources/graphql/schema.graphqls` (Spring for
GraphQL's default discovery location -- no explicit
`spring.graphql.schema.locations` override).

Each query addition adds a `type Query` field + any new output types
in the same file (until size warrants splitting into multiple
`*.graphqls` files; Spring auto-discovers every `.graphqls` under
`graphql/`).

For P9.0 the schema is:

```graphql
type Query {
  nlToLogQL(prompt: String!): NlQueryResult!
}

type NlQueryResult {
  logql: String!
  confidence: Float!
  explanation: String!
}
```

Field names + types **mirror the REST DTO** so the same client model
serialises identically over both surfaces (D5).

Rejected alternative: code-first via `@SchemaMapping` reflection -- it
hides the contract from non-Java reviewers, breaks linting tooling
(`graphql-config`), and forfeits Boot's free schema-inspection
report that catches unmapped fields at boot.

### D3. One resolver class per query under `io.cortex.gateway.graphql`

Every GraphQL query gets its **own `@Controller` resolver class** in
the new `io.cortex.gateway.graphql` package. Class name is
`<Query>GraphQlController` (P9.0: `NlQueryGraphQlController`).

Resolver method shape:

```java
@QueryMapping
@PreAuthorize("isAuthenticated()")
public NlQueryResponse nlToLogQL(@Argument final String prompt) { ... }
```

The resolver does **zero business logic**: it delegates to the
existing service layer (`NlQueryService.translate(...)`) and surfaces
exactly the same `ApplicationException` the REST controller throws.
The shared service layer is the single source of truth for both
surfaces; the resolver is a thin adapter.

Rejected alternative: one fat `Query` controller with all four
resolver methods -- bigger blast radius, breaks the
`@ConditionalOnProperty` per-query gating that lets us ship each
query independently behind its own feature flag.

### D4. Per-query feature gate via `@ConditionalOnProperty`

Every resolver class carries the same feature gate as its REST
counterpart:

```java
@ConditionalOnProperty(prefix = "cortex.gateway.nl-query",
        name = "enabled", havingValue = "true")
```

When the property is `false` (the default in
`src/main/resources/application.yml` shipped property block AND in
`src/test/resources/application.yml`), Spring does not instantiate
the resolver bean. Spring for GraphQL's schema-inspection report
flags the unmapped query at boot but does not fail -- the query
returns a GraphQL `validation` error at runtime rather than a 500.
This mirrors the REST posture (where the controller is also gated
off) and preserves the LD100 / NL-query OFF-by-default contract.

Rejected alternative: a single `cortex.gateway.graphql.enabled` umbrella
flag -- couples every query's rollout to a single switch and prevents
gradual per-query exposure.

### D5. Security via `anyRequest().authenticated()` -- no per-path rule

The existing `SecurityConfig` filter chain ends with
`.anyRequest().authenticated()`. `/graphql` is **not** added to the
`permitAll()` allowlist; therefore every GraphQL request requires a
valid JWT. The `@PreAuthorize` annotation on each resolver method is
defence-in-depth (catches the case where the filter chain mis-fires)
but the filter is the authoritative gate.

`CSRF` stays disabled globally (REST + GraphQL share the stateless
JSON-API posture per ADR-0015). Spring for GraphQL POSTs are
therefore not blocked.

Rejected alternative: a separate JWT filter chain at `@Order(N)` for
`/graphql` -- two chains is two contracts to keep in sync; one chain
covers both surfaces.

### D6. Rate-limiting via the existing global `RateLimitFilter`

`/graphql` is **not** added to `RateLimitFilter.excludedPaths()`. Every
GraphQL request consumes one global Bucket4j token per JWT subject,
exactly like every REST request. This is the production-safe default
for the scaffold sub-phase.

Per-query feature buckets (the `@RateLimitFeature` annotation
pattern from ADR-0021) do **NOT** fire on GraphQL resolvers in P9.0:
the `RateLimitFeatureInterceptor` scans `HandlerMethod` from the
Spring MVC `HandlerInterceptor` API, and GraphQL resolvers are
invoked through `DataFetcher`, not `HandlerMethod`. The
NL-query-specific sub-bucket (10/min per subject vs the global
100/min) therefore protects the REST surface only.

A follow-up sub-phase **P9.0a** will extend the NL sub-bucket to the
GraphQL surface via a `WebGraphQlInterceptor` that reads
`@RateLimitFeature` off the resolver method. P9.0 explicitly defers
this because it is **not a correctness gap** -- the global bucket
still caps the GraphQL caller; it is only a convenience-vs-REST
parity gap on the NL-specific sub-quota.

Rejected alternative: skip rate-limiting on `/graphql` for P9.0 --
unsafe default; an abusive caller could bypass the global bucket by
issuing the same NL prompt through GraphQL instead of REST.

### D7. Production-safe defaults: GraphiQL off, introspection off

`spring.graphql.graphiql.enabled` and
`spring.graphql.schema.introspection.enabled` both default **`false`**
in shipped `application.yml`. Each is wrapped in an env-var
indirection so per-environment overrides are mechanical:

```yaml
spring:
  graphql:
    path: ${CORTEX_GATEWAY_GRAPHQL_PATH:/graphql}
    schema:
      introspection:
        enabled: ${CORTEX_GATEWAY_GRAPHQL_INTROSPECTION_ENABLED:false}
    graphiql:
      enabled: ${CORTEX_GATEWAY_GRAPHQL_GRAPHIQL_ENABLED:false}
```

Local dev MAY flip both on by exporting the env vars; staging /
prod profile docs explicitly recommend leaving them off so the
schema is not exposed to anonymous probes and the GraphiQL UI does
not become an attractive nuisance behind the JWT wall.

Rejected alternative: leave Spring Boot's defaults
(introspection ON / GraphiQL OFF) -- the wider field of view from
introspection leaks schema shape (mutation absence, field types,
description strings) to any authenticated client without operator
consent.

## Consequences

- **Test ergonomics**: `@GraphQlTest(NlQueryGraphQlController.class)`
  is a Surefire-fast slice -- no full Spring context, no Tomcat. The
  Failsafe closer (`NlQueryRestAndGraphQlParityIT`) under
  `io.cortex.gateway.closer` is the first member of a new closer
  layer in this module; it boots a full
  `@SpringBootTest @AutoConfigureMockMvc`, acquires a real JWT via
  `POST /api/v1/auth/login`, then asserts the REST and GraphQL paths
  produce identical `(logql, confidence, explanation)` triples for
  the same prompt. **This is the parity contract.**

- **ArchUnit boundary** (`ArchitectureRulesTest.LAYERING`): a new
  `Graphql` layer is added with the same posture as `Controller` --
  `mayNotBeAccessedByAnyLayer()`; the `Service` and `Exception`
  allowed-callers lists are extended to permit `Graphql` calls.
  This is identical to the Controller treatment so the GraphQL
  resolver is structurally indistinguishable from a REST controller
  from a dependency-direction standpoint.

- **Schema-inspection report**: Boot logs the
  `Unmapped fields / Unmapped registrations / Unmapped arguments /
  Skipped types` summary at INFO on every boot. As future P9.x
  sub-phases land, any drift between schema and resolver shows up
  in the boot log immediately -- treat this as a first-class
  smoke-test signal.

- **Verification triangle deferral**: per LD104 the boot smoke
  (Leg B) and Newman (Leg C) for P9.0 are **deferred**. The Failsafe
  closer IT (`NlQueryRestAndGraphQlParityIT`) is the proxy for any
  Spring-wiring change inside this sub-phase. P9.0a + later P9.x
  sub-phases that add real endpoints will re-introduce smoke + Newman
  for the full triangle.

- **GraphQL mutations forever rejected**: this ADR reinforces
  ADR-0004 RA5. Any future request to expose a mutation is a fresh
  decision that requires superseding ADR-0004 AND amending this
  ADR. No "small" mutation slips in.

- **Operator runbook**: `log-gateway/README.md` gains a `## GraphQL`
  section documenting endpoint path, default env-var matrix, the
  rate-limiting posture, and a sample query/curl pair so on-call
  has a reproducible shape to test against.

## Alternatives

1. **Skip GraphQL entirely; mirror REST under `/api/v1/graphql/*`
   instead.** Rejected -- ADR-0004 is the project-level decision
   and pre-commits to a real GraphQL surface for the four queries.
   Faking GraphQL via REST defeats the client-side ergonomics that
   motivated ADR-0004 in the first place.

2. **Code-first schema via `@SchemaMapping` reflection.** Rejected --
   see D2.

3. **Per-surface security chain.** Rejected -- see D5.

4. **Skip rate-limiting on `/graphql` for the scaffold.** Rejected --
   see D6 (unsafe default).

5. **Ship the full four-query schema in P9.0 as stub resolvers
   throwing `not-yet-implemented`.** Rejected -- a stub schema leaks
   contract intent to clients that does not yet exist on the REST
   side, and the schema-inspection report would warn on every boot
   masking real future drift.

## References

- ADR-0004 -- REST + GraphQL parity contract (4 queries, no
  mutations).
- ADR-0014 -- log-gateway is Spring Cloud Gateway MVC (servlet) --
  the reason we do NOT pull in WebFlux.
- ADR-0015 -- log-gateway JWT resource server; stateless JSON API
  posture (no CSRF).
- ADR-0018 -- NL->LogQL via Spring AI + Ollama (the existing REST
  backer that P9.0 surfaces over GraphQL).
- ADR-0021 -- `@RateLimitFeature` annotation pattern (deferred
  extension to GraphQL noted in D6).
- memory.md LD42 -- HTTP/1.1 pin (irrelevant here -- GraphQL is on
  the same Tomcat as REST).
- memory.md LD100 -- OFF-by-default `@ConditionalOnProperty` posture.
- memory.md LD104 -- triangle-gate (leg-A-only acceptable for
  scaffold sub-phases).
- memory.md LD131 -- `@Lazy` Metrics ctor pattern (not applicable
  yet; no metrics bean depends on GraphQL resolvers).

## Amendment 1 -- P9.0a `WebGraphQlInterceptor` NL sub-bucket parity

Date: 2026-06-08. Status: shipped. Closes the D6 deferral.

### Change

D6 documented that per-query feature buckets (the `@RateLimitFeature`
annotation pattern from ADR-0021) do NOT fire on GraphQL resolvers
in P9.0 -- the MVC `RateLimitFeatureInterceptor` inspects
`HandlerMethod` which is absent on the GraphQL path. P9.0a closes
that gap with a new
`io.cortex.gateway.interceptor.RateLimitGraphQlInterceptor`
(Spring for GraphQL `WebGraphQlInterceptor`) that:

1. Scans `ApplicationContext.getBeansWithAnnotation(Controller.class)`
   at `@PostConstruct` for methods annotated with both
   `@QueryMapping` and `@RateLimitFeature`, indexed by GraphQL
   field name (the `@QueryMapping.value()` override or, when blank,
   the method name).
2. On every inbound `WebGraphQlRequest`, parses the GraphQL document
   via `graphql.parser.Parser`, walks each `OperationDefinition`'s
   top-level `Field` selections, and consumes one Bucket4j token per
   registered field via the SAME `ProxyManager<String>` bean wired
   into the MVC interceptor.
3. On exhaustion throws `RateLimitedException` (4-arg form) which
   propagates through `Mono.error(...)` ->
   `GraphQlHttpHandler.block()` -> the MVC exception chain into the
   existing `GlobalExceptionHandler.@ExceptionHandler(
   RateLimitedException.class)` -> 429 RFC 7807 body. No new error
   plumbing; the GraphQL surface inherits the REST contract verbatim.

The GraphQL NL resolver
(`NlQueryGraphQlController.nlToLogQL(String)`) now carries the
same `@RateLimitFeature(name="nl-query", capacity="${...:10}",
refill="${...:PT1M}", errorCode="NL_QUERY_RATE_LIMITED",
keyPrefix="${...:cortex:rl:nlq:}")` annotation as the REST
endpoint (`NlQueryController.translate(NlQueryRequest)`). Both
interceptors derive the bucket key as
`keyPrefix + name + ":user:" + auth.getName()` (or
`":ip:" + xff-first-hop` for anonymous callers) -- identical key
shape, identical injected `ProxyManager` bean, therefore a SINGLE
shared bucket per JWT subject across the REST and GraphQL surfaces.
This is the P9.0a parity contract.

### Gating

The new interceptor is annotated `@ConditionalOnProperty(prefix =
"cortex.gateway.rate-limit", name = "enabled", havingValue =
"true")` -- the same switch that gates the MVC
`RateLimitFeatureInterceptor`. With rate-limiting OFF (the LD100
default), the GraphQL interceptor bean is not registered and the
GraphQL request path has no behavioural change versus P9.0.

### Verification triangle (LD104)

- **Leg A (Surefire)** GREEN: `RateLimitGraphQlInterceptorTest`
  exercises 7 scenarios -- unregistered field skip, happy-path token
  consume for authenticated caller, exhaustion path with
  annotation-driven `errorCode` propagation, anonymous caller keyed
  by `X-Forwarded-For` first hop, anonymous caller fallback to
  `unknown` when XFF absent, mixed registered/unregistered top-level
  selections, and empty-registry short-circuit. All assertions land
  via `reactor.test.StepVerifier`.
- **Leg B (Failsafe)** GREEN: the existing
  `NlQueryRestAndGraphQlParityIT` is extended with
  `graphQlAndRestResolversDeclareIdenticalRateLimitFeatureAnnotation()`
  which uses `AnnotatedElementUtils.findMergedAnnotation` to assert
  every member of `@RateLimitFeature` is byte-equal across the REST
  and GraphQL resolver methods. This proves the parity contract at
  the integration level without standing up a live Lettuce/Redis
  fixture (deferred per existing repo posture; the production
  `RateLimitConfig` eagerly opens a Redis connection on
  `@ConditionalOnProperty` activation).
- **Leg C (Newman)** deferred: no new operator-visible endpoint is
  introduced; the GraphQL surface URL and request/response shapes
  remain those certified by P9.0.

### Rejected alternatives

1. **Live-Redis Failsafe IT via Testcontainers.** Rejected -- the
   project does not currently ship a Redis Testcontainer (verified
   via `grep -r redis pom.xml` across all 7 modules); introducing
   one for this single IT would add ~30s to the IT phase and bring
   a new infrastructural dependency that no other test exercises.
   The slice + annotation-equality assertions are sufficient to
   prove the contract.
2. **Mocked `ProxyManager` via `@MockBean` in a new Failsafe IT.**
   Rejected -- `RateLimitConfig` defines its
   `RedisClient`/`StatefulRedisConnection`/`ProxyManager` beans
   without `@ConditionalOnMissingBean`, so a `@MockBean` override
   would either clash or require modifying production wiring solely
   for testability. The Surefire slice already mocks the
   `ProxyManager` and asserts every call site.
3. **Separate `@RateLimitGraphQlFeature` annotation distinct from
   `@RateLimitFeature`.** Rejected -- the whole point of the parity
   contract is that REST and GraphQL declare the SAME bucket. A
   separate annotation invites drift (different capacity, different
   error code) and breaks the shared-bucket invariant.

### References

- Issue #129 -- `feat(gateway): P9.0a WebGraphQlInterceptor NL
  sub-bucket parity for GraphQL surface`.
- ADR-0021 -- `@RateLimitFeature` annotation pattern (now
  cross-surface).
- memory.md LD104 -- triangle-gate (Leg C deferred for this
  amendment).

## Amendment 2 -- P9.0b GraphQL rate-limit returns RFC 7807 429 (not 500)

**Date**: 2026-06-08. **Status**: Accepted (supersedes the runtime
claim made in Amendment 1).

### Context -- a defect Amendment 1 could not see

Amendment 1 (P9.0a) asserted that a rate-limit rejection on the
GraphQL `nlToLogQL` resolver flows
`RateLimitedException -> Mono.error -> GraphQlHttpHandler ->
GlobalExceptionHandler -> RFC 7807 429`. That claim was **never
exercised over the wire** -- the P9.0a Surefire slice mocked the
`ProxyManager`, and Legs B + C were deferred. A new live boot smoke
(`scripts/live-e2e/smoke-p9-0a.ps1`; booted gateway + Eureka + Redis +
WireMock) proved the claim **false**:

- When the **GraphQL** call exceeds the shared NL bucket, the gateway
  returned **HTTP 500** (`{"status":500,"error":"Internal Server
  Error","path":"/graphql"}`), NOT the documented `429`.
- The **REST** surface (and the GraphQL->REST shared-bucket direction)
  returned the correct `429` -- so the shared bucket itself is sound;
  only the GraphQL-side error **mapping** was wrong.

### Root cause

`GlobalExceptionHandler` is a `@RestControllerAdvice`. Spring's
`ExceptionHandlerExceptionResolver` only applies `@ExceptionHandler`
methods to `HandlerMethod` handlers (i.e. `@RequestMapping`
controllers). The GraphQL HTTP transport is a **functional**
`RouterFunction` endpoint (`GraphQlWebMvcAutoConfiguration`), whose
handler is not a `HandlerMethod`, so the advice is skipped and the
`RateLimitedException` thrown by `RateLimitGraphQlInterceptor` escapes
the resolver chain. Tomcat then renders a generic `500`. REST works
because its interceptor runs under `@RequestMapping` dispatch where
`@ExceptionHandler` applies.

### Decision (Option A -- preserve the documented HTTP contract)

Map `RateLimitedException` to RFC 7807 `429` for the functional
GraphQL endpoint so REST and GraphQL emit **byte-identical** problem
bodies (only `instance` differs: `/api/v1/query/nl` vs `/graphql`):

1. New `RateLimitProblemExceptionResolver` (`@Component`,
   `@ConditionalOnProperty(cortex.gateway.rate-limit.enabled=true)`,
   `implements HandlerExceptionResolver, Ordered`). It runs at
   `HIGHEST_PRECEDENCE` but **returns `null` for `HandlerMethod`
   handlers** so the REST surface stays on `@ExceptionHandler`
   untouched; it only acts on the functional (non-`HandlerMethod`)
   GraphQL endpoint. On a `RateLimitedException` (scanned through the
   cause chain) it writes `429` + `Retry-After` +
   `application/problem+json`.
2. The RFC 7807 builder is extracted from `GlobalExceptionHandler`
   into a shared `ProblemDetailFactory` so REST and GraphQL produce
   the same body by construction -- the parity contract becomes
   **structural**, not coincidental.

### Why a `HandlerExceptionResolver` (not the GraphQL error channel)

Option B (a `DataFetcherExceptionResolver` emitting `200 OK` with a
GraphQL `errors[]` payload + `extensions.classification=RATE_LIMITED`)
was rejected: P9.0a/ADR-0049 already document the rate-limit response
as an HTTP `429` with a `Retry-After` header, and the REST surface
returns exactly that. Keeping the GraphQL surface on the same HTTP
contract preserves operator tooling (load balancers, retry budgets,
`Retry-After`-aware clients) and the cross-surface parity claim. A
custom `HandlerExceptionResolver` is the correct MVC-layer tool for
exceptions escaping a functional endpoint that `@ExceptionHandler`
cannot reach.

### Rejected alternatives

1. **GraphQL-idiomatic `errors[]` at HTTP 200 (Option B).** Rejected
   -- diverges from the REST surface and from the already-published
   `429`/`Retry-After` contract; breaks HTTP-level rate-limit tooling.
2. **Make `RateLimitGraphQlInterceptor` set the HTTP status on
   `WebGraphQlResponse`.** Rejected -- the interceptor deals in
   GraphQL response shapes; it cannot emit an RFC 7807 problem body,
   which is the parity requirement.
3. **Move the rate-limit check into a servlet `Filter` on
   `/graphql`.** Rejected -- a filter runs before the GraphQL document
   is parsed, so it cannot know which top-level fields (and therefore
   which `@RateLimitFeature` sub-buckets) the request targets. The
   interceptor's post-parse field walk is required.
4. **Duplicate the RFC 7807 builder in the resolver.** Rejected --
   parity is the contract; a shared `ProblemDetailFactory` prevents
   drift between the two surfaces.

### Verification (triangle)

- **Leg A** GREEN: new `RateLimitProblemExceptionResolverTest` (5
  scenarios: functional-endpoint 429 body + `Retry-After` +
  problem+json + MDC trace id; `HandlerMethod` deferral; non-rate-limit
  ignored; wrapped-cause unwrap; `HIGHEST_PRECEDENCE`).
  `GlobalExceptionHandlerTest` stays GREEN (17/17) through the
  `ProblemDetailFactory` extraction. `mvn verify` GREEN
  (Checkstyle 0 / SpotBugs 0 / JaCoCo 0.80/0.80).
- **Leg B** GREEN: `scripts/live-e2e/smoke-p9-0a.ps1` end-to-end
  against the booted stack -- the GraphQL over-limit call now returns
  `429 NL_QUERY_RATE_LIMITED` + `Retry-After`, and BOTH shared-bucket
  directions (REST->GraphQL and GraphQL->REST) pass.
- **Leg C** deferred: no new endpoint shape (same GraphQL URL +
  request/response certified by P9.0).

### References

- Issue #131 -- `P9.0b: GraphQL NL rate-limit returns HTTP 500 instead
  of RFC 7807 429`.
- Amendment 1 (P9.0a) -- the parity contract whose runtime claim this
  amendment corrects.
- `scripts/live-e2e/smoke-p9-0a.ps1` -- the live smoke that caught the
  defect (Leg B).

## Amendment 3 -- P9.1b `searchLogs` REST + GraphQL parity (second query)

**Date**: 2026-06-08. **Status**: Accepted (first mechanical extension
of the D1-D7 pattern to a second query).

### Context

P9.0 shipped `nlToLogQL` as the pattern-setting first query; P9.1a
exposed log-indexer-service's P7.4 `LogSearchClient` SPI over REST
(`POST /api/v1/search`, ADR-0042 Amendment 1). P9.1b adds the second
of ADR-0004's four queries, `searchLogs`, on both gateway surfaces by
following the D1-D7 pattern verbatim -- new schema fields, a
`@ConditionalOnProperty`-gated REST controller + GraphQL resolver
sharing one `SearchLogsService`, a `@RateLimitFeature("search-logs")`
sub-bucket auto-registered on both surfaces (inheriting the P9.0a
interceptor + P9.0b 429 resolver for free), and a
`SearchLogsRestAndGraphQlParityIT` closer. This amendment records only
the decisions that are **new** relative to D1-D7.

### D-A3.1 -- Downstream call is client-side load-balanced, NOT a proxy route

Unlike `nlToLogQL` (whose backer is an in-process Spring AI call),
`searchLogs` calls a **separate service** (log-indexer-service). Two
ways to reach it were considered:

- **Spring Cloud Gateway `RouterFunction` proxy** (the P3.4
  `searchServiceRoute` shape) -- rejected. A proxy route forwards the
  raw HTTP exchange and cannot share the `SearchLogsService` +
  `@RateLimitFeature` + parity-IT machinery that the D-pattern
  requires; it would split REST and GraphQL onto different code paths
  and kill the parity contract.
- **Shared service that makes an outbound call** (chosen) -- the
  `SearchLogsServiceImpl` resolves an indexer instance via the
  blocking `LoadBalancerClient.choose("log-indexer-service")` and
  POSTs with a plain, timeout-bounded `RestClient` (HTTP/1.1 pin LD42
  + dual connect/read timeout LD121).

**Critical constraint**: the client is a *plain* `RestClient`, NOT a
`@LoadBalanced RestClient.Builder` bean. Declaring a `@LoadBalanced`
builder would be picked up by Spring AI's
`ObjectProvider.getIfAvailable(RestClient::builder)` lookup and
inadvertently load-balance the Ollama calls too. Using the blocking
`LoadBalancerClient` to resolve an absolute URI keeps load-balancing
scoped to exactly the search call.

### D-A3.2 -- Tenant resolved from `X-Tenant-Id`, surfaced to GraphQL via interceptor

The gateway JWT carries no tenant claim (subject + roles + token-type
only), so the tenant is taken from the `X-Tenant-Id` header (ADR-0009)
and forwarded to the indexer as the single source of truth (ADR-0042
D3) -- a body / input field can never spoof another tenant. REST reads
the header with `@RequestHeader`. GraphQL resolvers cannot see the HTTP
request, so a new `TenantHeaderGraphQlInterceptor`
(`WebGraphQlInterceptor`, gated on `search-logs.enabled`) lifts the
header into the GraphQL execution context under
`GraphQlContextKeys.TENANT_ID`, and the resolver reads it via
`@ContextValue`. This is the GraphQL counterpart to the REST
`@RequestHeader`, keeping tenant resolution identical across surfaces.

### D-A3.3 -- `JSON` + `Long` custom scalars for the opaque hit payload

`searchLogs` returns Quickwit hit documents whose shape is owned by the
indexer, not the gateway. To avoid coupling the schema to that shape,
hits are exposed through the `JSON` scalar and the 64-bit `numHits`
through the `Long` scalar (both from
`graphql-java-extended-scalars`, wired by `GraphQlScalarConfig`). This
keeps the GraphQL payload byte-identical to the REST JSON body.

### D-A3.4 -- Verdict-to-HTTP mapping consuming the indexer

The indexer returns RFC 7807 statuses (ADR-0042 Amendment 1). The
gateway service maps them onto its own `ErrorCodes`: `403` ->
`FORBIDDEN` (cross-tenant), `404` -> `NOT_FOUND` (missing index),
`422` -> `SEARCH_LOGS_INVALID` (permanent), and everything else
(downstream `429`/`503`/`5xx` + transport failures) ->
`SEARCH_LOGS_UPSTREAM_FAILED` (HTTP `502`). Three new `ErrorCodes`
(`SEARCH_LOGS_RATE_LIMITED`/`_INVALID`/`_UPSTREAM_FAILED`) were added
with matching `GlobalExceptionHandler` switch cases.

### D-A3.5 -- Retire the P3.4 `searchServiceRoute` echo placeholder

`GatewayRoutesConfig.searchServiceRoute` proxied `/api/v1/search/**`
to `log-echo-service` as a "until P7" placeholder. With the real
search surface now gateway-owned, the placeholder is removed. The new
REST endpoint is `GET /api/v1/logs/search` (per ADR-0004's
`GET /v1/logs/search`), which sits under the `/api/v1/logs/**` prefix
that `logsServiceRoute` proxies to log-ingest-service.

**Routing-precedence correction.** The first cut of this amendment
assumed Spring MVC's `RequestMappingHandlerMapping` (order 0) would
match the annotated `SearchLogsController` ahead of the gateway's
`RouterFunctionMapping` (order 3), so the two could coexist on the
shared prefix. The `SearchLogsRestAndGraphQlParityIT` closer (full
context) **falsified that assumption**: the REST call returned
`503 Unable to find instance for log-ingest-service`, proving the
gateway router function won and forwarded `/api/v1/logs/search` to the
ingest proxy. The collision is therefore resolved **structurally**:
the `logsServiceRoute` predicate is narrowed to
`path("/api/v1/logs/**").and(path("/api/v1/logs/search").negate())` so
the proxy no longer matches the gateway-owned search path, and the
annotated controller handles it. This is exactly the class of
integration defect the cross-surface closer IT exists to catch
(cf. the P7.1a `@Lazy` bean-cycle).

> **Superseded by P9.1c (Amendment 4).** The per-path
> `.negate("/api/v1/logs/search")` shown above does not scale to the
> P9.2 path-variable read `GET /api/v1/logs/{eventId}` (negating a
> `{eventId}` pattern would also exclude `batch`). Amendment 4 replaces
> it with a method discriminator
> `path("/api/v1/logs/**").and(method(POST))` that proxies every ingest
> WRITE and lets every read fall through, with no per-path exclusion.

### Rejected alternatives

1. **SCG `RouterFunction` proxy for searchLogs.** Rejected (D-A3.1) --
   splits REST/GraphQL code paths, no shared service, no parity IT.
2. **`@LoadBalanced RestClient.Builder` bean.** Rejected (D-A3.1) --
   pollutes Spring AI's builder lookup and load-balances Ollama.
3. **Tenant as a GraphQL input field / JWT claim.** Rejected (D-A3.2)
   -- spoofable; the header is the single source of truth and the JWT
   has no tenant claim yet (B6 pending).
4. **Map hits to a concrete GraphQL `LogHit` type.** Rejected (D-A3.3)
   -- couples the gateway schema to the indexer document shape; the
   `JSON` scalar keeps the surfaces decoupled and payload-identical.

### Verification (triangle)

- **Leg A** GREEN: `mvn -pl log-gateway verify` -- new Surefire slices
  (`SearchLogsControllerTest`, `SearchLogsGraphQlControllerTest`,
  `SearchLogsServiceImplTest` WireMock, `SearchLogsPropertiesTest`) +
  Failsafe `SearchLogsRestAndGraphQlParityIT` (payload identity +
  `@RateLimitFeature` annotation equality) + ArchUnit + Checkstyle 0 +
  SpotBugs 0 + JaCoCo 0.80/0.80.
- **Leg B** GREEN: live boot smoke (gateway + indexer + Eureka) --
  `searchLogs` returns an identical payload on REST and GraphQL for the
  same tenant + query.
- **Leg C** deferred per LD104 (Postman/Newman bumped with the new
  request; same GraphQL URL shape as P9.0).

### References

- Issue #136 -- `P9.1b: gateway searchLogs REST + GraphQL parity`.
- ADR-0042 Amendment 1 -- the P9.1a indexer REST search surface this
  query consumes.
- ADR-0009 -- tenant isolation via `X-Tenant-Id`.
- memory.md LD42 / LD121 -- HTTP/1.1 pin + dual timeout on the
  outbound search client.

## Amendment 4 -- P9.1c logs proxy method discriminator (supersedes Amendment 3 D-A3.5 negate)

**Date**: 2026-06-09. **Status**: Accepted (supersedes the per-path
`.negate()` in Amendment 3 D-A3.5).

### Context -- the negate does not scale to path-variable reads

Amendment 3 D-A3.5 resolved the `searchLogs` routing collision by
narrowing the ingest proxy predicate to
`path("/api/v1/logs/**").and(path("/api/v1/logs/search").negate())`.
That works for a single literal read path, but **breaks the moment a
path-variable read joins the prefix**: P9.2 adds
`GET /api/v1/logs/{eventId}` (`getLogById`), and
`path("/api/v1/logs/{eventId}").negate()` is a *pattern* that also
matches `/api/v1/logs/batch` -- so it would exclude the ingest write
too. There is no finite list of literal `.negate()` calls that isolates
"every get-by-id request" (the `{eventId}` set is unbounded) while still
proxying `batch`.

### Decision -- discriminate by HTTP method, not by path

The ingest surface under `/api/v1/logs/**` is **write-only**
(`POST /api/v1/logs/batch` today; `POST /api/v1/logs/stream` planned per
ADR-0004), and the read surface under the same prefix is **GET-only**
(`GET /api/v1/logs/search` P9.1b; `GET /api/v1/logs/{eventId}` P9.2).
Narrow the proxy predicate to the method:

```java
.route(path("/api/v1/logs/**").and(method(HttpMethod.POST)), http())
```

This proxies **every** ingest write to log-ingest-service (batch +
future stream, for free) and lets **every** gateway-owned read fall
through to its annotated controller (search now, getLogById next, future
reads free) -- structurally, with zero per-path exclusions to maintain.
The `.negate("/api/v1/logs/search")` is removed.

### Why this is the strongest form of the LD148 principle

LD148 says "the proxy owns an explicit contract, do not rely on handler
order." The negate was the weak form ("everything except the paths I
remember to exclude"); the method discriminator is the strong form ("the
proxy owns exactly the writes"). The only assumption it bakes in --
*writes are POST, reads are GET under this prefix* -- holds across the
entire ADR-0004 surface (the one POST-shaped read, `nlToLogQL`, lives at
`/api/v1/query/nl`, not under `/logs/`, so it never collides).

### Rejected alternatives

1. **Exact-path allowlist `POST("/api/v1/logs/batch")`.** Rejected --
   correct but requires a new allowlist line per future ingest path
   (e.g. `stream`), and `stream` is already on the ADR-0004 roadmap; the
   method discriminator covers it with no future edit.
2. **Multi-`.negate()` chain (`search` + `{eventId}`).** Rejected -- the
   dead end above: a `{eventId}` pattern negation also excludes `batch`.
3. **Reorder handler mappings / `@Order` on the controller.** Rejected --
   LD148: do not rely on `RequestMappingHandlerMapping` vs
   `RouterFunctionMapping` ordering; the predicate exclusion is explicit
   and greppable.

### Verification (triangle)

- **Leg A** GREEN: `GatewayRoutesConfigTest.logsProxyMatchesPostWritesButNotGetReads`
  evaluates the predicate directly via `ServerRequest.create(MockHttpServletRequest, ...)`
  -- `POST /batch` + `POST /stream` match, `GET /search` + `GET /{eventId}`
  do not. `SearchLogsRestAndGraphQlParityIT` stays GREEN (GET search
  still reaches the controller). `mvn -pl log-gateway verify` GREEN.
- **Leg B** the existing gateway-chain ingest smoke proves `POST /batch`
  still proxies to log-ingest-service over `lb://` (the route internals
  -- `rewritePath` + `lb()` -- are unchanged; only the predicate
  narrowed, and `POST /batch` still satisfies it).

### References

- Issue #139 -- `P9.1c: harden logs proxy predicate (method discriminator)`.
- Amendment 3 D-A3.5 -- the per-path negate this supersedes.
- memory.md LD148 + `/memories/repo/gateway-scg-mvc-routing-precedence.md`
  -- the routing-precedence lesson.

## Amendment 5 -- P9.2b `getLogById` REST + GraphQL parity (third query)

**Date**: 2026-06-09. **Status**: Accepted (third mechanical extension
of the D1-D7 pattern, mirroring Amendment 3's `searchLogs`).

### Context

P9.2a exposed log-ingest-service's `raw_logs` read path over REST
(`GET /api/v1/logs/{eventId}` -> `LogResponse`, ADR-0022 Amendment 2).
P9.2b adds the third of ADR-0004's four queries, `getLogById`, on both
gateway surfaces by following the D1-D7 pattern + the P9.1b
client-side-LB shape (Amendment 3) verbatim. This amendment records only
what is **new** relative to `searchLogs`.

### D-A5.1 -- Reuses the P9.1b downstream-call + tenant machinery wholesale

`getLogById` is structurally identical to `searchLogs`, just a different
HTTP method + path + downstream service:

- shared `GetLogByIdService` -> blocking `LoadBalancerClient.choose(
  "log-ingest-service")` + a plain `RestClient` (a SECOND bean,
  `ingestRestClient`, distinct from P9.1b's `indexerRestClient` so the
  two read features stay independently gated; by-name injection
  disambiguates the two `RestClient` beans -- D-A5.3);
- tenant from `X-Tenant-Id`: REST `@RequestHeader`, GraphQL via the
  P9.1b `TenantHeaderGraphQlInterceptor` -> `@ContextValue`. The
  interceptor is reused, but P9.2b promotes it from a search-gated
  component to an unconditional shared concern (D-A5.5);
- `labels` exposed through the EXISTING `JSON` scalar
  (`GraphQlScalarConfig` from P9.1b);
- `@RateLimitFeature("get-log-by-id")` on both surfaces (auto-registered
  by the P9.0a interceptor; inherits the P9.0b 429 resolver).

### D-A5.2 -- Routing is already clean (no route work)

`GET /api/v1/logs/{eventId}` sits under the `/api/v1/logs/**` ingest
proxy prefix, but P9.1c (Amendment 4) narrowed that proxy to `POST`
only, so the `GET` falls through to the controller with NO route edit.
Within the gateway, the literal `/api/v1/logs/search` mapping
(`SearchLogsController`) is more specific than this `{eventId}` pattern,
so Spring routes `search` to the search controller and every other
single segment to `GetLogByIdController` -- no ambiguity. The
`GatewayRoutesConfigTest` predicate guard already asserts
`GET /api/v1/logs/{eventId}` does not match the proxy.

### D-A5.3 -- Two `RestClient` beans coexist by name

P9.1b's `SearchLogsClientConfig` declares `indexerRestClient`; P9.2b's
`GetLogByIdClientConfig` declares `ingestRestClient`. Both are plain
timeout-bounded `RestClient`s with no base URL. When both features are
enabled, two `RestClient` beans exist; Spring resolves the constructor
parameters by name (`indexerRestClient` / `ingestRestClient`), so there
is no ambiguity. Keeping two beans (vs. one shared bean gated on an OR
of the two flags) preserves independent feature gating and matches the
parity pattern.

### D-A5.4 -- `LogEntry` carries timestamps as ISO-8601 strings

The gateway is a pass-through; it does not re-parse the ingest response.
`LogEntry.ts` / `receivedAt` are `String` (the ISO-8601 value the ingest
backer emits) so the REST JSON and the GraphQL `String` scalar produce
byte-identical values without any temporal-coercion mismatch. The
GraphQL `getLogById(id: ID!): LogEntry` returns a nullable `LogEntry`
per ADR-0004; a miss surfaces as a GraphQL error (the gateway throws
`NOT_FOUND`), not `null`.

### D-A5.5 -- Tenant interceptor promoted to an unconditional shared concern

P9.1b gated `TenantHeaderGraphQlInterceptor` on
`cortex.gateway.search-logs.enabled=true` -- correct when `searchLogs`
was the only tenant-scoped GraphQL query. P9.2b makes that gate a latent
bug: with `search-logs` disabled and `get-log-by-id` enabled, the
interceptor would be absent, the tenant header would never reach the
GraphQL context, and `getLogById`'s `@ContextValue` tenant would be
`null` -- so every GraphQL `getLogById` call would fail validation while
the REST surface kept working, breaking parity. The full-context
`GetLogByIdRestAndGraphQlParityIT` (which enables only `get-log-by-id`)
caught this: the GraphQL envelope carried a `VALIDATION_FAILED` error.
The fix removes the `@ConditionalOnProperty` entirely: tenant
propagation is a cross-query concern shared by `searchLogs`, `getLogById`,
and the future `getAnomalies`, so it must not hinge on any one query's
flag. The interceptor is cheap (a single header lookup; a no-op when the
header is absent or no resolver reads the context value), so running it
whenever the GraphQL surface is present costs nothing measurable. This
is the same lesson as P9.1c's routing fix (Amendment 4): a shared
cross-cutting concern must not be gated on one feature's flag.

### Verdict -> HTTP mapping

The ingest backer returns `404` on a miss (and `400` only if the tenant
header were absent, which the gateway always supplies). The gateway
maps: `404` -> `404 NOT_FOUND`; everything else (other 4xx, 5xx,
transport) -> `502 GET_LOG_BY_ID_UPSTREAM_FAILED`. Two new `ErrorCodes`
(`GET_LOG_BY_ID_RATE_LIMITED` / `_UPSTREAM_FAILED`) + the matching
`GlobalExceptionHandler` switch cases.

### Rejected alternatives

1. **One shared downstream `RestClient` bean for both read features.**
   Rejected (D-A5.3) -- would need an OR-of-two-flags condition
   (`@ConditionalOnExpression`) and couples the two features' lifecycles;
   two named beans are simpler and independently gated.
2. **Typed `Instant` timestamps on `LogEntry`.** Rejected (D-A5.4) --
   the gateway is a relay; `String` passthrough guarantees byte-identical
   REST/GraphQL payloads with no coercion surprises.
3. **A new route entry for `GET /api/v1/logs/{eventId}`.** Rejected
   (D-A5.2) -- P9.1c's method discriminator already lets the GET fall
   through; no route work is needed.
4. **Fix the parity IT by also enabling `search-logs` (so the existing
   gated interceptor loads).** Rejected (D-A5.5) -- that papers over a
   real production defect (only-`get-log-by-id`-enabled deployments
   would have broken GraphQL tenant propagation); decoupling the
   interceptor from the search flag fixes the root cause instead.

### Verification (triangle)

- **Leg A** GREEN: `mvn -pl log-gateway verify` -- new Surefire slices
  (`GetLogByIdControllerTest`, `GetLogByIdGraphQlControllerTest`,
  `GetLogByIdServiceImplTest` WireMock, `GetLogByIdPropertiesTest`) +
  Failsafe `GetLogByIdRestAndGraphQlParityIT` (payload identity +
  `@RateLimitFeature` annotation equality) + ArchUnit + Checkstyle 0 +
  SpotBugs 0 + JaCoCo 0.80/0.80.
- **Leg B** GREEN: live boot smoke `scripts/live-e2e/smoke-p9-2b.ps1`
  (gateway + ingest + Postgres + Eureka) -- `getLogById` returns an
  identical payload on REST and GraphQL for a seeded row, and 404 on a
  miss. This Leg B closes both P9.2a and P9.2b per LD104.

### References

- Issue #143 -- `P9.2b: gateway getLogById REST + GraphQL parity`.
- ADR-0022 Amendment 2 -- the P9.2a ingest REST read backer this query
  consumes.
- Amendment 3 (P9.1b) -- the `searchLogs` shape this mirrors.
- Amendment 4 (P9.1c) -- the method discriminator that makes the GET
  route fall through for free.

## Amendment 6 -- P9.3b `getAnomalies` REST + GraphQL parity (fourth query)

**Date**: 2026-06-10. **Status**: Accepted (fourth and final mechanical
extension of the D1-D7 pattern, closing ADR-0004's four read queries).

### Context

P9.3a exposed log-remediation-service's `anomalies` read model over REST
(`GET /api/v1/anomalies?tenantId&since&until&limit` -> `List<AnomalyResponse>`,
ADR-0052). P9.3b adds the last of ADR-0004's four queries, `getAnomalies`,
on both gateway surfaces by following the D1-D7 pattern + the P9.1b/P9.2b
client-side-LB shape verbatim. This amendment records only what is **new**
relative to `getLogById` (Amendment 5).

### D-A6.1 -- A list query, not a single-entity lookup

`getAnomalies` is the first read query that returns a **collection**
(`[Anomaly!]!` / `List<Anomaly>`). An empty list is a valid result, not a
miss, so there is NO `NOT_FOUND` mapping (unlike `getLogById`). The
shared `GetAnomaliesService` returns the (defensively copied) list; both
surfaces relay it unchanged. The GraphQL field is non-null-list-of-
non-null (`[Anomaly!]!`): a zero-anomaly tenant yields `[]`, never
`null`.

### D-A6.2 -- Tenant flows downstream as a query parameter, not a header

P9.1b/P9.2b forwarded the resolved tenant to the backer as the
`X-Tenant-Id` header. The P9.3a remediation backer instead reads
`tenantId` as a **required query parameter** (it predates the gateway and
stays directly callable, ADR-0052). So `GetAnomaliesServiceImpl` appends
`?tenantId=<resolved>` to the downstream URI rather than setting a header.
The gateway still resolves the tenant identically (REST `@RequestHeader`
X-Tenant-Id, GraphQL `@ContextValue` via the shared
`TenantHeaderGraphQlInterceptor` promoted unconditional in Amendment 5),
so a client-supplied argument still cannot spoof a tenant -- only the
gateway-resolved value reaches the query string. Query values are
URL-encoded (`UriComponentsBuilder.encode()`) so ISO-8601 `since`/`until`
bounds (which carry `:` and possibly `+`) survive transit.

### D-A6.3 -- Third downstream `RestClient` bean (`remediationRestClient`)

P9.1b declares `indexerRestClient`, P9.2b `ingestRestClient`; P9.3b adds
`remediationRestClient` (plain, timeout-bounded, HTTP/1.1, no base URL).
Three named beans now coexist when all read features are enabled; Spring
resolves the constructor parameters by name. Keeping a per-feature bean
preserves independent gating, matching the Amendment 5 D-A5.3 rationale.

### D-A6.4 -- `Anomaly` carries timestamps as ISO-8601 strings

Like `LogEntry` (D-A5.4), the gateway is a pass-through: `Anomaly.ts` /
`receivedAt` are `String` (the ISO-8601 values the remediation backer
emits) so the REST JSON and the GraphQL `String` scalar are byte-identical
with no temporal coercion. `confidence` rides the built-in `Float` scalar
(a double); no custom scalar is needed (the `Anomaly` type has no opaque
JSON field, unlike `searchLogs`).

### Verdict -> HTTP mapping

The remediation backer returns `200` (with a possibly-empty list) on
success and `400` only on a malformed filter (the gateway always supplies
a non-blank `tenantId`). The gateway maps: downstream `4xx` -> `422
GET_ANOMALIES_INVALID` (a permanently-unprocessable query); everything
else (`5xx`, transport, no-instance) -> `502 GET_ANOMALIES_UPSTREAM_FAILED`.
The gateway also validates `tenantId` presence + `limit > 0` up front ->
`400 VALIDATION_FAILED` (shared by both surfaces via the service). Three
new `ErrorCodes` (`GET_ANOMALIES_RATE_LIMITED` / `_INVALID` /
`_UPSTREAM_FAILED`) + the matching `GlobalExceptionHandler` switch cases.

### Rejected alternatives

1. **A GraphQL `input` type for the filters.** Rejected -- all three
   filters (`since`, `until`, `limit`) are optional, so individual
   optional arguments (`getAnomalies(since, until, limit)`) map 1:1 to the
   REST query parameters and keep the parity test trivial; an input
   wrapper adds a type for no gain (contrast `searchLogs`, whose required
   `indexId`/`query` justified `LogSearchInput`).
2. **Typed `Instant` `since`/`until` on the gateway.** Rejected -- the
   gateway is a relay; `String` pass-through forwards the operator's value
   verbatim and lets the backer own ISO-8601 parsing + the `400` on
   malformed input, avoiding timezone re-formatting drift.
3. **A `404`/empty-as-miss mapping.** Rejected (D-A6.1) -- a list query's
   empty result is success, not a miss.

### Verification (triangle)

- **Leg A** GREEN: `mvn -pl log-gateway verify` -- new Surefire slices
  (`GetAnomaliesControllerTest`, `GetAnomaliesGraphQlControllerTest`,
  `GetAnomaliesServiceImplTest` WireMock, `GetAnomaliesPropertiesTest`) +
  Failsafe `GetAnomaliesRestAndGraphQlParityIT` (payload identity +
  `@RateLimitFeature` annotation equality) + ArchUnit + Checkstyle 0 +
  SpotBugs 0 + JaCoCo 0.80/0.80.
- **Leg B** GREEN: live boot smoke `scripts/live-e2e/smoke-p9-3b.ps1`
  (gateway + remediation + Postgres + Eureka) -- `getAnomalies` returns an
  identical payload on REST and GraphQL for a seeded tenant.

### References

- Issue (P9.3b) -- `gateway getAnomalies REST + GraphQL parity`.
- ADR-0052 -- the P9.3a remediation anomalies read model + REST backer
  this query consumes.
- Amendment 5 (P9.2b) -- the `getLogById` shape this mirrors.
- This closes ADR-0004's four read queries (`nlToLogQL`, `searchLogs`,
  `getLogById`, `getAnomalies`) and the P9 epic's GraphQL parity track.

