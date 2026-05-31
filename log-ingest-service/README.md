# log-ingest-service

CORTEX ingestion-side adapter. **Validate -> dedupe -> enrich -> queue
-> Postgres write** for log batches submitted by `log-agent-lib` or any
HTTP client that speaks the `LogEntry` contract.

> P4.0 (this commit) is the **scaffold only**. The endpoint accepts
> a batch and returns `202 Accepted`. Real ingestion behaviour lands
> in P4.1..P4.4 -- see [`plan.md` section 9b](../plan.md).

## Endpoint surface (P4.0)

| Method | Path                         | Status   | Notes                                                     |
|--------|------------------------------|----------|-----------------------------------------------------------|
| POST   | `/api/v1/ingest/batch`       | 202      | `IngestBatchRequest -> IngestAcceptedResponse`            |
| GET    | `/actuator/health`           | 200      | K8s probes                                                |
| GET    | `/actuator/health/liveness`  | 200      | K8s probes                                                |
| GET    | `/actuator/health/readiness` | 200      | K8s probes (requires Eureka registration to flip ready)   |
| GET    | `/actuator/prometheus`       | 200      | Micrometer Prometheus scrape endpoint                     |

## Configuration

| Property                                  | Default                                        | Notes                                                                 |
|-------------------------------------------|------------------------------------------------|-----------------------------------------------------------------------|
| `server.port`                             | `8091`                                         | Module port (D8)                                                      |
| `spring.application.name`                 | `log-ingest-service`                           | Eureka application id                                                 |
| `spring.datasource.url`                   | `jdbc:postgresql://localhost:5432/cortex_ingest` | Override via `CORTEX_INGEST_DB_URL`                                   |
| `spring.datasource.username`              | `cortex_ingest`                                | Override via `CORTEX_INGEST_DB_USERNAME`                              |
| `spring.datasource.password`              | `cortex_ingest`                                | Override via `CORTEX_INGEST_DB_PASSWORD`                              |
| `spring.flyway.enabled`                   | `true`                                         | Runs `V1__baseline.sql` (tenants table + dev seed) on boot            |
| `eureka.client.service-url.defaultZone`   | `http://localhost:8761/eureka/`                | Override via `EUREKA_DEFAULT_ZONE`; K8s deployments use Service DNS   |
| `cortex.security.service-jwt.required`    | `false`                                        | When `true`, every `/api/**` request needs `X-Cortex-Service-JWT` (O8) |
| `cortex.security.service-jwt.expected-issuer` | `""`                                       | Reserved for P5.x signature validation                                |
| `cortex.security.mtls.enabled`            | `false`                                        | Reserved for the P5.x mTLS rollout flag                               |

The mTLS `SslBundle` block in `application.yml` is intentionally
**commented** -- activating it requires a keystore + truststore on
disk in every profile. Uncomment + populate when P5.x rolls mTLS out
cluster-wide. The application boots without it; outbound clients added
in P4.4 will resolve the bundle via `SslBundles` lookup (LD39 pattern).

## Build

The fastest local iteration loop (LD18):

```powershell
# Install parent pom + log-agent-lib peer jar ONCE per session.
.\mvnw.cmd -pl ".,log-agent-lib" install -DskipTests -B

# Iterate on log-ingest-service only after the install above.
.\mvnw.cmd -pl log-ingest-service verify -B
```

## Run locally

```powershell
# 1. Boot Postgres + Redis via the smoke compose:
docker compose -f infra\local\docker-compose.smoke.yml up -d postgres redis

# 2. Boot Eureka registry:
.\mvnw.cmd -pl infra/eureka/eureka-server spring-boot:run -B

# 3. Boot log-ingest-service (dev profile):
.\mvnw.cmd -pl log-ingest-service spring-boot:run `
    -Dspring-boot.run.profiles=dev `
    -Dspring-boot.run.fork=false -B

# 4. Probe:
Invoke-WebRequest http://localhost:8091/actuator/health
$body = @{ entries = @(@{
    timestamp = (Get-Date).ToUniversalTime().ToString("o");
    level = "INFO";
    service = "cortex-cli";
    message = "hello"
    labels = @{ tenant = "cortex-dev" }
}) } | ConvertTo-Json -Depth 4
Invoke-RestMethod -Method Post -Uri http://localhost:8091/api/v1/ingest/batch `
    -ContentType application/json -Body $body
```

## Smoke + Newman (Part 26.1)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke-p4-0.ps1
npx newman run postman\log-ingest.postman_collection.json `
    -e postman\log-ingest.postman_environment_local.json --reporters cli
```

Both must exit 0 before a sub-phase is marked DONE (Part 26.1 + LD40
Definition of WORKING).
