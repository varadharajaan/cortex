# 14. log-gateway uses Spring Cloud Gateway MVC, not the reactive variant

- Status: accepted
- Date: 2026-06-04
- Deciders: CORTEX core
- Tags: P3, gateway, spring-cloud, mvc-vs-reactive

## Context and problem statement

CORTEX P3 introduces `log-gateway`, the first Spring Boot service module
(P2 was the embedder SDK). Spring Cloud 2023.0.x ships **two** Gateway
runtimes:

1. `spring-cloud-starter-gateway` — reactive (Netty + WebFlux).
2. `spring-cloud-starter-gateway-mvc` — server-side, blocking, runs on
   the same Spring MVC stack as a normal `spring-boot-starter-web`
   application.

The rest of CORTEX (per the master plan and the strict rules contract)
is Spring MVC + JPA + Testcontainers + blocking servlet code. The
service modules that come after `log-gateway` (ingest, processor,
remediation, indexer, monitoring) are all blocking REST services.

We must pick one variant before writing the module's `pom.xml`.

## Decision drivers

- Stack consistency: the rest of CORTEX is Spring MVC; mixing reactive
  in one module forces every shared utility (filters, ArchUnit rules,
  test fixtures) to grow a reactive twin.
- Rule 8.x layering tests express controller/service layers in terms of
  blocking method calls; rewriting them for `Mono` / `Flux` doubles the
  test surface for zero functional gain.
- Rule 12.1 mandates Testcontainers; reactive Gateway with WebTestClient
  is fine, but `MockMvc` is the project default and is already wired
  into the parent POM's Surefire config.
- The gateway's documented responsibilities (auth, correlation, rate
  limit, NL->LogQL routing, reverse proxy) do not require reactive
  back-pressure; expected QPS is moderate (hundreds per second per
  instance) and the downstream calls are short-lived HTTP requests.
- The team's day-to-day debugging skills are stronger on blocking
  stacks; reactive stack traces are notoriously hard to read.
- `spring-cloud-starter-gateway-mvc` is officially supported by the
  Spring Cloud team as a first-class peer of the reactive starter
  (since Spring Cloud 2023.0.0, December 2023).

## Considered options

### Option A — Reactive Gateway (`spring-cloud-starter-gateway`)
- Pros: better at very high concurrency (10k+ in-flight requests),
  built-in Redis rate-limit filter with `RequestRateLimiter`, mature.
- Cons: introduces WebFlux into a module that otherwise has no need
  for it; forces all internal utilities (filters, error advice,
  ArchUnit rules) to gain a reactive variant; mixes blocking JDBC
  (if we add user/tenant lookup) with reactive pipelines, which is the
  worst of both worlds.

### Option B — Spring Cloud Gateway MVC (`spring-cloud-starter-gateway-mvc`)
- Pros: same servlet stack as the rest of CORTEX; reuses MockMvc,
  servlet filters, RestControllerAdvice; shares ArchUnit rules with
  ingest/processor/indexer.
- Cons: not appropriate for >10k concurrent in-flight requests per
  instance (acceptable for CORTEX scale targets).

### Option C — Hand-rolled proxy with `RestClient`
- Pros: zero Spring Cloud dependency.
- Cons: re-implements predicate routing, filter chain, Resilience4j
  integration, retry, circuit breakers — already provided by either
  Gateway variant. Reinventing this is a multi-month tax.

## Decision outcome

**Chosen: Option B (Spring Cloud Gateway MVC).**

- Add `<dependency>spring-cloud-starter-gateway-mvc</dependency>` to
  `log-gateway/pom.xml`.
- All filters subclass `OncePerRequestFilter`.
- Rate limit (P3.2) uses Bucket4j over Redis (Testcontainers), invoked
  from a `OncePerRequestFilter`, not from the reactive
  `RequestRateLimiter` filter.
- Gateway routes (P3.4) declared via Java DSL on
  `RouterFunctions.route()` (Gateway MVC's `RouteLocator` equivalent).

## Consequences

### Positive
- Single mental model for the whole codebase.
- ArchUnit rules in P3.0 apply uniformly to every downstream service.
- MockMvc + Testcontainers test patterns transfer directly to
  ingest/processor/indexer.

### Negative / accepted
- If a single gateway instance ever needs to serve more than a few
  thousand concurrent in-flight requests, we revisit and consider
  migrating to the reactive variant. Logged as open question OQ4 in
  `memory.md`.

## Verification

- The chosen starter resolves cleanly with Spring Boot 3.3.5 and Spring
  Cloud 2023.0.4 BOM (parent POM already imports the BOM).
- A `WebMvcTest` slice can boot `HealthController` without pulling in
  reactive types (verified by `./mvnw -pl log-gateway -am verify`).
- ArchUnit rule `NO_REACTIVE_TYPES_IN_GATEWAY` can be added if a
  future contributor accidentally adds a `Mono`/`Flux` dependency.
