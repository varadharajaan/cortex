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

