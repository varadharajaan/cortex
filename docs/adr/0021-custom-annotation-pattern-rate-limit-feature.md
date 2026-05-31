# 0021 - log-gateway custom-annotation pattern + `@RateLimitFeature` reference impl

Status: Accepted
Date: 2026-05-31
Deciders: CORTEX core engineering
Tags: P3.4, gateway, cross-cutting, annotation, handler-interceptor, rate-limit

## Context

Two cross-cutting concerns on log-gateway need to be applied
selectively to a small set of HTTP handlers WITHOUT touching every
handler's body:

1. **Per-feature rate limits**: the NL-query endpoint (P3.3) needs an
   independent Bucket4j sub-bucket so a runaway NL caller cannot
   exhaust the global P3.2 bucket and lock the whole gateway out.
   P3.4 adds the same need on `/api/v1/auth/login` (defence in depth
   against credential-stuffing) and reserves the pattern for future
   feature endpoints (P4 ingest write quotas, P7 search QPS caps).

2. **Future cross-cutting concerns** (audit-emit, tenant-scope assert,
   PII-redact on logs) that will land in P4..P8 need the SAME wiring
   shape so we are not inventing a new pattern per concern.

P3.3 originally hand-wired the NL sub-bucket inside the
`NlQueryServiceImpl` body. That works for one feature but breaks down
the moment a second annotated route lands -- the consume logic gets
duplicated and the bucket key convention drifts across services.

The user gave the requirement explicitly:

> "wire it through a custom annotation so new annotated routes inherit
> the same rate-limit posture without per-route boilerplate, and do it
> WITHOUT adding spring-aop or aspectjweaver to the gateway."

The "no spring-aop / no aspectjweaver" half of that constraint is
load-bearing: see LD41 in memory.md. AspectJ adds a weaver agent in
some build modes, and the spring-aop starter adds runtime-generated
CGLIB proxies on every annotated bean -- a class-loader cost we are
not willing to pay just to apply a `tryConsume()` call before a
handler runs.

## Decision drivers

