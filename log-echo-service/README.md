# CORTEX :: log-echo-service (throwaway downstream stub)

Throwaway downstream stub used by `log-gateway` smoke tests for
P3.0b..P3.4. See
[ADR-0016](../docs/adr/0016-cortex-uses-eureka-for-local-discovery.md).

**This module is intentionally minimal.** It exists ONLY so the
gateway has a real downstream to satisfy Part 21 Level 4 verification
for the rate-limit (P3.2), AI route (P3.3), and RouteLocator (P3.4)
sub-phases. Deleted when `log-ingest-service` (P4) and
`log-query-service` (P7-ish) take over.

## Endpoints

| Method  | Path        | Description                                   |
|---------|-------------|-----------------------------------------------|
| GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS | `/echo/**` | Mirror back `{upstream, path, method, headers}` |
| GET     | `/actuator/health`            | Boot health probe          |
| GET     | `/actuator/health/liveness`   | K8s liveness probe         |
| GET     | `/actuator/health/readiness`  | K8s readiness probe        |
| GET     | `/actuator/prometheus`        | Micrometer metrics scrape  |

## Run

From repo root:

```powershell
.\mvnw.cmd -pl log-echo-service -am spring-boot:run
```

Or via the smoke docker-compose:

```powershell
docker compose -f infra\local\docker-compose.smoke.yml up log-echo-service
```

The service registers with Eureka at
`http://localhost:8761/eureka/` as application-id `log-echo-service`.

## Verify

```powershell
Invoke-RestMethod http://localhost:8090/actuator/health
# {status: UP, ...}

Invoke-RestMethod http://localhost:8090/echo/ping
# upstream=log-echo-service, path=/echo/ping, method=GET, headers={...}
```
