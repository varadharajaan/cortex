# 0017 - log-gateway rate limit uses Bucket4j 8.14 + raw Lettuce + Redis

Status: Accepted
Date: 2026-05-30
Deciders: CORTEX core engineering
Tags: P3, gateway, rate-limit, bucket4j, lettuce, redis, infra

## Context

Part 5 of the strict rules (Reliability) and Part 7.1.3 (rate limiting)
require log-gateway to enforce per-principal rate limits with a
distributed token bucket and surface the canonical
`X-RateLimit-Limit / X-RateLimit-Remaining / X-RateLimit-Reset /
Retry-After` headers plus an RFC 7807 problem body on 429.

P3.2 in plan.md section 9 closes that requirement on the gateway
edge. The bucket store must:

- be process-shared (multiple gateway replicas in prod cannot each
  hold a local bucket without breaking the contract)
- not require a second connection pool when the gateway already
  depends on Lettuce transitively (via spring-boot-dependencies BOM)
- atomically decrement-and-fetch in one round trip so that a single
  rejected request does not cost two Redis calls
- have a per-principal AND a per-IP fallback path (Part 7.1.3 says
  anonymous traffic must also be throttled, but with a smaller
  bucket)

LD33 (memory.md, recorded 2026-05-30 in this sub-phase) records the
Bucket4j version-pin justification. This ADR records the engineering
choice for the store, the client, the proxy-manager variant, and the
filter wiring.

## Decision drivers

- **B5.1 (distributed bucket)**: the in-rule contract forbids a
  per-JVM bucket; the store MUST survive a gateway pod restart and
  MUST be observable from a second replica.
- **B5.2 (canonical headers)**: the filter MUST set
  `X-RateLimit-*` on both allowed and rejected responses; the
  `@RestControllerAdvice` MUST set `Retry-After` on the 429.
- **A0.1 (ASCII-only)**: every source file under
  `log-gateway/src/main/java` must be 7-bit ASCII, which rules out
  any library whose generated Javadoc or constants leak non-ASCII
  characters into the classpath.
- **O5 (Lombok policy)**: log-gateway uses Lombok; log-agent-lib
  does not. Anything pulled into the gateway may use Lombok.
- **LD27 (transitive pinning)**: every new transitive dependency
  with > 0 conflicts in the dependency tree must be pinned in the
  parent `dependencyManagement` block BEFORE the Enforcer runs.
- **Single-round-trip atomicity**: rejection should be cheap. A
  GET-then-SET pattern costs two round trips and races between them.
- **Spring Boot defaults**: spring-boot-dependencies already brings
  in Lettuce. Adding a second Redis client (Jedis, Redisson) would
  double the connection pool and break LD27.
- **Maintenance signal**: when picking a Bucket4j minor version, we
  prefer the version with the highest Maven Central dependent count
  to minimise the chance of hitting an unpatched bug.

## Considered options

### Option A - Bucket4j 8.14 + raw Lettuce + Redis CAS proxy manager
- Pros: single-round-trip atomic consume via the CAS proxy manager;
  no extra Redis client; 38 Maven Central dependents on the 8.14
  line (highest in the 8.x train as of 2026-05-30); the
  `_jdk17-*` artifact split keeps the runtime class set small;
  Lettuce is already on the classpath transitively.
- Cons: `LettuceBasedProxyManager.builderFor(connection)` is
  deprecated in 8.14 in favour of a future
  `Bucket4jLettuce.casBasedBuilder` that ships on the 8.15+ line.
  We pin 8.14 anyway because 8.15+ currently has 0-2 dependents
  (immature). The deprecation is documented and suppressed inline
  per LD33.

### Option B - Bucket4j 8.15+ + raw Lettuce + new builder
- Pros: non-deprecated builder.
- Cons: 0-2 Maven Central dependents on 8.15.x as of 2026-05-30;
  insufficient bake time; high chance of hitting an unpatched bug.
  Picking the deprecation suppression on 8.14 is the lower-risk
  trade.

### Option C - Bucket4j + spring-data-redis
- Pros: integrates with Spring's `RedisTemplate`.
- Cons: spring-data-redis is a heavyweight abstraction over
  Lettuce; doubles the connection-manager surface area; offers no
  feature that the raw Lettuce path lacks; adds another transitive
  dependency cluster to pin per LD27.

### Option D - Spring Cloud Gateway's RequestRateLimiter +
###            Redis Reactive (token bucket)
- Pros: documented Spring Cloud feature.
- Cons: requires Reactor, but the gateway is the MVC variant
  (ADR-0014). Mixing reactive into the MVC dispatcher would force
  a thread-pool boundary on every request. The Spring Cloud
  rate-limiter also does not emit the canonical
  `X-RateLimit-Remaining` header in the same shape that B5.2
  prescribes, so we would write the same custom filter anyway.

### Option E - In-memory Caffeine + cluster sticky-session
- Pros: zero infra.
- Cons: violates B5.1 (per-JVM bucket); breaks Part 5 contract.

