# CORTEX :: log-gateway

Edge service for the CORTEX log-management cluster. Spring Boot 3.3
application built on **Spring Cloud Gateway MVC** (server-side, blocking
- see [ADR-0014](../docs/adr/0014-log-gateway-uses-spring-cloud-gateway-mvc.md)).

This module is the public ingress for the platform. It owns:

| Capability              | Status     | Sub-phase |
|-------------------------|------------|-----------|
| Health + actuator       | live       | P3.0      |
| Correlation id (MDC)    | live       | P3.0      |
| RFC 7807 error bodies   | live       | P3.0      |
| OpenAPI / Swagger UI    | live       | P3.0      |
| ArchUnit layering tests | live       | P3.0      |
| JWT + API-key auth      | planned    | P3.1      |
| Redis rate limiting     | planned    | P3.2      |
| NL -> LogQL (Spring AI) | planned    | P3.3      |
| Reverse-proxy routes    | planned    | P3.4      |

## Requirements

| Tool      | Version                                    |
|-----------|--------------------------------------------|
| Java      | 17 LTS (Temurin recommended)               |
| Maven     | bundled wrapper (`./mvnw`)                 |
| Docker    | required from P3.1 (Testcontainers, Redis) |

## Build

From the repository root:

```bash
./mvnw -pl log-gateway -am verify
```

This runs Checkstyle, SpotBugs, JaCoCo (>= 80% line, >= 80% branch),
unit tests, and the ArchUnit suite. The build fails fast if any gate is
red.

## Run locally

```bash
./mvnw -pl log-gateway spring-boot:run -Dspring-boot.run.profiles=dev
```

Sanity checks:

```bash
curl -s http://localhost:8090/api/v1/health | jq
curl -s http://localhost:8090/actuator/health | jq
open http://localhost:8090/swagger-ui.html
```

## Configuration

All settings come from `application.yml` plus profile overlays
(`application-dev.yml`, `application-prod.yml`). No `@Value` injection
in production code - typed properties only (rule A6.1).

| Property                    | Default      | Meaning                            |
|-----------------------------|--------------|------------------------------------|
| `cortex.gateway.service`    | `log-gateway`| Logical service name in logs/metrics |
| `cortex.gateway.environment`| `local`      | Deployment label (local/dev/staging/prod) |
| `server.port`               | `8090`       | HTTP listen port                   |
| `management.endpoints.web.exposure.include` | `health,info,metrics,prometheus` | Actuator surface |

### Profiles

| Profile | Selected via                       | Notes                              |
|---------|------------------------------------|------------------------------------|
| `dev`   | `--spring.profiles.active=dev`     | Plaintext console logs, full health detail |
| `prod`  | `--spring.profiles.active=prod`    | JSON logs, restricted actuator     |

## Endpoints (P3.0)

| Method | Path                  | Auth          | Description                          |
|--------|-----------------------|---------------|--------------------------------------|
| GET    | `/api/v1/health`      | public        | Lightweight liveness probe.          |
| GET    | `/actuator/health`    | public (dev)  | Spring Boot health (composite).      |
| GET    | `/actuator/info`      | public (dev)  | Service info.                        |
| GET    | `/actuator/prometheus`| public (dev)  | Micrometer / Prometheus scrape.      |
| GET    | `/v3/api-docs`        | public        | OpenAPI 3 document.                  |
| GET    | `/swagger-ui.html`    | public        | Swagger UI.                          |

Any other request is rejected with `401 Unauthorized` until P3.1 lands
the JWT and API-key filters.

## Routes (P3.4 / ADR-0014 + ADR-0016)

Programmatic Spring Cloud Gateway MVC routes registered in
`GatewayRoutesConfig` (the Java DSL is stable across SCG 4.x and 5.x;
the YAML route prefix moved between minors).

| Predicate              | Filter                      | Target                | Notes                                                |
|------------------------|-----------------------------|-----------------------|------------------------------------------------------|
| `/echo/**`             | `lb("log-echo-service")`    | `lb://log-echo-service` | P3.0b discovery proof.                              |
| `/api/v1/logs/**`      | `rewritePath(/api/v1/logs/(.*) -> /api/v1/ingest/$1)` + `lb("log-ingest-service")` | `lb://log-ingest-service` | P5.0a -- placeholder retired; production route to ingest with path rewrite. |
| `/api/v1/search/**`    | `lb("log-echo-service")`    | `lb://log-echo-service` | P3.4 placeholder until P7 brings up search.         |

