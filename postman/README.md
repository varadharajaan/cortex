# CORTEX Postman collections

This folder satisfies rule 25.2 / 25.3 of the strict-rules contract:
every REST service ships a Postman v2.1 collection plus one environment
file per deployment target.

## Files

| File                                              | Purpose                                            |
|---------------------------------------------------|----------------------------------------------------|
| `log-gateway.postman_collection.json`             | Public + admin + error scenarios for log-gateway. |
| `log-gateway.postman_environment_local.json`      | Local dev (`http://localhost:8080`).              |
| `log-gateway.postman_environment_staging.json`    | Staging cluster.                                  |
| `log-gateway.postman_environment_prod.json`       | Production cluster.                               |

Additional services land here as P4-P8 progress.

## Running locally (Postman UI)

1. Open Postman -> Import -> drop both files above.
2. Pick the `log-gateway :: local` environment in the top-right selector.
3. Start the gateway:

   ```bash
   ./mvnw -pl log-gateway -am spring-boot:run
   ```

4. Run the "Health > GET /api/v1/health" request. Expect 200 and a body
   with `status: "UP"`.

## Running headless (Newman)

[Newman](https://learning.postman.com/docs/collections/using-newman-cli/installing-running-newman/)
is the official CLI runner. CI uses it as a smoke gate.

```bash
# one-time
npm install -g newman

# all environments
newman run postman/log-gateway.postman_collection.json \
       -e postman/log-gateway.postman_environment_local.json \
       --reporters cli,junit \
       --reporter-junit-export target/newman/log-gateway-junit.xml
```

## Environment variables

Every environment file defines:

| Key                       | Purpose                                                          |
|---------------------------|------------------------------------------------------------------|
| `base_url`                | Service root URL. NO trailing slash.                             |
| `tenant_id`               | Default tenant id sent on multi-tenant endpoints.                |
| `auth_username`           | Login user (local env only; dev bootstrap user).                 |
| `auth_password`           | Login password (local env only; dev bootstrap password).         |
| `jwt`                     | Access token. Populated dynamically by the Login + Refresh hooks.|
| `refresh_token`           | Current refresh token. Rotates on every successful refresh.      |
| `consumed_refresh_token`  | Snapshot of the original refresh token (set in Login) replayed by the single-use test to prove rule B7.5. |
| `jwt_token`               | Legacy alias kept for backward compatibility; unused by P3.1.    |

## Adding a request

1. Place the request under one of the existing folders (`Auth`,
   `Health`, `Admin`, `Error Scenarios`) or create a new folder for a
   new functional area.
2. Always send `X-Request-Id: {{request_id}}` so the gateway echoes
   the same correlation id back; the collection's top-level test hook
   asserts the echo.
3. Add a `pm.test(...)` block that asserts the HTTP status and at least
   one body field.
4. **Mirror in `scripts/smoke-p3-N.ps1`** -- LD23 requires that every
   HTTP behavior checked by the Postman collection is also checked by
   the equivalent sub-phase smoke script (and vice versa) so that
   `mvn verify` -> smoke -> Newman is a closed loop. If you fix or add
   any endpoint, update BOTH the collection and the smoke script in
   the same commit batch.
5. Re-export the collection from Postman to keep this file in sync
   (or hand-edit; the file is human-readable JSON v2.1).

## Current request matrix (P3.3, 26 requests / 90+ assertions)

| Folder           | Request                                            | Purpose                                                |
|------------------|----------------------------------------------------|--------------------------------------------------------|
| Auth             | POST `/api/v1/auth/login` (good)                   | 200 + token shape + HS256 header + accessExpiresAt ISO |
| Auth             | POST `/api/v1/auth/login` (blank password)         | 400 VALIDATION_FAILED                                  |
| Auth             | POST `/api/v1/auth/login` (bad credentials)        | 401 UNAUTHENTICATED                                    |
| Auth             | POST `/api/v1/auth/refresh` (rotates)              | 200 + rotated pair (tokens differ)                     |
| Auth             | POST `/api/v1/auth/refresh` (replay consumed)      | 401 UNAUTHENTICATED (rule B7.5 single-use)             |
| Auth             | GET  `/api/v1/health` (WITH bearer)                | 200 + bearer parsed through resource-server chain      |
| Auth             | GET  `/api/v1/health` (tampered bearer)            | 401 (HMAC verification fails)                          |
| Health           | GET  `/api/v1/health` (public)                     | 200 + {status:UP, service:log-gateway}                 |
| Admin            | GET  `/actuator/health`                            | 200 + status UP                                        |
| Admin            | GET  `/actuator/health/liveness`                   | 200 + status UP (K8s probe)                            |
| Admin            | GET  `/actuator/health/readiness`                  | 200 + status UP (K8s probe)                            |
| Admin            | GET  `/actuator/info`                              | 200 + JSON object                                      |
| Admin            | GET  `/actuator/prometheus`                        | 200 + `http_server_requests_seconds_count` + `jvm_memory_used_bytes` |
| Admin            | GET  `/v3/api-docs`                                | 200 + paths include /auth/login, /auth/refresh, /health|
| Admin            | GET  `/swagger-ui/index.html`                      | 200 + HTML body                                        |
| Discovery        | GET  `/echo/ping` (no bearer)                      | 401 (chain enforced before lb:// resolution)           |
| Discovery        | GET  `/echo/ping` (bearer)                         | 200 + upstream=log-echo-service + Authorization survived + X-Tenant-Id propagated |
| Discovery        | POST `/echo/foo/bar` (bearer)                      | 200 + upstream=log-echo-service + method=POST + path=/echo/foo/bar |
| Error Scenarios  | GET  unknown path WITH bearer                      | 404 NOT_FOUND (proves NoResourceFoundException fix)    |
| Error Scenarios  | GET  unknown path NO bearer                        | 401                                                    |
| NlQuery          | POST `/api/v1/query/nl` (trigger:HAPPY)            | 200 + non-empty logql with valid leading token + confidence in [0,1] + non-empty explanation |
| NlQuery          | POST `/api/v1/query/nl` (trigger:SCHEMA_MISS)      | 422 + RFC 7807 errorCode=NL_QUERY_INVALID              |
| NlQuery          | POST `/api/v1/query/nl` (trigger:REFUSAL)          | 422 + RFC 7807 errorCode=NL_QUERY_REFUSED              |
| RateLimit        | GET  `/echo/ping` (bearer)                         | 200 + X-RateLimit-Limit=100 + Remaining digits + Reset 1..60 |
| RateLimit        | POST `/api/v1/auth/login` (bad creds, no bearer)   | 401 + X-RateLimit-Limit=20 + Remaining digits + Reset 1..60 + errorCode=UNAUTHENTICATED |
| RateLimit        | GET  `/echo/ping` (bearer, after pre-request flood)| 429 + Retry-After present + X-RateLimit-Remaining=0 + RFC 7807 errorCode=RATE_LIMITED |

Folder order matters: Auth populates `jwt` + `refresh_token` + `consumed_refresh_token` for the later folders. Run with `--bail` to fail fast on the first regression. The Discovery folder requires the throwaway `log-echo-service` stub + the standalone Eureka registry to be running (see `scripts/smoke-p3-0b.ps1`); skip the folder when running Newman against a deployment that does not include `log-echo-service`. The NlQuery folder requires WireMock running on `:8081` with the `infra/local/wiremock/mappings/` stubs mounted AND the gateway booted with `SPRING_AI_OLLAMA_BASE_URL=http://localhost:8081`; the sub-bucket-exhaustion 429 case is covered by `scripts/smoke-p3-3.ps1` only because it needs deterministic Redis state. Reset `cortex:rl:nlq:*` keys in Redis between the smoke and the newman run so the NlQuery folder starts with a full sub-bucket. The RateLimit folder MUST run LAST because its 429 test floods ~110 requests against `/echo/ping` to exhaust the principal-keyed Bucket4j counter in Redis; any subsequent authenticated, non-excluded request would also see 429 until the 1-minute refill window elapses. The RateLimit folder requires a live Redis (see `scripts/smoke-p3-2.ps1` and `infra/local/docker-compose.smoke.yml`); skip it when running Newman against a deployment where `cortex.gateway.rate-limit.enabled=false`.

## Rules anchor

- 25.2: Postman collection + per-environment files MANDATORY for every
  REST service.
- 25.3: collection MUST live under `postman/` at the repo root.
- 25.5: every request carries `X-Request-Id` so correlation works end
  to end (see also rule 17.5).
- 25.7: folder layout is `Auth/` (added in P3.1), per-module folders,
  `Discovery/` (added in P3.0b), `Admin/`, `Error Scenarios/`, `NlQuery/`
  (added in P3.3), `RateLimit/` (added in P3.2).
- B5.1 / B5.2 (strict rules): the `RateLimit/` folder asserts the
  distributed Bucket4j+Redis token-bucket contract and the canonical
  `X-RateLimit-*` + `Retry-After` headers on both allowed (200) AND
  rejected (401 / 429) responses; the 429 path additionally asserts the
  RFC 7807 problem body with `errorCode=RATE_LIMITED`.
- B20.1 / ADR-0018 (strict rules): the `NlQuery/` folder asserts the
  Spring AI ChatClient -> Ollama natural-language to LogQL contract.
  Three outcome paths (happy / schema-miss / refusal) are exercised
  against the WireMock `/api/chat` stubs under
  `infra/local/wiremock/mappings/`. The 422 paths must surface RFC 7807
  with `errorCode=NL_QUERY_INVALID` or `NL_QUERY_REFUSED` respectively;
  the happy path must produce a valid `{logql, confidence, explanation}`
  body with the LogQL starting with one of the allowed leading tokens
  (stream selector `{` or scalar aggregation).