### Option F - Resilience4j RateLimiter
- Pros: already a Resilience4j shop (P3.1 used it).
- Cons: Resilience4j's RateLimiter is per-JVM by design; explicitly
  rejected by its own documentation for distributed scenarios.

## Decision outcome

**Chosen: Option A - Bucket4j 8.14 + raw Lettuce + Redis CAS proxy
manager.**

Concrete shape for v0.1.0:

- Parent `<bucket4j.version>` pinned to 8.14.0; three
  `dependencyManagement` entries:
  - `com.bucket4j:bucket4j_jdk17-core`
  - `com.bucket4j:bucket4j_jdk17-redis-common`
  - `com.bucket4j:bucket4j_jdk17-lettuce`
- log-gateway adds those three plus `io.lettuce:lettuce-core` (no
  version; pulled by spring-boot-dependencies BOM).
- `RateLimitConfig` (gated by `cortex.gateway.rate-limit.enabled`):
  - `rateLimitRedisClient` - one `RedisClient` keyed off
    `cortex.gateway.rate-limit.redis-uri`
  - `rateLimitRedisConnection` - one
    `StatefulRedisConnection<String,byte[]>` with the
    `ByteArrayCodec` shape Bucket4j expects
  - `rateLimitProxyManager` -
    `LettuceBasedProxyManager.builderFor(connection).build()`
    (CAS variant, suppressed deprecation, see LD33)
  - `authenticatedBucketConfiguration` - 100 tokens / 1 minute
  - `anonymousBucketConfiguration` - 20 tokens / 1 minute
- `RateLimitFilter` is `@Order(SecurityProperties.
  DEFAULT_FILTER_ORDER + 1)` so it runs AFTER Spring Security
  (which is at -100) and is keyed off the authenticated principal
  when present, else off `clientIp()` (honouring `X-Forwarded-For`).
- Header policy:
  - `RateLimitFilter` sets `X-RateLimit-Limit`,
    `X-RateLimit-Remaining`, `X-RateLimit-Reset` on every response
    it touches (allowed AND rejected).
  - `GlobalExceptionHandler` sets ONLY `Retry-After` on the 429
    response when it handles `RateLimitedException`. This avoids
    the duplicate-headers anti-pattern.
- Excluded paths (per `cortex.gateway.rate-limit.excluded-paths`)
  default to `/actuator/**` and `/api/v1/health` so liveness/
  readiness probes never get throttled.

## Consequences

Positive:

- One Redis round trip per consumption attempt (CAS bucket atomic).
- Zero extra connection pool: Lettuce already in the classpath.
- Bucket survives gateway restart (Redis-backed); a second replica
  sees the same bucket.
- Canonical `X-RateLimit-*` headers visible on every response.
- Anonymous traffic still throttled (20/min) without forcing a
  login on `/api/v1/health` probes.
- Tests can disable the whole stack with one property
  (`cortex.gateway.rate-limit.enabled=false`) so MockMvc tests do
  not need a live Redis.

Negative / Open:

- We are sitting on a deprecated API surface
  (`LettuceBasedProxyManager.builderFor`). LD33 records the
  upgrade trigger: Bucket4j 8.x line with at least 10 Maven Central
  dependents AND a non-deprecated `Bucket4jLettuce.casBasedBuilder`
  builder shipped. Until then the deprecation is suppressed at the
  single call site with `@SuppressWarnings("deprecation")` and a
  Javadoc back-reference to LD33.
- Redis becomes a hard runtime dependency of the gateway whenever
  `cortex.gateway.rate-limit.enabled=true`. The smoke runbook
  brings it up via the existing `infra/local/docker-compose.smoke.
  yml` so this is invisible in dev.
- The CAS proxy manager uses Redis `GETEX` + `SET XX EX` under the
  hood, which requires Redis >= 6.2. The compose stack already
  uses `redis:7-alpine` so this is satisfied.

## Re-evaluation triggers

- Bucket4j 8.x line publishes
  `Bucket4jLettuce.casBasedBuilder` and the carrier minor version
  reaches at least 10 Maven Central dependents -> swap the call
  site, remove `@SuppressWarnings("deprecation")`, update LD33.
- log-gateway moves to the WebFlux variant (would reverse ADR-0014)
  -> revisit Option D.
- A second gateway pod replica is added in prod (already supported
  by the design; the trigger is to add a CI smoke that boots two
  replicas against one Redis to prove the bucket is shared).

## Related

- ADR-0014 - log-gateway uses Spring Cloud Gateway MVC
- ADR-0016 - CORTEX uses Eureka for local-dev discovery
- LD27 (memory.md) - transitive deps must be pinned in parent
- LD33 (memory.md) - Bucket4j 8.14 deprecation suppression rationale
- Part 5 (strict rules) - reliability / distributed bucket
- Part 7.1.3 (strict rules) - rate-limit headers contract
- Part 26.1 (strict rules) - live Postman/Newman gate