- **D1. Selective application.** The annotation only applies to a few
  HTTP handlers; we do NOT want it triggered on every Spring bean
  method call (which spring-aop's broad pointcut style invites).
- **D2. No new transitive dependencies.** Spring MVC already ships a
  `HandlerInterceptor` extension point; we do NOT need a new starter.
  LD27 (parent-pom transitive pinning) means every new dep costs a
  manual entry in `dependencyManagement` plus an Enforcer review.
- **D3. Scoped registration.** The interceptor MUST be registered on
  `/api/**` ONLY so it never runs on `/actuator/**`, `/v3/api-docs`,
  or `/swagger-ui/**` -- the health probes and OpenAPI docs are
  excluded paths in our security + rate-limit policy and must not be
  routed through any per-feature filter.
- **D4. Placeholder-friendly members.** Annotation members are
  evaluated at class-load time, so capacity/refill MUST be expressible
  as `${...}` placeholders resolved against the live `Environment` at
  request dispatch time -- not at class-load time. Otherwise we cannot
  override capacity per environment without a recompile.
- **D5. Symmetric with the global filter.** The per-feature 429 path
  MUST surface RFC 7807 + `Retry-After` exactly like the global
  filter, and MUST share the same Bucket4j `ProxyManager` so a single
  Redis connection pool covers global + per-feature traffic.
- **D6. No header collision with the global filter.** The global
  `RateLimitFilter` owns `X-RateLimit-Limit / -Remaining / -Reset`
  on every response it touches (B5.2). Per-feature 429s MUST NOT add
  those headers (LD33) so the absence-of-headers is a reliable
  signal that the 429 came from a per-feature sub-bucket -- not a
  global exhaustion.
- **D7. ArchUnit-enforceable layering.** A future test should be able
  to assert: "no class outside the interceptor talks directly to the
  bucket library for per-feature buckets" -- so the annotation pattern
  remains the single entry point.

## Considered options

### Option A - Custom annotation + Spring MVC `HandlerInterceptor`

- Pros: zero new dependencies; `HandlerInterceptor` is the documented
  Spring MVC extension point for "do something before/after a handler
  runs"; registered explicitly on `/api/**` via `WebMvcConfigurer`
  (D3); has direct access to the matched `HandlerMethod` so the
  annotation lookup is a one-liner; cannot apply to anything other
  than dispatcher handlers (so non-HTTP bean methods are out of scope
  by construction).
- Cons: only applies inside the MVC dispatcher; cannot be reused for
  scheduler jobs or non-HTTP entry points. Acceptable: every consumer
  of this annotation today is an HTTP handler.

### Option B - `spring-aop` + `@Aspect`

- Pros: idiomatic Spring; works on any bean method.
- Cons: adds the `spring-aop` starter (CGLIB at runtime); broader
  pointcut surface area than we need; requires the bean to be
  proxyable (no `final` classes, no `final` methods); duplicates
  Spring's existing `HandlerInterceptor` extension point for this
  use case. Explicitly rejected by LD41.

### Option C - AspectJ `aspectjweaver` (load-time or compile-time)

- Pros: most flexible.
- Cons: heaviest of the three; adds a weaver agent in some build
  modes; cross-cutting code gets woven into bytecode making stack
  traces and JVM-tool inspection harder; class-loader cost. Rejected
  by LD41.

### Option D - Filter (servlet `Filter`) keyed by URL pattern

- Pros: even smaller surface area than `HandlerInterceptor`.
- Cons: a `Filter` runs BEFORE Spring MVC binds the request to a
  `HandlerMethod`, so the annotation discovery would have to
  duplicate the MVC URL -> handler mapping. Defeats the purpose.

### Option E - Programmatic per-route wiring (status quo)

- Pros: zero abstraction.
- Cons: duplicate `tryConsume()` boilerplate on every new feature
  endpoint; bucket key convention drifts; the wiring lives in the
  service body where reviewers do not expect it. Status quo before
  P3.4 and explicitly the thing we are replacing.

## Decision

**Chosen: Option A -- custom `@RateLimitFeature` annotation wired
through `RateLimitFeatureInterceptor` (`HandlerInterceptor`) and
registered on `/api/**` only by `RateLimitFeatureConfig`
(`WebMvcConfigurer.addInterceptors`).**

The decision applies the broader principle: **cross-cutting concerns
on log-gateway are wired through custom annotations + Spring MVC
HandlerInterceptors, NEVER through spring-aop or aspectjweaver**.
`@RateLimitFeature` is the reference implementation; subsequent
cross-cutting annotations (audit-emit, tenant-scope assert,
PII-redact) MUST follow the same pattern.

Concrete shape:

- `io.cortex.gateway.annotation.RateLimitFeature` -- `@Target({METHOD,
  TYPE}) @Retention(RUNTIME)`; members `name` / `capacity` / `refill`
  / `errorCode` / `keyPrefix`. All numeric and duration members are
  `String` so `${...}` placeholders resolve at request time via
  `Environment.resolveRequiredPlaceholders(...)`.
- `io.cortex.gateway.interceptor.RateLimitFeatureInterceptor` --
  `HandlerInterceptor#preHandle` resolves the annotation via
  `AnnotatedElementUtils.findMergedAnnotation` on the handler method
  (falling back to the bean type), resolves placeholders, builds the
  bucket key (`<keyPrefix><name>:user:<principal>` for authed,
  `<keyPrefix><name>:ip:<remote>` for anonymous), calls
  `proxyManager.builder().build(key, () -> config)
  .tryConsumeAndReturnRemaining(1)`, throws `RateLimitedException`
  carrying the resolved `ErrorCodes` constant when the bucket is
  empty. `BucketConfiguration` is cached per `name` in a
  `ConcurrentHashMap`.
- `io.cortex.gateway.config.RateLimitFeatureConfig` -- the lone
  `WebMvcConfigurer`; `addInterceptors(registry).addPathPatterns
  ("/api/**")` (D3).
- Reuse path: the same `ProxyManager<String>` bean built by
  `RateLimitConfig` for the global filter (single Redis connection
  pool; D5).
- Header policy: the interceptor does NOT set `X-RateLimit-*`
  headers; only the global filter does (D6 + LD33).

P3.4 also refactors `NlQueryController.translate(...)` and
`AuthController.login(...)` onto the annotation so the two existing
sub-buckets share the same wiring.

## Consequences

Positive:

- Zero new dependencies (no `spring-aop`, no `aspectjweaver`).
- One reference pattern for ALL future cross-cutting concerns on the
  gateway; new annotations follow the same annotation + interceptor +
  registrar trio.
- Adding a new feature sub-bucket is one annotation on the handler
  plus zero infrastructure code.
- The annotation lookup is restricted to MVC handlers by construction
  (D1); cannot accidentally fire on a `@Service` method.
- The bucket-config cache (keyed by `name`) means placeholder
  resolution + ISO-8601 parsing is paid once per feature, not once
  per request.
- The 429 body shape is symmetric with the global filter so existing
  RFC 7807 client code keeps working unchanged.

Negative / Open:

- The pattern is HTTP-only. Scheduled tasks or message-driven entry
  points cannot reuse `@RateLimitFeature` directly; they would need
  their own interceptor analogue. Acceptable: log-gateway is the
  only module with this concern today.
- The interceptor depends on the global `RateLimitFilter`'s
  `ProxyManager` bean. If `cortex.gateway.rate-limit.enabled=false`
  the interceptor's `@ConditionalOnProperty` also disables, so both
  layers turn off together (consistent with LD44 -- the global
  filter and per-feature sub-buckets are a single switch).
- An ArchUnit rule pinning "only the interceptor calls the bucket
  library for per-feature buckets" is NOT shipped in P3.4; it is
  deferred to P3.5 / P4 once a second annotation lands and we have
  two reference points for the rule.

## Re-evaluation triggers

- A future cross-cutting concern needs scheduler-task coverage
  (non-HTTP) -> add a parallel interceptor for the scheduler entry
  point; do NOT pivot to spring-aop just for that.
- The `BucketConfiguration` cache outgrows a `ConcurrentHashMap`
  (>1000 distinct annotated handlers, unlikely) -> swap to Caffeine.
- A second cross-cutting annotation (audit-emit / tenant-scope /
  PII-redact) lands -> ship the ArchUnit rule pinning "no class
  outside the matching interceptor talks to the underlying library
  for that concern".
- The MVC stack is replaced by WebFlux (would reverse ADR-0014) ->
  swap `HandlerInterceptor` for `WebFilter`; the annotation contract
  stays identical.

## Related

- ADR-0014 - log-gateway uses Spring Cloud Gateway MVC
- ADR-0016 - CORTEX uses Eureka for local-dev discovery
- ADR-0017 - rate limit uses Bucket4j 8.14 + raw Lettuce + Redis
- ADR-0018 - NL-query uses Spring AI ChatClient + Ollama starter
- LD27 (memory.md) - transitive deps must be pinned in parent
- LD33 (memory.md) - global filter owns `X-RateLimit-*` headers;
  sub-buckets do NOT add them
- LD41 (memory.md) - no `spring-aop`, no `aspectjweaver` on
  log-gateway; cross-cutting concerns use custom annotations +
  HandlerInterceptors
- LD44 (memory.md) - global + per-feature rate-limit are a single
  on/off switch via `cortex.gateway.rate-limit.enabled`
- Part 7.1.3 (strict rules) - rate-limit header contract
- Part 26.1 (strict rules) - live Postman/Newman gate