No path rewrite is applied; the downstream sees the original path. The
throwaway `log-echo-service` stub mirrors every verb under
`/echo/**`, `/api/v1/logs/**`, and `/api/v1/search/**` so the smoke +
Newman can prove end-to-end routing through Eureka.

## Per-feature rate limit -- `@RateLimitFeature` (P3.4 / ADR-0021)

Declarative custom annotation that adds an independent Bucket4j
sub-bucket on top of the global P3.2 `RateLimitFilter`. Wired via a
Spring MVC `HandlerInterceptor` (`RateLimitFeatureInterceptor`); does
**not** use `spring-aop` or `aspectjweaver` (LD41). Registered on
`/api/**` only by `RateLimitFeatureConfig`.

```java
@PostMapping("/login")
@PreAuthorize("permitAll()")
@RateLimitFeature(
        name = "auth-login",
        capacity = "${cortex.gateway.security.login-rate-limit-capacity:5}",
        refill   = "${cortex.gateway.security.login-rate-limit-refill:PT1M}",
        errorCode = "RATE_LIMITED",
        keyPrefix = "cortex:rl:auth:")
public ResponseEntity<TokenResponse> login(@Valid @RequestBody final LoginRequest request) { ... }
```

Members:

| Member       | Required | Resolves   | Purpose                                                            |
|--------------|----------|------------|--------------------------------------------------------------------|
| `name`       | yes      | literal    | Stable feature id; bucket cache key + log field.                   |
| `capacity`   | yes      | placeholder or literal | Tokens per refill window.                              |
| `refill`     | yes      | placeholder or literal | ISO-8601 `Duration` refill window (e.g. `PT1M`).       |
| `errorCode`  | no       | placeholder or literal | `ErrorCodes` constant surfaced on 429 (default `RATE_LIMITED`). |
| `keyPrefix`  | no       | literal    | Redis key namespace; full key is `<keyPrefix><name>:user:<principal>` (authed) or `<keyPrefix><name>:ip:<remote>` (anonymous). Default `cortex:rl:feat:`. |

On exhaustion the interceptor throws `RateLimitedException` carrying
the resolved `ErrorCodes` so `GlobalExceptionHandler` renders RFC 7807
+ sets `Retry-After`. **Sub-bucket 429s do NOT add the `X-RateLimit-*`
headers** -- only the global `RateLimitFilter` owns those (LD33);
this keeps the per-feature 429 body the only marker for sub-bucket
exhaustion (e.g. `errorCode=NL_QUERY_RATE_LIMITED` vs the global
`errorCode=RATE_LIMITED`).

## Observability

- **Logs**: JSON via Logstash encoder in non-dev profiles (rule A8.1).
  Every line carries `traceId` (and, when present, `tenantId`,
  `userId`) from MDC.
- **Tracing**: deferred to a later P3 sub-phase (Micrometer tracing +
  OpenTelemetry OTLP exporter will be added once a collector endpoint
  is provisioned).
- **Metrics**: Micrometer registry feeds `/actuator/prometheus`.

## Postman

The collection and three environment files live in
[postman/](../postman/). Run via Newman:

```bash
newman run postman/log-gateway.postman_collection.json \
       -e postman/log-gateway.postman_environment_local.json
```

See [postman/README.md](../postman/README.md).

## Architecture rules

`ArchitectureRulesTest` enforces:

- Layering: controllers depend on services; services do not depend on
  controllers.
- DTOs are records (immutability by construction).
- No field-level `@Autowired`; constructor injection only.
- No `System.out` / `System.err` / `java.util.Date` / `java.util.logging`.
- No `RestTemplate` (use `RestClient`/`WebClient`).
- Logger fields are `private static final org.slf4j.Logger`.
- Constants holders are `final` with private constructors that throw.

New violations break the build immediately - this is intentional.

## Troubleshooting

| Symptom                                          | Likely cause                                                  |
|--------------------------------------------------|---------------------------------------------------------------|
| `401` on `/api/v1/health`                        | A custom security override permitted only authenticated; check `SecurityConfig`. |
| Startup fails with `cortex.gateway.service: must not be blank` | Missing yml override; set `--cortex.gateway.service=foo`. |
| `JsonProcessingException` in tests               | A new DTO needs a public no-arg/record constructor.           |
| ArchUnit failure naming a new class              | New class violates one of the layered rules - move it to the correct package. |
