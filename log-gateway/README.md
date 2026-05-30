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
curl -s http://localhost:8080/api/v1/health | jq
curl -s http://localhost:8080/actuator/health | jq
open http://localhost:8080/swagger-ui.html
```

## Configuration

All settings come from `application.yml` plus profile overlays
(`application-dev.yml`, `application-prod.yml`). No `@Value` injection
in production code - typed properties only (rule A6.1).

| Property                    | Default      | Meaning                            |
|-----------------------------|--------------|------------------------------------|
| `cortex.gateway.service`    | `log-gateway`| Logical service name in logs/metrics |
| `cortex.gateway.environment`| `local`      | Deployment label (local/dev/staging/prod) |
| `server.port`               | `8080`       | HTTP listen port                   |
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
