# CORTEX Postman collections

This folder satisfies rule 25.2 / 25.3 of the strict-rules contract:
every REST service ships a Postman v2.1 collection plus one environment
file per deployment target.

## Files

| File                                              | Purpose                                            |
|---------------------------------------------------|----------------------------------------------------|
| `log-gateway.postman_collection.json`             | Public + admin + error scenarios for log-gateway. |
| `log-gateway.postman_environment_local.json`      | Local dev (`http://localhost:8090`).              |
| `log-gateway.postman_environment_staging.json`    | Staging cluster.                                  |
| `log-gateway.postman_environment_prod.json`       | Production cluster.                               |
| `log-ingest.postman_collection.json`              | Ingest happy-path + RFC 7807 error contract + P4.1 multi-tenant/jsonb labels + P4.2 Redis SETNX hot-path dedupe (Idempotency-Key) + server-side PII masking + Prometheus counter scrape + P4.3 server-side enrichment (correlation-id, JWT tid, label normalization, geo_country stub) + P4.4 outbox counter families (published, failed, dlq) + P4.4 outbox-publish delta after 4s settle. |
| `log-ingest.postman_environment_local.json`       | Local dev (`http://localhost:8092`).              |
| `log-ingest.postman_environment_staging.json`     | Staging cluster (P5.2a closer).                   |
| `log-ingest.postman_environment_prod.json`        | Production cluster (P5.2a closer).                |
| `log-processor.postman_collection.json`           | Actuator + end-to-end pipeline (gateway -> ingest -> Kafka -> processor; assert `cortex.processor.events.consumed_total` and `classified_total{outcome=anomaly}` deltas) + P5.3 `cortex.processor.sink.{loki,quickwit}.published_total` baseline + delta (conditional on family presence so disabled sinks don't false-fail; ADR-0030) + error scenarios. P5.3 closer. |
| `log-processor.postman_environment_local.json`    | Local dev (`http://localhost:8095`; full stack via `scripts/p5-3/boot-full-stack.ps1`). |
| `log-processor.postman_environment_staging.json`  | Staging cluster (P5.2a closer).                   |
| `log-processor.postman_environment_prod.json`     | Production cluster (P5.2a closer).                |

Additional services land here as P5-P8 progress.

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

## Current request matrix

### log-gateway collection (P3.4, 29 requests / 100+ assertions)

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
| LogsRoute        | POST `/api/v1/logs/batch` (bearer)                 | 202 + receivedCount + receivedAt (gateway rewrites to `/api/v1/ingest/batch` and forwards to `lb://log-ingest-service`; P5.0a contract migration from the retired echo placeholder) |
| SearchRoute      | GET  `/api/v1/search/echo` (bearer)                | 200 + upstream=log-echo-service + path=/api/v1/search/echo (no rewrite) |
| Error Scenarios  | GET  unknown path WITH bearer                      | 404 NOT_FOUND (proves NoResourceFoundException fix)    |
| Error Scenarios  | GET  unknown path NO bearer                        | 401                                                    |
| NlQuery          | POST `/api/v1/query/nl` (trigger:HAPPY)            | 200 + non-empty logql with valid leading token + confidence in [0,1] + non-empty explanation |
| NlQuery          | POST `/api/v1/query/nl` (trigger:SCHEMA_MISS)      | 422 + RFC 7807 errorCode=NL_QUERY_INVALID              |
| NlQuery          | POST `/api/v1/query/nl` (trigger:REFUSAL)          | 422 + RFC 7807 errorCode=NL_QUERY_REFUSED              |
| RateLimit        | GET  `/echo/ping` (bearer)                         | 200 + X-RateLimit-Limit=100 + Remaining digits + Reset 1..60 |
| RateLimit        | POST `/api/v1/auth/login` (bad creds, no bearer)   | 401 + X-RateLimit-Limit=20 + Remaining digits + Reset 1..60 + errorCode=UNAUTHENTICATED |
| RateLimit        | GET  `/echo/ping` (bearer, after pre-request flood)| 429 + Retry-After present + X-RateLimit-Remaining=0 + RFC 7807 errorCode=RATE_LIMITED |

Folder order matters: Auth populates `jwt` + `refresh_token` + `consumed_refresh_token` for the later folders. Run with `--bail` to fail fast on the first regression. The Discovery + SearchRoute folders require the throwaway `log-echo-service` stub + the standalone Eureka registry to be running (see `scripts/smoke-p3-0b.ps1` and `scripts/smoke-p3-4.ps1`); skip those folders when running Newman against a deployment that does not include `log-echo-service`. The LogsRoute folder requires `log-ingest-service` + Postgres + Kafka + Eureka (see `scripts/smoke-p4-0.ps1` and the local P5.0 stack) because the gateway now rewrites `/api/v1/logs/**` to `/api/v1/ingest/**` and forwards to `lb://log-ingest-service`. The NlQuery folder requires WireMock running on `:8094` with the `infra/local/wiremock/mappings/` stubs mounted AND the gateway booted with `SPRING_AI_OLLAMA_BASE_URL=http://localhost:8094`; the sub-bucket-exhaustion 429 case is covered by `scripts/smoke-p3-3.ps1` only because it needs deterministic Redis state. The AuthLogin sub-bucket exhaustion case (`@RateLimitFeature` on `/api/v1/auth/login`, P3.4 / ADR-0021) is likewise covered only by `scripts/smoke-p3-4.ps1` because Newman would otherwise lock the `admin` principal's login bucket for the rest of the run. Reset `cortex:rl:nlq:*` + `cortex:rl:auth:*` keys in Redis between the smoke and the newman run so the NlQuery + Auth folders start with full buckets. The RateLimit folder MUST run LAST because its 429 test floods ~110 requests against `/echo/ping` to exhaust the principal-keyed Bucket4j counter in Redis; any subsequent authenticated, non-excluded request would also see 429 until the 1-minute refill window elapses. The RateLimit folder requires a live Redis (see `scripts/smoke-p3-2.ps1` and `infra/local/docker-compose.smoke.yml`); skip it when running Newman against a deployment where `cortex.gateway.rate-limit.enabled=false`.

### log-ingest collection (P4.4c, 18 requests / 46 assertions)

| # | Request                                                                                | Purpose                                                                                                          |
|---|----------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| 1 | GET  `/actuator/health`                                                                | 200 + `status: UP` (live-boot readiness gate, Part 21 level 2).                                                  |
| 2 | POST `/api/v1/ingest/batch` (happy)                                                    | 202 + `receivedCount=N` + ISO-8601 `receivedAt` (P4.0 baseline contract).                                        |
| 3 | POST `/api/v1/ingest/batch` (empty `entries`)                                          | 400 + RFC 7807 `errorCode=VALIDATION_FAILED` (Part 26.3.4 input-validation gate).                                |
| 4 | POST `/api/v1/ingest/batch` (malformed JSON)                                           | 400 + RFC 7807 `errorCode=BAD_REQUEST` (parser-error path).                                                      |
| 5 | GET  `/api/v1/ingest/unknown`                                                          | 404 + RFC 7807 `errorCode=NOT_FOUND` (Part 26.3.3 unknown-path gate).                                            |
| 6 | POST `/api/v1/ingest/batch` (P4.1 rich `labels` map)                                   | 202 + jsonb roundtrip survives (multi-tenant + label cardinality).                                               |
| 7 | POST `/api/v1/ingest/batch` (P4.1, NO `X-Tenant-Id`)                                   | 400 + RFC 7807 `errorCode=VALIDATION_FAILED` (tenant-header contract, ADR-0022).                                 |
| 8 | POST `/api/v1/ingest/batch` (P4.1, same `event_id` twice)                              | both 202; second call dedup'd by Postgres `UNIQUE(tenant_id, event_id)` cold path (transparent to client).       |
| 9 | POST `/api/v1/ingest/batch` (P4.2, `Idempotency-Key` first send)                       | 202 + `receivedCount=1` (Redis SETNX claims key `cortex:ingest:idem:{tenant}:{idemKey}` with PT24H TTL).         |
|10 | POST `/api/v1/ingest/batch` (P4.2, SAME `Idempotency-Key` replay)                      | 202 absorbed by hot path; `cortex.ingest.dedupe.hits{path=hot}` increments (D3 contract).                        |
|11 | POST `/api/v1/ingest/batch` (P4.2, PII in `message`)                                   | 202; persisted row has `message` rewritten with mask tokens (`<EMAIL>`, `<PHONE>`, etc.); D4 server-side leg.    |
|12 | GET  `/actuator/prometheus`                                                            | 200 + scrape contains `cortex_ingest_dedupe_hits_total` (with `path` tag) + `cortex_ingest_mask_applied_total`. |
|13 | POST `/api/v1/ingest/batch` (P4.3, `X-Correlation-Id`)                                 | 202 + `receivedCount=1`; correlation header propagates to `labels.trace_id` on the persisted row (DB-asserted by `scripts/smoke-p4-3.ps1` step 04, LD23 mirror). |
|14 | POST `/api/v1/ingest/batch` (P4.3, JWT `tid` claim + `X-Tenant-Id`)                    | 202 + `receivedCount=1`; JWT `tid` wins -- persisted row `labels.tenant` matches the JWT claim, header is ignored (ADR-0024 D2; DB-asserted by smoke-p4-3.ps1 step 06).    |
|15 | POST `/api/v1/ingest/batch` (P4.3, mixed-case + padded label keys)                     | 202 + `receivedCount=1`; persisted row collapses `Env` -> `env` and trims `"  Region  "` -> `region: eu-west` (ADR-0024 D4; DB-asserted by smoke-p4-3.ps1 step 08). |
|16 | POST `/api/v1/ingest/batch` (P4.3, geo stub)                                           | 202 + `receivedCount=1`; persisted row has `labels.geo_country = unknown` and `received_at` populated by server (ADR-0024 D6; DB-asserted by smoke-p4-3.ps1 step 09). |
|17 | GET  `/actuator/prometheus` (P4.4 counter families)                                    | 200 + scrape contains `cortex_ingest_outbox_published_total` + `cortex_ingest_outbox_failed_total` + `cortex_ingest_outbox_dlq_total` (counter registration, ADR-0027). |
|18 | POST `/api/v1/ingest/batch` + 4s settle + re-scrape (P4.4 publish delta)               | 202 then `cortex_ingest_outbox_published_total` strictly increases after the `@Scheduled(fixedDelay=1s)` outbox poller drains the row (ADR-0026 D2 + D3; mirrored by `scripts/p4-4c/`). |

### log-processor collection (P5.3, 13 requests / 53 assertions)

| Folder            | Request                                                                                   | Purpose                                                                                                                |
|-------------------|-------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| Auth              | POST `{{gateway_base_url}}/api/v1/auth/login`                                             | 200 + populate `jwt` + `refresh_token` env vars; assert HS256 header (ADR-0015).                                       |
| Admin (actuator)  | GET `{{base_url}}/actuator/health`                                                        | 200 + `status: UP`.                                                                                                    |
| Admin (actuator)  | GET `{{base_url}}/actuator/health/liveness`                                               | 200 + `status: UP` (K8s probe).                                                                                        |
| Admin (actuator)  | GET `{{base_url}}/actuator/health/readiness`                                              | 200 + `status: UP` (K8s probe).                                                                                        |
| Admin (actuator)  | GET `{{base_url}}/actuator/info`                                                          | 200 + JSON object.                                                                                                     |
| Admin (actuator)  | GET `{{base_url}}/actuator/metrics`                                                       | 200 + `names` includes `cortex.processor.events.{consumed,parsed,classified}_total`.                                   |
| Admin (actuator)  | GET `{{base_url}}/actuator/prometheus`                                                    | 200 + exposition contains `cortex_processor_events_{consumed,parsed,classified}_total` (counter registration) + conditional `cortex_processor_sink_{loki,quickwit}_published_total` presence checks when sinks are enabled (P5.3 / ADR-0030). |
| Metrics-Baseline  | GET `{{base_url}}/actuator/prometheus` (snapshot)                                         | 200 + capture `consumed_total` sum + `classified_total{outcome=anomaly}` + `sink_loki_published_baseline` + `sink_quickwit_published_baseline` into env vars for the delta assertion (P5.3 sink baselines are zero when the sinks are disabled, which is fine).        |
| Pipeline          | POST `{{gateway_base_url}}/api/v1/logs/batch` (anomaly-looking ERROR entry, bearer)        | 202 + `receivedCount=1` (gateway -> ingest -> outbox -> Kafka).                                                        |
| Pipeline          | GET `{{base_url}}/actuator/health` (4s settle for outbox poller + Kafka consumer drain)   | 200; settle window before Metrics-After reads.                                                                         |
| Metrics-After     | GET `{{base_url}}/actuator/prometheus` (delta)                                            | 200 + `consumed_total` strictly increased (>= +1) + `classified_total{outcome=anomaly}` strictly increased (>= +1) + `cortex.processor.sink.{loki,quickwit}.published_total` strictly increased (>= +1) conditional on the family being present in the scrape (P5.3 / ADR-0030 fan-out; family-absent path is a no-op so disabled sinks don't false-fail). |
| Error Scenarios   | GET `{{base_url}}/actuator/no-such-endpoint`                                              | 404 (Spring Boot default error contract on an unknown actuator path).                                                  |
| Error Scenarios   | GET `{{base_url}}/no-such-root-path`                                                      | 404 (Spring Boot default error contract on an unknown root path).                                                      |

Folder order matters: `Auth` populates `jwt`, `Metrics-Baseline` snapshots the counters (including the P5.3 sink baselines), `Pipeline` publishes through the gateway and then waits 4 seconds for the outbox poller + Kafka consumer to drain, and `Metrics-After` reads `/actuator/prometheus` on the processor and asserts the deltas (event-pipeline counters always; sink counters only when the family is present in the scrape, so the same collection passes against a stack with sinks disabled and against a stack with `cortex.processor.sinks.{loki,quickwit}.enabled=true`). Run with `--bail`. The pipeline assertion REQUIRES the full stack booted via `scripts/p5-3/boot-full-stack.ps1` so that the processor is started with `CORTEX_PROCESSOR_CLASSIFIER=spring-ai` + `SPRING_AI_OLLAMA_BASE_URL=http://localhost:8094` + `CORTEX_PROCESSOR_SINKS_LOKI_ENABLED=true` + `CORTEX_PROCESSOR_SINKS_QUICKWIT_ENABLED=true` and WireMock on `:8094` returns a deterministic anomaly verdict + the Loki / Quickwit stub mappings under `infra/local/wiremock/mappings/{loki-push,quickwit-ingest}.json` (ADR-0030 D6) return 204 / 200. Newman invocation: `npx newman run postman\log-processor.postman_collection.json -e postman\log-processor.postman_environment_local.json --reporters cli --bail`.

Mirror in `scripts/p5-3/smoke-p5-3.ps1` (LD23 / LD101 closed-loop rule): every HTTP-observable assertion in the collection above has a matching assertion in the smoke script (and vice versa). Items 1-7 mirror smoke-p5-3 steps 01-07; items 8-11 mirror steps 08-11 plus the sink baseline + delta legs (loki + quickwit `published_total` strictly increases by 1 after the pipeline leg, asserted against the same WireMock journal that the smoke script checks); items 12-13 are negative-path probes that the smoke script also performs.

Just import and run: open Postman, import `log-processor.postman_collection.json` and `log-processor.postman_environment_local.json`, pick the `log-processor-service :: local` environment, boot the full stack with `scripts\p5-3\boot-full-stack.ps1`, and hit "Run collection".

Mirror in `scripts/smoke-p4-0.ps1` / `scripts/smoke-p4-1.ps1` / `scripts/smoke-p4-2.ps1` / `scripts/smoke-p4-3.ps1` (LD23 / Part 26.2 closed-loop rule): every HTTP-observable assertion in the collection above has a matching assertion in the per-phase smoke script (and vice versa). Items 2-5 mirror smoke-p4-0; items 6-8 mirror smoke-p4-1; items 9-12 mirror smoke-p4-2; items 13-16 mirror smoke-p4-3. The smoke-only assertions (DB row-count baselines, `redis-cli GET`, `labels::text` JSON inspection, run-log ERROR grep, eureka registry scrape) are intentionally not in Newman because they are not HTTP-observable from the service boundary.

## Rules anchor

- 25.2: Postman collection + per-environment files MANDATORY for every
  REST service.
- 25.3: collection MUST live under `postman/` at the repo root.
- 25.5: every request carries `X-Request-Id` so correlation works end
  to end (see also rule 17.5).
- 25.7: folder layout is `Auth/` (added in P3.1), per-module folders,
  `Discovery/` (added in P3.0b), `LogsRoute/` + `SearchRoute/` (added in
  P3.4), `Admin/`, `Error Scenarios/`, `NlQuery/` (added in P3.3),
  `RateLimit/` (added in P3.2).
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
