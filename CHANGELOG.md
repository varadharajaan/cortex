# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- P16 E2E + load testing with Gatling (ADR-0060). New standalone
  `log-load-tests` Maven module (outside the reactor) with a `GatewayLoadSimulation`
  that authenticates against the gateway and drives the NL-to-LogQL and
  searchLogs read paths, asserting the gateway round-trip SLOs (p95 latency +
  success rate) so a regression fails the build. A `load-test` CI job boots the
  compose stack, runs `gatling:test` against it, and uploads the Gatling HTML
  report. All load knobs are `-D` tunables.

- P15 Postman + Newman in CI (ADR-0059). A `newman` job in
  `.github/workflows/ci.yml` boots the P10 compose stack, waits for the gateway
  health endpoint, and runs the six committed per-service Postman collections
  (gateway, ingest, processor, remediation, indexer, monitoring) inside a
  `postman/newman:6-alpine` container attached to `cortex-net`, each targeting
  its in-network service DNS via a `base_url` override. A Newman failure fails
  the pipeline; compose logs are dumped on failure and the stack is always torn
  down.

- P14 full CI/CD (ADR-0058). Replaced the P0 GitHub Actions stub with a
  production CI/CD workflow: PR commitlint, root `./mvnw -B -ntp verify`
  with Docker available for Testcontainers, retained Maven verification
  artifacts, a matrix build for all eight P10 runtime images, a blocking Trivy
  vulnerability scan of every built image (with a documented `.trivyignore`
  allowlist for framework-locked residuals), release-tooling readiness checks
  with cosign installed, GHCR publish on trusted `main` / `v*.*.*` events only,
  and keyless cosign signing for pushed image digests. Pull requests build and
  scan images but never push or sign.
- P14 image-CVE backlog burndown (#156). Bumped Spring Boot 3.3.6 -> 3.3.13 and
  Spring AI 1.0.0 -> 1.0.8, pinned Netty 4.1.135.Final, Tomcat 10.1.55, XStream
  1.4.21, BouncyCastle 1.84, Jersey 3.1.10, PostgreSQL 42.7.11, and lz4-java
  1.8.1, bumped the standalone `infra/eureka` pom the same way, and added
  `apt-get upgrade` to every runtime Dockerfile stage, clearing the in-stack
  HIGH/CRITICAL image findings on the patched 3.3.x baseline. The residual
  framework-locked CVEs (spring-core, spring-security-web, spring-boot, and
  kafka-clients, each fixable only via a Boot 3.4+/Spring Kafka 3.3+ migration)
  are allowlisted in `.trivyignore` and tracked in #156.

- P8.3 counter-family SLO backend (ADR-0046 Amendment 4). Added
  `CounterFamilySloBudgetEngine`, optional
  `SloDefinition.counterFamily` source binding, a Prometheus text
  counter-family parser, and the named `counterFamilySloRestClient` so
  monitoring can derive success-ratio SLIs from target services'
  `/actuator/prometheus` endpoints over Eureka. The backend reuses the
  existing `cortex.monitoring.slo_budget_remaining` and
  `cortex.monitoring.slo_burn_rate` gauges, maps no-data / HTTP /
  transport failures into bounded `SloSnapshot` outcomes, and can boot
  alongside the eureka-actuator probe backend without `RestClient`
  ambiguity.

- P8.4-P8.7+ advanced SLO backends (ADR-0046 Amendment 5). Added
  `TimerPercentileSloBudgetEngine`, `PromQlSloBudgetEngine`,
  `CompositeSloBudgetEngine`, `OtelSloBudgetEngine`, `SloSnapshotStore`,
  source-aware `SloBudgetEngine.supports(...)`, and `mixed` mode so one
  evaluator tick can route latency, PromQL, composite, OTel, counter-family,
  and availability SLO definitions to the owning backend. The advanced
  backends preserve the existing SLO gauge surface and add named HTTP clients
  plus typed properties for timer-percentile, PromQL, and OTel sources.

- P9.3b gateway `getAnomalies` REST + GraphQL parity (ADR-0049 Amendment 6),
  the fourth and final ADR-0004 read query. A shared `GetAnomaliesService`
  forwards to log-remediation-service (P9.3a) over `lb://` via the blocking
  `LoadBalancerClient` + a plain `remediationRestClient`; REST
  `GET /api/v1/anomalies?since=&until=&limit=` (`GetAnomaliesController`) and
  GraphQL `getAnomalies(since, until, limit): [Anomaly!]!`
  (`GetAnomaliesGraphQlController`) resolve identically. The tenant is read
  from `X-Tenant-Id` (REST header / GraphQL context) and forwarded downstream
  as the `tenantId` query parameter. Three new error codes
  (`GET_ANOMALIES_RATE_LIMITED` -> 429, `_INVALID` -> 422, `_UPSTREAM_FAILED`
  -> 502) + a shared `@RateLimitFeature("get-anomalies")` sub-bucket; gate
  `cortex.gateway.get-anomalies.enabled`. Closes the P9 GraphQL-parity track.

### Fixed

- log-ingest-service unknown-tenant ingest now returns `400 VALIDATION_FAILED`
  instead of `500` (ADR-0022 Amendment 3). Posting a batch for an
  unprovisioned tenant tripped a Postgres foreign-key violation on
  `raw_logs.tenant_id` that surfaced as a generic server error;
  `GlobalExceptionHandler` gains one arm for
  `DataIntegrityViolationException` / `DbActionExecutionException` that maps it
  to an RFC 7807 400 with a bounded, non-leaking message (the SQL / constraint
  name is logged server-side only, never echoed -- OWASP A09). Caught by the
  P10.1 live end-to-end sweep.

### Added

- P10.1 compose-stack observability + fan-out wiring (no service code changes).
  Added `infra/docker/prometheus.yml` and a `cortex-prometheus` service that
  scrapes `cortex-monitoring` in-network and loads the shared SLO burn-rate
  rules; enabled the monitoring SLO engine (`micrometer-derivation`) and the
  processor's classifier + Loki/Quickwit sinks against the in-stack WireMock so
  the per-service Postman/Newman collections exercise the full pipeline; exposed
  the named `quickwit` / `monitoring` health contributors. All six service
  collections pass in-network (368 assertions / 0 failures) alongside the ring
  smoke and the P11/P12/P13 infra gates.


  operator runbook and draft release notes plus guarded release scripts under
  `scripts/release` for preflight, SBOM generation, cosign image signing, and
  GitHub Release creation. Publishing is explicitly guarded: image signing
  requires `-ConfirmSign SIGN`, release creation requires
  `-ConfirmRelease RELEASE`, and the dry-run path does not tag, sign, push, or
  publish. Verified by `scripts/live-e2e/smoke-p18-release-prep.ps1`
  (PASS 4 / FAIL 0). Actual v0.1.0 publication remains pending operator
  approval.

- P17 Grafana dashboards + SLO operator surface (ADR-0056). Added
  `infra/grafana` with provisioned Prometheus datasource
  (`cortex-prometheus`), `CORTEX Overview` and `CORTEX SLO` dashboards, and a
  CORTEX availability SLO catalog mirroring the runtime defaults in
  `log-monitoring-service`. Wired Grafana into the local smoke compose and the
  full Docker compose stack on `:3000`; Prometheus remains the alert-rule
  source of truth via `infra/local/alerts/slo-burn-rate.rules.yml`. Verified by
  `scripts/live-e2e/smoke-p17-grafana.ps1` (PASS 7 / FAIL 0: dashboard JSON,
  SLO catalog, `promtool`, live Prometheus/Grafana boot, datasource/dashboard
  API proof, alert rules loaded).

- P13: Ansible operational orchestration (ADR-0055). Added
  `infra/ansible` with local inventory, shared variables, and playbooks for
  Terraform validation/provision, Helm deploy, Helm rollback, and rollout +
  in-cluster gateway health smoke. The playbooks delegate to Terraform,
  Helm, and kubectl instead of redefining infrastructure or Kubernetes
  manifests. Terraform apply is guarded by both
  `cortex_terraform_apply=true` and `cortex_confirm_apply=APPLY`. Added
  `scripts/live-e2e/smoke-p13-ansible.ps1`, verified with containerized
  `ansible-core 2.17.7` syntax checks for all playbooks (PASS 2 / FAIL 0).
  No real apply, deploy, rollback, or cluster smoke was executed.

- P12: Terraform Azure infrastructure scaffold (ADR-0054). Added
  `infra/terraform` root stack for Resource Group, AKS with OIDC/workload
  identity, ACR with AKS `AcrPull`, Log Analytics, Application Insights,
  Key Vault with RBAC, Storage account + private Blob containers, Azure
  Service Bus namespace/topics/subscriptions, and optional PostgreSQL/Redis
  resources behind explicit toggles. Terraform owns infrastructure only and
  outputs Helm handoff values; P11 remains the app manifest layer and P13
  will own deploy/rollback/smoke orchestration. Added
  `scripts/live-e2e/smoke-p12-terraform.ps1`, verified with
  `terraform fmt -recursive -check`, `terraform init -backend=false`, and
  `terraform validate` (PASS 4 / FAIL 0). No `terraform apply` was run.

- P11: Helm deployment layer for Kubernetes (ADR-0053). Added
  `infra/helm/cortex` umbrella chart plus one service chart per runnable
  component: `cortex-eureka`, `cortex-gateway`, `cortex-ingest`,
  `cortex-processor`, `cortex-remediation`, `cortex-indexer`,
  `cortex-monitoring`, and `cortex-echo`. A shared `cortex-common` library
  template renders ServiceAccount, ConfigMap/Secret, Deployment, Service,
  optional Ingress, and optional HPA while preserving the P10 canonical
  `cortex-<component>` names as Kubernetes Deployment/Service names. The
  chart owns application workloads only; stateful dependencies remain
  external inputs from P10 local infrastructure or future P12 Azure outputs.
  Added `values-prod.example.yaml` for P12 handoff and
  `scripts/live-e2e/smoke-p11-helm.ps1`, verified with Helm lint/template,
  kubectl client dry-run, and Docker Desktop Kubernetes server dry-run
  (PASS 6 / FAIL 0).

- P9.3/P9.3a: `log-remediation-service` anomalies read model and direct
  REST backer (ADR-0052). Valid parsed anomaly CloudEvents are copied into
  a remediation-owned Postgres `anomalies` table through a fail-open writer
  in `AnomalyConsumer`; duplicates are absorbed by `(tenant_id,event_id)`.
  New `GET /api/v1/anomalies?tenantId&since&until&limit` returns
  tenant-scoped newest-first anomaly rows and maps validation failures to
  `400 VALIDATION_FAILED`. Added Flyway migration, JDBC repository/query
  service/controller, Postman/Newman coverage, and
  `scripts/live-e2e/smoke-p9-3a.ps1` for live Kafka -> read model -> API
  verification. P9.3b gateway REST + GraphQL parity remains the next
  boundary.

- P10.1: `infra/docker/docker-compose.yml` full CORTEX stack (ADR-0050
  Amendment 1) -- composes the eight P10.0 images into one runnable stack on
  a single `cortex-net` bridge, on top of the datastores, so `docker compose
  up` brings the whole ring online with container-to-container DNS (the
  wired-up health P10.0 deferred). Canonical `cortex-<component>` naming,
  identical across Docker and Kubernetes (container name == DNS host == future
  K8s Deployment/Service + pod prefix): `cortex-eureka`, `cortex-gateway`,
  `cortex-ingest`, `cortex-processor`, `cortex-remediation`, `cortex-indexer`,
  `cortex-monitoring`, `cortex-echo` + datastores `cortex-postgres`,
  `cortex-redis`, `cortex-kafka`, `cortex-quickwit`, `cortex-wiremock` (the
  bridge stack keeps its `cortex-smoke-*` names). Only the gateway (`:8090`,
  the single public entry point) and eureka (`:8761`) publish host ports;
  Eureka IP registration (`prefer-ip-address`) resolves `lb://` container-to-
  container. `depends_on: service_healthy` gates every app on its datastores +
  eureka; Kafka uses a lightweight bash `/dev/tcp` probe (the JVM-spawning
  `kafka-broker-api-versions.sh` is too slow under load). `postgres-init`
  creates a separate `cortex_remediation` database so the two services' Flyway
  histories never collide. All cross-container wiring lives in the compose
  `environment:` blocks -- no service pom or YAML was touched (the
  remediation Redis dep is repointed via `SPRING_DATA_REDIS_HOST` at the
  compose layer). No local secrets manager: dev creds are plain env; "Vault"
  means Azure Key Vault, prod-only (P11/P12). Proven by
  `scripts/live-e2e/smoke-p10.ps1` (PASS 8: 13/13 healthy + Eureka registry +
  getLogById REST/GraphQL parity through the gateway over `lb://`).

- P10.0: container images for every runnable service (ADR-0050) -- the
  hard gate for the P10 `infra/docker/` compose stack. Eight hand-rolled
  multi-stage JDK17 Dockerfiles under `infra/docker/` (the seven services
  + the standalone eureka-server); `log-agent-lib` is a library and gets
  no image. Stage 1 (`eclipse-temurin:17-jdk-jammy`) builds the fat jar
  from source, stage 2 (`eclipse-temurin:17-jre-jammy`) runs it as a
  non-root `cortex` user with a container-aware heap
  (`-XX:MaxRAMPercentage=75`), a `/actuator/health` `HEALTHCHECK`, and an
  exec-form entrypoint. The service poms do not declare
  `spring-boot-maven-plugin`, so each builder runs a two-phase, pom-free
  build -- `-pl <svc> -am … install` then an app-only
  `package spring-boot:repackage` in one lifecycle -- to produce the
  executable fat jar without editing any pom (eureka-server declares the
  plugin and builds with a single `package`). Tests and the quality gates
  (checkstyle/spotbugs/owasp/jacoco/sbom/enforcer) are skipped in the
  image build -- those stay `mvn verify` / CI's job (P14). Builds use
  `--network=host` (BuildKit's default bridge cannot reach Maven Central
  on this host) and a shared `.m2` BuildKit cache mount. New root
  `.dockerignore` reconciliation keeps `infra/eureka/eureka-server` in the
  build context. Proven by `scripts/live-e2e/smoke-p10-0.ps1`: all eight
  images build and each container boots (eureka + echo reach health UP;
  dependency-bound services boot their web context -- full wired health is
  P10.1). New `infra/docker/README.md` build/run matrix. Started ahead of
  the externally-blocked P9.3 because the infra phases are
  directory-isolated and carry no dependency on P9.3.

- P9.2b: `log-gateway` `getLogById` REST + GraphQL parity (ADR-0049
  Amendment 5) -- the third of ADR-0004's four read queries and the
  gateway half of P9.2 (P9.2a shipped the ingest REST backer). Both
  surfaces delegate to one `GetLogByIdService`, which forwards the lookup
  to log-ingest-service (`GET /api/v1/logs/{eventId}`) over
  `lb://log-ingest-service` via the blocking `LoadBalancerClient` + a
  plain, timeout-bounded `RestClient` (bean `ingestRestClient`, distinct
  from P9.1b's `indexerRestClient`; a `@LoadBalanced` builder is
  deliberately avoided so Spring AI's Ollama client is not affected).
  REST: `GET /api/v1/logs/{eventId}` (`GetLogByIdController`). GraphQL:
  `getLogById(id: ID!): LogEntry` (`GetLogByIdGraphQlController`). The
  tenant is read from the `X-Tenant-Id` header (ADR-0009) -- REST via
  `@RequestHeader`, GraphQL via the existing P9.1b
  `TenantHeaderGraphQlInterceptor` -> `@ContextValue` -- and forwarded
  downstream as the single source of truth. The new `LogEntry` type
  carries timestamps as ISO-8601 strings (pass-through, no re-parse) and
  exposes `labels` via the existing `JSON` scalar, so the GraphQL payload
  is byte-identical to REST. Feature gate
  `cortex.gateway.get-log-by-id.enabled`; a shared
  `@RateLimitFeature("get-log-by-id")` sub-bucket is auto-registered on
  both surfaces (inheriting the P9.0a interceptor + P9.0b 429 resolver).
  Downstream verdicts map to HTTP: 404 -> 404 NOT_FOUND, everything else
  (other 4xx, 5xx, transport) -> 502 GET_LOG_BY_ID_UPSTREAM_FAILED. No
  route work was needed: P9.1c's POST-only ingest-proxy predicate already
  lets the GET fall through, and the literal `/api/v1/logs/search` mapping
  outranks the `{eventId}` pattern. Parity is enforced by
  `GetLogByIdRestAndGraphQlParityIT` (payload identity + `@RateLimitFeature`
  annotation equality).

- P9.2a: `log-ingest-service` tenant-scoped `getLogById` REST backer
  (ADR-0022 Amendment 2) -- the first step of P9.2 `getLogById` and the
  read path over the `raw_logs` system-of-record (P4.1 shipped the write
  path). New `LogQueryController` exposes
  `GET /api/v1/logs/{eventId}`: it reads the required `X-Tenant-Id`
  header as the single tenant source of truth, looks the row up via the
  new `RawLogRepository.findByTenantIdAndEventId(...)` derived query (the
  `UNIQUE (tenant_id, event_id)` constraint guarantees at most one row),
  and maps the verdict to HTTP -- hit -> 200 with a `LogResponse`, miss
  -> 404 NOT_FOUND, missing/blank tenant -> 400 VALIDATION_FAILED, all as
  RFC 7807 via the existing `GlobalExceptionHandler`. The new
  `LogResponse` record projects `RawLog` onto the public read shape
  (`eventId, tenantId, ts, level, service, message, labels, receivedAt`)
  and deliberately drops the internal surrogate `id` and the
  `idempotencyKey`. The read lives in log-ingest-service (the
  system-of-record) next to the write, mirroring the P9.1a decision that
  put the search surface in log-indexer-service; the gateway (P9.2b) will
  forward to this endpoint over `lb://log-ingest-service`. New
  `LogQueryControllerTest` `@WebMvcTest` slice (200 hit / 404 miss / 400
  missing + blank tenant; asserts `id` + `idempotencyKey` do not leak)
  and three new `RawLogRepositoryIT` cases (hit, tenant-scoped isolation,
  absent) against real Postgres via Testcontainers.

- P9.1c: harden the log-gateway logs proxy predicate with a method
  discriminator (ADR-0049 Amendment 4) -- preemptive route cleanup that
  unblocks P9.2 `getLogById` and any future GET read under the
  `/api/v1/logs/**` prefix. P9.1b resolved the `searchLogs` routing
  collision by narrowing the ingest proxy to
  `path("/api/v1/logs/**").and(path("/api/v1/logs/search").negate())`,
  but that per-path `.negate()` cannot scale to the path-variable
  `GET /api/v1/logs/{eventId}` (a `{eventId}` negation would also exclude
  `batch`). The predicate is now
  `path("/api/v1/logs/**").and(method(POST))`: every ingest WRITE
  (`POST /batch` today, `POST /stream` planned per ADR-0004) proxies to
  log-ingest-service, and every gateway-owned READ (GET search now,
  getLogById next, future reads free) falls through to its annotated
  controller -- structurally, with no per-path exclusion to maintain.
  New `GatewayRoutesConfigTest.logsProxyMatchesPostWritesButNotGetReads`
  evaluates the predicate directly (POST writes match; GET reads do not)
  as the CI-protected LD148 regression guard;
  `SearchLogsRestAndGraphQlParityIT` stays GREEN.

- P9.1b: `log-gateway` `searchLogs` REST + GraphQL parity
  (ADR-0049 Amendment 3) -- the second of ADR-0004's four read queries
  and the gateway half of P9.1 (P9.1a shipped the indexer REST search
  surface). Both surfaces delegate to one `SearchLogsService`, which
  forwards the query to log-indexer-service over
  `lb://log-indexer-service` via the blocking `LoadBalancerClient` + a
  plain, timeout-bounded `RestClient` (a `@LoadBalanced` builder is
  deliberately avoided so Spring AI's Ollama client is not affected).
  REST: `GET /api/v1/logs/search?index=&q=&maxHits=`
  (`SearchLogsController`). GraphQL:
  `searchLogs(input: LogSearchInput!): LogSearchResult!`
  (`SearchLogsGraphQlController`). The tenant is read from the
  `X-Tenant-Id` header (ADR-0009) -- REST via `@RequestHeader`, GraphQL
  via a new `TenantHeaderGraphQlInterceptor` that lifts the header into
  the execution context for a `@ContextValue` read -- and forwarded
  downstream as the single source of truth, so no input field can spoof
  another tenant. Hit documents ride the `JSON` scalar and `numHits`
  the `Long` scalar (`graphql-java-extended-scalars`, wired by
  `GraphQlScalarConfig`) so the GraphQL payload is byte-identical to
  REST. Feature gate `cortex.gateway.search-logs.enabled`; a shared
  `@RateLimitFeature("search-logs")` sub-bucket is auto-registered on
  both surfaces (one bucket per JWT subject, inheriting the P9.0a
  interceptor + P9.0b RFC 7807 429 resolver). Downstream verdicts map
  to HTTP: cross-tenant -> 403, missing index -> 404, permanent -> 422
  (`SEARCH_LOGS_INVALID`), downstream 429/503/5xx + transport -> 502
  (`SEARCH_LOGS_UPSTREAM_FAILED`). The P3.4 `searchServiceRoute` echo
  placeholder (`/api/v1/search/**` -> log-echo-service) is retired now
  that the real surface is gateway-owned. Parity is enforced by
  `SearchLogsRestAndGraphQlParityIT` (payload identity +
  `@RateLimitFeature` annotation equality).

- P9.1a: `log-indexer-service` tenant-scoped REST search surface
  (ADR-0042 Amendment 1) -- the first step of P9.1 `searchLogs`. New
  `SearchController` (`POST /api/v1/search`, JSON in/out) is a thin
  HTTP boundary over the existing P7.4 `LogSearchClient` SPI: it owns
  no search logic, reads the required `X-Tenant-Id` header as the
  single source of truth for the tenant (the body carries only
  `indexId`, `query`, optional `maxHits`, so a body field can never
  spoof another tenant), builds the domain `SearchRequest`, calls the
  SPI, and maps the returned `SearchResult` verdict onto an HTTP
  status. Because the SPI never throws (ADR-0042 D6) the mapping is
  verdict-driven: `search_ok`/`noop` -> 200 `{numHits, hits}`;
  `permanent_failure` `quickwit:tenant-mismatch` -> 403; reason ending
  `:404` -> 404; other permanent -> 422; `transient_failure`
  `quickwit:429` -> 429 + `Retry-After`; other transient -> 503.
  Validation + missing-header -> 400 via a new `SearchControllerAdvice`
  `@RestControllerAdvice`, all as RFC 7807 `ProblemDetail`. New DTOs
  `SearchHttpRequest` / `SearchHttpResponse` keep the verdict internals
  (`backend`/`outcome`/`reason`) off the happy-path wire. `maxHits`
  defaults to 50 and clamps to a 1000 ceiling. ArchUnit `LAYERING`
  extended with a `Controller` layer. Leg A `mvn verify` GREEN
  (Surefire 120 incl. new `SearchControllerTest` 14-test `@WebMvcTest`
  slice + Failsafe 45 incl. new `SearchControllerWireMockIT` 4-test
  `@SpringBootTest(RANDOM_PORT)` IT against a WireMock Quickwit +
  Checkstyle 0 + SpotBugs 0 + JaCoCo BUNDLE 0.80/0.80). Per LD104, live
  smoke + Postman + Newman deferred to the P9.1b gateway closer (an
  internal endpoint with no operator-facing client of its own yet --
  the P7.0..P7.4 precedent). P9.1b will add the gateway shared
  `SearchLogsService` + REST + GraphQL `searchLogs` parity calling this
  endpoint via `lb://log-indexer-service`.

### Fixed

- P9.0b: GraphQL NL rate-limit now returns RFC 7807 `429` instead of
  HTTP `500` (PR for #131, ADR-0049 Amendment 2 2026-06-08, closes
  #131). A live boot smoke (`scripts/live-e2e/smoke-p9-0a.ps1`)
  proved the P9.0a runtime claim false: when a GraphQL `nlToLogQL`
  call exhausted the shared NL sub-bucket the gateway returned a
  generic `500` (`{"status":500,"error":"Internal Server Error",
  "path":"/graphql"}`), not the documented `429`. Root cause:
  `GlobalExceptionHandler` is a `@RestControllerAdvice`, and Spring's
  `ExceptionHandlerExceptionResolver` only applies `@ExceptionHandler`
  to `HandlerMethod` handlers (`@RequestMapping` controllers); the
  `/graphql` HTTP transport is a functional `RouterFunction`
  (`GraphQlWebMvcAutoConfiguration`), so the `RateLimitedException`
  thrown by `RateLimitGraphQlInterceptor` escaped the advice and the
  servlet container rendered a `500`. Fix: new
  `RateLimitProblemExceptionResolver` (`HandlerExceptionResolver` at
  `HIGHEST_PRECEDENCE`, gated by
  `cortex.gateway.rate-limit.enabled=true`) maps
  `RateLimitedException` from non-`HandlerMethod` handlers to a `429`
  RFC 7807 body + `Retry-After`; it returns `null` for `HandlerMethod`
  handlers so the REST surface stays on `@ExceptionHandler` untouched.
  The RFC 7807 builder is extracted from `GlobalExceptionHandler` into
  a shared `ProblemDetailFactory` so REST and GraphQL emit
  byte-identical problem bodies (only `instance` differs:
  `/api/v1/query/nl` vs `/graphql`) -- the parity contract is now
  structural. The interceptor's earlier P9.0a CHANGELOG claim of a
  `GlobalExceptionHandler`-mediated 429 on the GraphQL path is
  superseded by this entry. Verified live (Leg B): the GraphQL
  over-limit call returns `429 NL_QUERY_RATE_LIMITED` +
  `Retry-After`, and both shared-bucket directions (REST->GraphQL and
  GraphQL->REST) drain one `cortex:rl:nlq:nl-query:user:<sub>` bucket.
  New `RateLimitProblemExceptionResolverTest` (5 scenarios) +
  `GlobalExceptionHandlerTest` unchanged (17/17 through the factory
  extraction).

### Added

- P9.0a: log-gateway `WebGraphQlInterceptor` NL sub-bucket parity
  for the GraphQL surface (PR for #129, ADR-0049 Amendment 1
  2026-06-08, closes #129). Closes the D6 deferral in ADR-0049.
  New `RateLimitGraphQlInterceptor` under
  `io.cortex.gateway.interceptor` -- Spring for GraphQL
  `WebGraphQlInterceptor` that scans every
  `@Controller` bean at `@PostConstruct` for methods carrying both
  `@QueryMapping` and `@RateLimitFeature`, indexes the annotation by
  GraphQL field name, parses each inbound `WebGraphQlRequest`
  document via `graphql.parser.Parser`, walks the top-level
  `Field` selections, and consumes one Bucket4j token per matched
  field via the SAME `ProxyManager<String>` bean wired into the MVC
  `RateLimitFeatureInterceptor`. On exhaustion throws
  `RateLimitedException` which propagates through `Mono.error(...)`
  -> `GraphQlHttpHandler.block()` -> the existing
  `GlobalExceptionHandler.@ExceptionHandler(RateLimitedException.class)`
  -> 429 RFC 7807 body with `errorCode=NL_QUERY_RATE_LIMITED`. The
  GraphQL NL resolver `NlQueryGraphQlController.nlToLogQL` now
  carries the same `@RateLimitFeature(name="nl-query",
  capacity="${...:10}", refill="${...:PT1M}",
  errorCode="NL_QUERY_RATE_LIMITED",
  keyPrefix="${...:cortex:rl:nlq:}")` annotation as
  `NlQueryController.translate` -- identical bucket key shape
  (`cortex:rl:nlq:nl-query:user:<sub>`) + identical injected
  `ProxyManager` bean equals a single shared sub-bucket per JWT
  subject across REST and GraphQL surfaces. Abusive callers can no
  longer bypass the 10/min NL cap by issuing the same prompt over
  the other surface. Gated by
  `cortex.gateway.rate-limit.enabled=true` (the same switch as the
  MVC interceptor; OFF by default per LD100). New Surefire slice
  `RateLimitGraphQlInterceptorTest` covers 7 scenarios
  (unregistered-field skip, happy-path token consume, exhaustion
  with annotation-driven `errorCode` propagation, anonymous caller
  keyed by `X-Forwarded-For` first hop, anonymous-fallback to
  `unknown`, mixed registered/unregistered selections,
  empty-registry short-circuit) using `reactor.test.StepVerifier`.
  Existing closer `NlQueryRestAndGraphQlParityIT` extended with
  `graphQlAndRestResolversDeclareIdenticalRateLimitFeatureAnnotation()`
  asserting via `AnnotatedElementUtils.findMergedAnnotation` that
  every member of `@RateLimitFeature` is byte-equal across the REST
  and GraphQL resolver methods -- the parity contract at the IT
  level without standing up a live Lettuce/Redis fixture (deferred
  per existing repo posture). Per LD104, Leg C (Newman) deferred --
  no new operator-visible endpoint; URL and response shape are
  those certified by P9.0.

- P9.0: log-gateway GraphQL scaffold + `nlToLogQL` query with REST
  parity (PR for #127, ADR-0049 2026-06-08, closes #127). First
  GraphQL surface in the project per ADR-0004 (REST + GraphQL parity
  on four read queries, no mutations forever). Spring for GraphQL
  pulled in via the bundled `spring-boot-starter-graphql` (Boot 3.3.6
  BOM-managed; no explicit version). Schema-first under
  `log-gateway/src/main/resources/graphql/schema.graphqls`:
  `type Query { nlToLogQL(prompt: String!): NlQueryResult! }` with
  `NlQueryResult { logql: String! confidence: Float! explanation: String! }`
  mirroring the existing REST DTO field-by-field so a single client
  model serialises identically over both surfaces. New resolver
  `NlQueryGraphQlController` in new `io.cortex.gateway.graphql`
  package -- `@QueryMapping @PreAuthorize("isAuthenticated()")`,
  zero business logic, delegates to the existing shared
  `NlQueryService.translate(NlQueryRequest, principalName)` so REST
  + GraphQL are guaranteed identical at runtime. Per-query
  `@ConditionalOnProperty(cortex.gateway.nl-query.enabled=true)`
  matches the REST controller gate (LD100 OFF-by-default) so the
  GraphQL surface ships dark and is rolled out per-query. Security
  via the existing `anyRequest().authenticated()` filter chain in
  `SecurityConfig` -- no per-path `permitAll` rule for `/graphql`;
  the `@PreAuthorize` is defence-in-depth. Rate-limiting via the
  existing global Bucket4j `RateLimitFilter` -- `/graphql` is
  deliberately NOT in `excludedPaths()` so every request consumes
  one token per JWT subject like every REST request. Production-safe
  defaults `spring.graphql.graphiql.enabled` +
  `spring.graphql.schema.introspection.enabled` both default `false`
  via `CORTEX_GATEWAY_GRAPHQL_GRAPHIQL_ENABLED` +
  `CORTEX_GATEWAY_GRAPHQL_INTROSPECTION_ENABLED` env vars (local dev
  MAY flip on; staging/prod leave off). `spring.graphql.path`
  defaults to `/graphql` via `CORTEX_GATEWAY_GRAPHQL_PATH`. New
  `Graphql` layer in `ArchitectureRulesTest.LAYERING` mirrors the
  `Controller` posture (`mayNotBeAccessedByAnyLayer`) with `Service`
  + `Exception` allowed-callers extended to permit `Graphql` calls.
  New Surefire slice `NlQueryGraphQlControllerTest` uses
  `@GraphQlTest(NlQueryGraphQlController.class)` -- fast, no full
  context; 2 tests (happy path + `NL_QUERY_INVALID` propagation).
  First member of new `io.cortex.gateway.closer` package --
  `NlQueryRestAndGraphQlParityIT` Failsafe IT (`@SpringBootTest
  @AutoConfigureMockMvc`) acquires a real JWT via `POST
  /api/v1/auth/login` with the bootstrap admin user, then asserts
  REST + GraphQL produce identical `(logql, confidence, explanation)`
  for the same prompt -- this is the parity contract. Failsafe
  plugin activated in `log-gateway/pom.xml` (was Surefire-only
  before this sub-phase). Per-phase verification triangle deferred
  to Leg A only per LD104 (closer IT is the proxy for Spring-wiring
  changes inside this scaffold sub-phase; smoke + Newman re-introduced
  in P9.x when each query gains an operator-visible endpoint).
  Per-query NL sub-bucket via `@RateLimitFeature` does NOT fire on
  GraphQL resolvers in P9.0 because `RateLimitFeatureInterceptor` is
  a Spring MVC `HandlerInterceptor` and never sees `DataFetcher`
  invocations -- explicitly deferred to a follow-up P9.0a
  `WebGraphQlInterceptor` extension (not a correctness gap; the
  global bucket still caps abusive callers, only a convenience
  parity gap on the NL-specific 10/min sub-quota).

- P8.2b: log-monitoring-service multi-target probe pump +
  default availability SLO definitions (PR for #125,
  ADR-0046 Amendment 3 2026-06-08, closes #125). New
  `ScheduledProbeEvaluator` under
  `io.cortex.monitoring.probe` fans out
  `serviceHealthProbe.probe(new ProbeRequest(serviceId, null))`
  once per service-id in `cortex.monitoring.probe.targets` on
  every `@Scheduled` tick; gated by
  `cortex.monitoring.probe.enabled=true` (OFF by default so
  existing per-phase tests + the P8.1a / P8.2a closers see
  the same context they did before P8.2b). Cadence read
  through the SpEL bean reference
  `#{@probeEvaluationIntervalMillis}` resolved by a new
  `@Bean Long probeEvaluationIntervalMillis(ProbeProperties)`
  on the new `ProbeSchedulerConfig` per LD141 (mirrors the
  Amendment 2 fix for `SloEvaluator`). New `ProbeProperties`
  record binds `cortex.monitoring.probe.{enabled, evaluation-interval,
  targets}` alongside the existing `backend` field; compact-ctor
  defensive defaults so a missing key never NPEs. Shipped
  `application.yml` now declares all six cortex services
  (`log-gateway`, `log-ingest-service`, `log-echo-service`,
  `log-processor-service`, `log-remediation-service`,
  `log-indexer-service`) as the default
  `cortex.monitoring.probe.targets` AND matching
  `cortex.monitoring.slo.definitions` rows (`availability`
  SLO, `target-success-ratio=0.99`, `window=PT1H`) so an
  operator who flips both `enabled` gates gets cortex-wide
  availability monitoring for free, with zero per-service yml
  authoring. New sibling closer
  `MonitoringMultiTargetProbeAndDefaultSlosIT` under
  `io.cortex.monitoring.closer` proves the multi-target
  fan-out end-to-end against a stub `DiscoveryClient` that
  routes every target id to a shared in-process WireMock --
  asserts every target produces exactly one
  `cortex.monitoring.probe_total{outcome=healthy,service_id=...}`
  series after a single `evaluateOnce()` call AND
  `sloEvaluator.evaluateOnce()` under the noop binder
  iterates all six default definitions without throwing.
  ADR count stays at 48 (amendment to existing decision).

### Fixed

- log-monitoring-service issue #120 / LD137:
  `SloEvaluator.@Scheduled(fixedRateString=...)` no longer
  `NumberFormatException`s at bean creation when
  `cortex.monitoring.slo.enabled=true` with the operator-friendly
  `30s` / `1h` / `PT30S` Duration syntax for
  `cortex.monitoring.slo.evaluation-interval`. The cadence is
  now read through the SpEL bean reference
  `#{@sloEvaluationIntervalMillis}` resolved by a new
  `@Bean Long sloEvaluationIntervalMillis(SloProperties)` on
  `SloEngineConfig` (returns
  `properties.evaluationInterval().toMillis()`). Bean name
  pinned via the public constant
  `SloEngineConfig.SLO_EVALUATION_INTERVAL_MILLIS_BEAN` so a
  drift between the SpEL string and the registered bean
  surfaces as an immediate boot-time failure. The P8.2a closer
  IT `MonitoringProbeAndSloPipelineIT` was reverted from the
  IT-only numeric-millis workaround
  (`evaluation-interval=3600000`) back to the
  operator-friendly `1h` form. New narrowest-possible
  `SloEvaluatorScheduledBootIT` (under
  `io.cortex.monitoring.closer`) boots the full Spring context
  with `slo.enabled=true` + `evaluation-interval=30s` and pins
  the fix as a CI-protected regression. ADR-0046 Amendment
  2026-06-08 captures the decision. (Closes #120.)

### Added

- P8.1a: log-monitoring-service probe-only cross-phase closer
  (PR for #122, ADR-0048, closes P8 epic #9). New cross-phase
  Failsafe IT `MonitoringProbeAndHealthIndicatorIT` under
  `io.cortex.monitoring.closer` alongside the existing
  P8.2a `MonitoringProbeAndSloPipelineIT`, covering the
  production-shaped operator deployment where ONLY the probe
  binder gate is flipped (`cortex.monitoring.probe.backend=
  eureka-actuator`; SLO defaulted off). Intentionally
  `@SpringBootTest(webEnvironment=RANDOM_PORT)` (NOT
  `MOCK`) so the IT can fire `TestRestTemplate.exchange(
  /actuator/health/monitoring, GET, ParameterizedType-
  Reference<Map<String,Object>>)` against embedded Tomcat
  and assert the indicator surface returns `200 UP` with
  `details.backend=eureka-actuator` end-to-end -- the
  P8.2a `MOCK` env structurally cannot make this
  assertion. Does NOT set
  `cortex.monitoring.slo.enabled=true` and therefore does
  NOT carry the LD137 numeric-millis workaround
  `cortex.monitoring.slo.evaluation-interval=3600000` from
  ADR-0047: the SLO scheduler bean is gated off so the
  broken `SloEvaluator.@Scheduled(fixedRateString=...)`
  annotation is never reached. This IT is the canonical
  proof that the probe surface boots green in a
  production-shaped configuration that does NOT depend on
  the deferred issue #120 prod fix. Six test methods:
  probe bean is `eureka-actuator` + engine bean is `noop`
  + probe healthy path increments the counter family +
  indicator HTTP surface reports `backend=eureka-actuator`
  with `status=UP` + bootstrap loop registers the
  `eureka-actuator` outcome series + `MonitoringMetrics`
  ctor-autowire reachable. Re-applies the ADR-0047 D2a
  `spring.autoconfigure.exclude` triplet of
  `EurekaClientAutoConfiguration` +
  `CompositeDiscoveryClientAutoConfiguration` +
  `SimpleDiscoveryClientAutoConfiguration` to win the
  `@Primary DiscoveryClient` contest in the cross-phase
  IT context. Re-uses the existing P8.2a
  `postman/log-monitoring.postman_collection.json` (the
  Prometheus folders skip cleanly via
  `pm.execution.skipRequest()` when `prometheus_base_url`
  is absent). New standalone PowerShell smoke
  `scripts/smoke-p8-1a.ps1` (LOCAL-ONLY gitignored under
  `/scripts/`) boots ONLY the service JAR on `:8098` with
  `CORTEX_MONITORING_PROBE_BACKEND=eureka-actuator` +
  `EUREKA_CLIENT_ENABLED=false` (no SLO env vars; no
  Prometheus container -- the P8.2a smoke covers that
  surface) and asserts `/actuator/health/monitoring` UP
  with `backend=eureka-actuator` + `/actuator/prometheus`
  exposes the `cortex_monitoring_probe_total` family with
  `# HELP` + `# TYPE` + Part 17 tag keys. PR body carries
  BOTH `Closes #122` AND `Closes #9` per LD138 -- P8.1a
  IS the last P8-blocking sub-phase per the ADR-0046
  amendment 2026-06-08, so the P8 epic closes on this PR.
  Captured as LD139 in memory.md: in this repo any test
  code that consumes a generic Spring `RestTemplate` /
  `TestRestTemplate` response MUST use
  `ParameterizedTypeReference<T>` not raw `Map.class` /
  `List.class`, because parent javac runs with `-Werror`
  and rejects the resulting `found raw type` warning at
  compile time.

### Added

- P8.2a: log-monitoring-service cross-phase closer +
  Prometheus singleton + cross-phase Failsafe IT + smoke +
  Postman + DiscoveryClient autoconfig-exclude +
  `SloEvaluator.fixedRateString` workaround (PR for #119,
  ADR-0047, closes P8 epic #9). New cross-phase IT
  `MonitoringProbeAndSloPipelineIT` boots the full Spring
  context with both binder gates flipped
  (`cortex.monitoring.probe.backend=eureka-actuator` +
  `cortex.monitoring.slo.backend=micrometer-derivation` +
  `cortex.monitoring.slo.enabled=true`) against a singleton
  in-process `WireMockServer` + a `@TestConfiguration @Bean
  @Primary DiscoveryClient` stub routing TWO service IDs to
  the same WireMock for test-order independence. 9 test
  methods exercise the full P8.0..P8.2 ring through
  autowired beans: 1 binder-gate proof + 1 metrics-bootstrap
  proof (counter family present with all 6 outcome series)
  + 3 probe outcomes (HEALTHY / DEGRADED / UNREACHABLE-
  no-instance) + 1 wire-shape verify + 2 SLO derivations
  (banded micrometer-derivation + no-data unknown) + 1
  evaluator-as-bean proof. New full-stack PowerShell smoke
  `scripts/smoke-p8-2a.ps1` boots Prometheus 2.55.1 on
  `:9090` (via `infra/local/docker-compose.smoke.yml`
  `prometheus:` service + new `infra/local/prometheus.yml`)
  scraping the actual service JAR on `:8098`, asserts
  `/actuator/health/monitoring details.backend=eureka-
  actuator`, scrapes the three Micrometer families
  (`cortex_monitoring_probe_total`,
  `cortex_monitoring_slo_budget_remaining`,
  `cortex_monitoring_slo_burn_rate`) with `# HELP` +
  `# TYPE` + Part 17 tag keys, asserts Prometheus
  `/api/v1/rules` loaded the three CORTEX alert rules
  (`CortexSloFastBurn`, `CortexSloSlowBurn`,
  `CortexSloBudgetExhausted`) + `/api/v1/targets` UP, tears
  down with `-KeepInfra` to enable Newman replay. New
  `postman/log-monitoring.postman_collection.json` (5
  folders: Health + Metrics-Baseline + Eureka-Probe-Contract
  + Prometheus + Metrics-After) + three env files
  (`local`, `staging`, `prod`); `local` sets
  `prometheus_base_url=http://host.docker.internal:9090`,
  `staging` + `prod` leave it blank for offline replay.

### Fixed

- IT-only fix: cross-phase IT context startup was blocked by
  `NoUniqueBeanDefinitionException: more than one 'primary'
  bean found among candidates: [stubDiscoveryClient,
  compositeDiscoveryClient, simpleDiscoveryClient]`. Spring
  Cloud Commons' `CompositeDiscoveryClientAutoConfiguration`
  + `SimpleDiscoveryClientAutoConfiguration` register their
  own `@Primary` `DiscoveryClient` beans by default, so a
  `@TestConfiguration @Bean @Primary DiscoveryClient` stub
  collides with two same-tier `@Primary` candidates.
  Repaired in `MonitoringProbeAndSloPipelineIT`'s
  `properties=` block via `spring.autoconfigure.exclude=`
  listing the three `DiscoveryClient` autoconfig classes
  (`EurekaClientAutoConfiguration` +
  `CompositeDiscoveryClientAutoConfiguration` +
  `SimpleDiscoveryClientAutoConfiguration`). Surgical: only
  the cross-phase IT context is affected, prod and per-phase
  tests are unchanged. Captured as ADR-0047 D2a.

- Test-order-independence fix: the SLO budget-derivation
  test was sensitive to counter-family pollution from the
  probe tests (`HEALTHY` budget assertion would fail if a
  prior test had piled failure outcomes onto the same
  service-tagged counter). Repaired by introducing a
  dedicated `DERIVE_SERVICE_ID = "log-echo-slo-derive"` for
  the SLO test only; the stub `DiscoveryClient` routes BOTH
  service IDs to the same WireMock. Mirrors realistic
  operator deployments where multiple Eureka service IDs
  point at the same actuator surface (ADR-0047 D7).

### Deferred (LD137 follow-up)

- Prod fix for `SloEvaluator.@Scheduled(fixedRateString=
  "${cortex.monitoring.slo.evaluation-interval:30s}")` is
  deferred to a follow-up bug-fix issue per LD104
  closer-pattern (the closer ships green WITHOUT inventing
  a prod-scheduling-semantics change inside the closer PR).
  Spring's `ScheduledAnnotationBeanPostProcessor.
  fixedRateString` calls `Long.parseLong(value)` directly
  with NO `Duration.parse` fallback, so the prod annotation
  is broken whenever `slo.enabled=true` -- masked in prod
  because `slo.enabled` defaults to false so the evaluator
  bean is never instantiated. The cross-phase IT works
  around this by passing the numeric-millis form
  (`cortex.monitoring.slo.evaluation-interval=3600000`).
  Recommended prod fix in the follow-up issue: introduce
  `@Bean Long sloEvaluationIntervalMillis(SloProperties)`
  + switch the annotation to SpEL `@Scheduled(
  fixedRateString="#{@sloEvaluationIntervalMillis}")`,
  preserving operator-facing `Duration` ergonomics in
  `application.yml`. Captured as LD137 in memory.md +
  ADR-0047 D2b + GitHub issue #120.

### Added

- P8.2: log-monitoring-service SLO budget engine +
  `MicrometerSloBudgetEngine` derivation backend +
  multi-window burn-rate alert rules (PR for #115,
  ADR-0046). New `SloBudgetEngine` SPI in new
  `io.cortex.monitoring.slo` package (single method
  `SloSnapshot evaluate(SloDefinition)` plus
  `String backendId()`, MUST NOT throw -- mirrors
  `ServiceHealthProbe` + `QuickwitIndexAdmin` +
  `RemediationDispatcher` SPI discipline). New immutable
  `SloDefinition(serviceId, sloName, targetSuccessRatio,
  window)` operator-input record (compact-ctor rejects
  null/blank/out-of-range/zero-duration so misconfigured
  yml fast-fails at context refresh) + new immutable
  `SloSnapshot(backend, serviceId, sloName, outcome,
  budgetRemainingRatio, burnRate, reason)` verdict envelope
  with `BACKEND_NOOP` / `BACKEND_MICROMETER_DERIVATION`
  constants + 7 `OUTCOME_*` constants (`NOOP / HEALTHY /
  AT_RISK / EXHAUSTED / UNKNOWN / TRANSIENT_FAILURE /
  PERMANENT_FAILURE`) + factories `noop / unknown /
  banded / transientFailure / permanentFailure` + static
  `classifyBand(double)` per SRE workbook bands
  (>0.5 healthy, >0.1 at_risk, <=0.1 exhausted).
  `NoopSloBudgetEngine @ConditionalOnProperty(backend=noop,
  matchIfMissing=true)` default keeps the cold-start
  operator all-clear; new `MicrometerSloBudgetEngine
  @ConditionalOnProperty(backend=micrometer-derivation)`
  real adapter introspects the existing
  `cortex.monitoring.probe_total{backend, outcome,
  service_id}` counter family (P8.1 / ADR-0045), sums
  successes vs failures by outcome tag
  (`HEALTHY+DEGRADED` -> success;
  `UNHEALTHY+UNREACHABLE+TRANSIENT_FAILURE+PERMANENT_FAILURE`
  -> failure; `NOOP+UNKNOWN` -> ignored), derives
  `errorBudget = 1 - target`,
  `errorRate = failures / total`,
  `burnRate = errorRate / errorBudget`, and clamps
  `budgetRemaining = clamp((errorBudget - errorRate) /
  errorBudget, -1.0, 1.0)`. No-data path returns
  `unknown / micrometer-derivation:no-data` with gauges
  defaulted to all-clear (`1.0 / 0.0`); thrown
  `RuntimeException` is caught and mapped to
  `transientFailure / micrometer-derivation:exception:<class>`
  so a registry bug cannot stall the loop. New
  `SloEvaluator @ConditionalOnProperty(enabled=true,
  matchIfMissing=false)` with `@Scheduled(fixedRateString =
  "${cortex.monitoring.slo.evaluation-interval:30s}")`
  ticks every engine x every definition pair, calls
  `MonitoringMetrics.recordSlo(snapshot)` on each
  non-null verdict, WARN-logs throws + null-snapshots
  and continues so a single bad pair cannot stall the
  loop (explicit ctor per Checkstyle Rule 14.1 + LD110).
  New `SloProperties @ConfigurationProperties("cortex.
  monitoring.slo")` immutable record with defensive
  defaults (blank backend -> noop / null/zero/negative
  interval -> 30s / null definitions -> emptyList else
  defensive `List.copyOf`). New `SloEngineConfig
  @EnableConfigurationProperties(SloProperties.class)`
  (properties bean unconditional so engine beans can wire
  without flipping the master switch -- mirrors P8.1
  `EurekaActuatorHttpConfig` pattern).
  `MonitoringMetrics.recordSlo(SloSnapshot)` registers
  two new gauges
  `cortex.monitoring.slo_budget_remaining{service_id,
  slo_name}` and `cortex.monitoring.slo_burn_rate{service_id,
  slo_name}` on first contact per `(serviceId, sloName)`
  key via `AtomicReference<SloSnapshot>` holder; subsequent
  contacts swap the holder value in place so exactly one
  time-series per key for the JVM lifetime. New
  `infra/local/alerts/slo-burn-rate.rules.yml` with 3
  SRE-workbook multi-window alerts:
  `CortexSloFastBurn` (5m+1h burn-rate > 14.4x for 2m,
  severity `page`), `CortexSloSlowBurn` (1h+6h burn-rate
  > 6x for 15m, severity `ticket`), and
  `CortexSloBudgetExhausted` (budget remaining < 0 for 5m,
  severity `ticket`); operator runbook comments inline at
  the top of the file. Two new env-var escape hatches
  `CORTEX_MONITORING_SLO_ENABLED` +
  `CORTEX_MONITORING_SLO_BACKEND` +
  `CORTEX_MONITORING_SLO_EVALUATION_INTERVAL`.
  `@EnableScheduling` added to
  `CortexMonitoringApplication`. ArchUnit `Slo` layer
  added (`io.cortex.monitoring.slo..`) with allowed access
  `App` + `Metrics`. Verification triangle Leg A green:
  Surefire 104 (50 prior + 54 new -- 11 `SloDefinitionTest`
  + 13 `SloSnapshotTest` + 8 `SloPropertiesTest` + 3
  `NoopSloBudgetEngineTest` + 10
  `MicrometerSloBudgetEngineTest` + 5 `SloEvaluatorTest` +
  4 new `recordSlo` tests on the existing
  `MonitoringMetricsTest`) + Failsafe 9 unchanged
  (`EurekaActuatorHealthProbeWireMockIT` 9/0/0/0,
  39.47 s) + Checkstyle 0 + SpotBugs 0 + JaCoCo BUNDLE
  0.80/0.80 met. Per LD104, P8.2 ships Leg A only; Legs
  B (boot smoke) / C (Postman + Newman) / D (cross-phase
  `@SpringBootTest`) / E (per-module README runbook
  smoke) stay deferred to the P8.2a closer where a real
  Prometheus + Compose stack scrapes the gauges and fires
  the alert rules. Closes #115.

- P8.1: log-monitoring-service `EurekaActuatorHealthProbe`
  HTTP probe via Eureka discovery + dual-timeout `RestClient`
  (PR for #113, ADR-0045). New `EurekaActuatorHealthProbe
  @Component @ConditionalOnProperty(prefix="cortex.monitoring.probe",
  name="backend", havingValue="eureka-actuator")` adapter in
  new package `io.cortex.monitoring.probe.eureka` that
  resolves `ProbeRequest.serviceId()` via Spring Cloud
  `DiscoveryClient`, picks the instance matching
  `ProbeRequest.instanceId()` (or the first registered
  entry), issues a single HTTP GET to
  `<instance.uri>/actuator/health` via the
  `eurekaActuatorRestClient` bean (HTTP/1.1-pinned via
  `JdkClientHttpRequestFactory` per LD42 + dual connect+read
  timeout per LD121, default 5 s) drawn from typed
  `EurekaActuatorProperties(requestTimeout, actuatorPath)`
  record bound to prefix `cortex.monitoring.eureka` with
  defaults `5s` / `/actuator/health` and compact-ctor
  defensive defaults on null/zero/negative timeouts and
  null/blank paths. Happy-path JSON `status` field parsed at
  the adapter: `UP` -> `HealthSnapshot.healthy("UP")`,
  `OUT_OF_SERVICE` -> `degraded("OUT_OF_SERVICE")`, `DOWN`
  -> `unhealthy("DOWN")`, any other / missing / non-textual /
  empty body / parse error -> `degraded("unknown:<reason>")`.
  HTTP outcome -> `HealthSnapshot` classification delegated
  to new channel-agnostic `RestProbeTemplate` helper
  symmetric with P7.1 `RestAdminTemplate` (`classifyHttp` ->
  429 transient / 5xx transient / other 4xx permanent;
  `classifyTransport` -> `HttpTimeoutException` /
  `TimeoutException` cause transient `:timeout`, other
  transport transient `:transport`; `classifyUnknown` ->
  transient `:unknown`). Eureka returning null/empty
  instances OR no `instanceId` match maps to
  `unreachable / eureka-actuator:no-instance` (NOT
  permanent/transient -- the bounded enum value reserved for
  "we never got off the box" per ADR-0044 D3); null
  `ProbeRequest` maps to
  `permanent_failure / eureka-actuator:null-request`. Every
  exit ticks `MonitoringMetrics.incProbe(backend, outcome,
  serviceId)` via private `tick(...)` helper so the
  bootstrap-registered counter family produces a continuous
  signal at the `/actuator/prometheus` scrape surface. SPI
  contract honoured -- `probe()` MUST NOT throw, four catch
  arms in `scrape()` funnel every failure into the verdict
  envelope per ADR-0044 D7. `@Lazy MonitoringMetrics` ctor
  param breaks `MonitoringMetrics -> EurekaActuatorHealthProbe
  -> MonitoringMetrics` bean cycle per LD131. Activation
  gates noop + eureka-actuator beans mutually exclusive per
  ADR-0044 D5 binder pattern. New `MonitoringHttp` final
  constants holder (`TOO_MANY_REQUESTS=429`,
  `SERVER_ERROR_FLOOR=500`, `NOT_FOUND=404`) in new package
  `io.cortex.monitoring.constants` to defuse Checkstyle
  `MagicNumber` warnings. New `EurekaActuatorHttpConfig
  @Configuration @ConditionalOnProperty(backend=eureka-actuator)
  @EnableConfigurationProperties(EurekaActuatorProperties.class)`
  provides the `eurekaActuatorRestClient` bean. ArchUnit
  layered contract gains the `Constants` layer + the
  `Probe -> Metrics` back-edge. Part 17 tag-key allowlist
  unchanged (3 keys, 7 outcomes -- bounded cardinality).
  Tests: 5 new production files (incl. 2 package-info) + 4
  new test files (`EurekaActuatorPropertiesTest` 6/0/0/0,
  `RestProbeTemplateTest` 10/0/0/0 incl. `TimeoutException`
  cause discrimination via `initCause()` pattern mirroring
  P7.1 `RestAdminTemplateTest:113-122`,
  `EurekaActuatorHealthProbeTest` 6/0/0/0 with hand-rolled
  `StubDiscoveryClient` per Part 20 Mockito ban,
  `EurekaActuatorHealthProbeWireMockIT` 9/0/0/0 mirroring
  `QuickwitHttpAdminWireMockIT` shape -- dynamic port, full
  D3 outcome table including LD120
  `Fault.CONNECTION_RESET_BY_PEER` deterministic transport
  fault, IT-local 30 s read-timeout bump per LD123).
  Module-local `mvn verify` GREEN on first re-run after one
  IT-assertion fix (50 surefire + 9 failsafe / 0F0E0S /
  Checkstyle 0 violations / SpotBugs 0 violations / JaCoCo
  0.80/0.80 met). Per LD104, P8.1 ships Leg A
  (`mvn verify`) + per-adapter Leg D slice
  (`EurekaActuatorHealthProbeWireMockIT`); Legs B/C/D-cross-
  phase/E stay deferred to the P8.1a closer. Closes #113.

- P8.0: log-monitoring-service scaffold + `ServiceHealthProbe`
  SPI + per-backend probe contract (PR for #111, ADR-0044).
  New Spring Boot module `log-monitoring-service` on port
  `:8098` (LD92 -- next free after `:8097` indexer) that owns
  the cross-service health-aggregation surface + the future
  SLO budget engine for the CORTEX platform. Carves the
  ownership boundary against per-service `/actuator/health`
  endpoints: each service keeps owning its own actuator;
  this module is a **consumer + aggregator**, NOT a
  replacement. Mirrors the P7.0 log-indexer-service scaffold
  shape (ADR-0038). New `ServiceHealthProbe` SPI in
  `io.cortex.monitoring.probe` with `String backendId()` +
  `HealthSnapshot probe(ProbeRequest)`; new immutable
  `ProbeRequest(serviceId, instanceId)` record with
  compact-ctor null/blank rejection on `serviceId`
  (`instanceId` nullable for fan-out probes); new immutable
  `HealthSnapshot(backend, outcome, reason, detail)` record
  with constants `BACKEND_NOOP/EUREKA_ACTUATOR` +
  `OUTCOME_{NOOP,HEALTHY,DEGRADED,UNHEALTHY,UNREACHABLE,TRANSIENT_FAILURE,PERMANENT_FAILURE}`
  and factories `noop/healthy/degraded/unhealthy/unreachable/transientFailure/permanentFailure`
  all coercing null backend -> `BACKEND_NOOP` + null reason
  / detail -> `""` (D3 -- bounded enum-like strings keep
  the Part 17 tag allowlist holdable by construction);
  `NoopServiceHealthProbe` `@ConditionalOnProperty(prefix="cortex.monitoring.probe",
  name="backend", havingValue="noop", matchIfMissing=true)`
  default that returns `HealthSnapshot.noop(...)` for every
  call so the scaffold boots green with no Eureka dependency
  (D6); single-counter-family `cortex.monitoring.probe_total{backend,
  outcome, service_id}` published by `MonitoringMetrics`
  (`@Component @RequiredArgsConstructor` injects
  `MeterRegistry` + `List<ServiceHealthProbe>`,
  `@PostConstruct bootstrapMeters()` walks the probe list
  and pre-registers the failable-outcome series per backend
  per LD106 + LD112 + LD125 so the `/actuator/prometheus`
  scrape sees the family from cold start; `incProbe(backend,
  outcome, serviceId)` coerces null/blank tag values to
  `"unknown"` so the counter cannot NPE on adapter bugs);
  `MonitoringHealthIndicator @Component("monitoring")` bound
  to `/actuator/health/monitoring` reporting `UP` for the
  noop backend at P8.0 + surfacing the active
  `probe.backendId()` as a detail (D4 -- operator can verify
  the binder gate at a glance without parsing
  `/actuator/prometheus`). New `pom.xml` with full parent
  inheritance (`cortex-parent` 0.1.0-SNAPSHOT) + explicit
  `<maven-failsafe-plugin>` declaration per LD129; deps
  match indexer (spring-boot-starter, validation, actuator,
  web, spring-cloud-starter-netflix-eureka-client,
  micrometer-registry-prometheus, logstash-logback-encoder,
  lombok, spotbugs-annotations, commons-lang3,
  spring-boot-starter-test, archunit-junit5) WITHOUT
  WireMock (P8.0 has no outbound HTTP per LD104 closer
  pattern). New `application.yml` (Eureka client +
  `cortex.monitoring.probe.backend=${CORTEX_MONITORING_PROBE_BACKEND:noop}`),
  `logback-spring.xml` (dev console pattern + JSON via
  logstash-logback-encoder with `customFields={"service":"log-monitoring-service"}`),
  `src/test/resources/application.yml` LD100 full shadow
  (`eureka.client.{enabled,register-with-eureka,fetch-registry}=false`
  + `server.port=0` + `cortex.monitoring.probe.backend=noop`).
  Tests: 27 across 7 classes (`ArchitectureTest`,
  `CortexMonitoringApplicationTests`,
  `NoopServiceHealthProbeTest`, `HealthSnapshotTest`,
  `ProbeRequestTest`, `MonitoringMetricsTest`,
  `MonitoringHealthIndicatorTest`); JaCoCo BUNDLE 0.80 line +
  0.80 branch met from day one. Root `pom.xml` adds the new
  `<module>log-monitoring-service</module>` line; `docs/adr/INDEX.md`
  bumped 43 -> 44 with a new "Monitoring pipeline (P8)"
  section; `docs/p7-to-p8-handoff.md` lands as the cross-
  epic handoff doc; module README ships with the standard
  10-section layout + `P8.0 SHIPPED` banner. P8.1 (real
  `EurekaActuatorHealthProbe`), P8.2 (SLO budget engine +
  Alertmanager rules), and P8.1a (cross-phase closer per
  LD104) stay deferred; Grafana dashboards stay scheduled
  for P17. Leg A only at P8.0 per LD104 closer pattern
  (`mvn verify` BUILD SUCCESS on the per-module
  unit-test suite). Four-file tracking flip (`plan.md` row 8
  P8 + `todo.md` P8 + `checkpoint.md` Last/Next + `memory.md`
  LD entry) lands in the same PR.

- P7.1a: log-indexer-service cross-phase closer (PR for #109,
  ADR-0043). Closes the P7 epic by adding the **Leg B/C/D/E
  artifacts** that the per-phase ships (P7.0..P7.4) deferred
  per the LD104 closer-pattern precedent: a full-stack
  PowerShell boot smoke + a Postman collection + a cross-phase
  Failsafe IT that exercises every P7.0..P7.4 SPI flow through
  autowired Spring beans + ADR-0043 + INDEX bump 42 -> 43 +
  README banner flip + four-file tracking flip. New
  `log-indexer-service/src/test/java/io/cortex/indexer/closer/QuickwitCrossPhaseIT.java`
  (~500 lines) -- ONE `@SpringBootTest` class with BOTH
  binder gates flipped to `quickwit`
  (`cortex.indexer.admin.backend=quickwit` +
  `cortex.indexer.search.backend=quickwit`), singleton
  in-process `WireMockServer` started in a static block,
  `@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)`
  + `private final` fields + package-private constructor for
  `QuickwitIndexAdmin admin` + `LogSearchClient search` +
  `MeterRegistry registry` (Checkstyle Rule 14.1 forbids
  field `@Autowired`; pattern mirrors
  `SlackCrossPhaseIT` / `PagerDutyCrossPhaseIT` /
  `JiraCrossPhaseIT` from log-remediation-service per
  ADR-0037). 12 test methods: 2 binder-gate proofs
  (`adminBeanIsQuickwitBackend` + `searchBeanIsQuickwitBackend`);
  4 P7.1 admin tests (`ensureIndex` create / `ensureIndex`
  exists / `dropIndex` present / `dropIndex` idempotent on
  404); 1 P7.2 retention test (`applyRetention` POSTs delete-
  tasks with the correct cutoff); 2 P7.3 budget tests
  (under-ceiling allow / over-ceiling reject); 3 P7.4 search
  tests (happy path with 2 hits / 404 permanent /
  tenant-mismatch permanent without contacting Quickwit). Per-
  test `@BeforeEach` resets WireMock; `@DynamicPropertySource`
  maps `cortex.indexer.quickwit.base-url` to
  `WIRE_MOCK::baseUrl`. The IT asserts on observable
  production surfaces only: `IndexAdminResult` + `SearchResult`
  envelope shape + the
  `cortex_indexer_index_admin_total{backend,outcome,tenant_id}`
  + `cortex_indexer_search_total{backend,outcome,tenant_id}`
  counter deltas (Part 17 allowlist) + the wire shape of
  every POST/GET/DELETE/search call via WireMock `verify()`
  with JSON-path body matchers. New full-stack PowerShell
  smoke `scripts/smoke-p7-1a.ps1` (~280 lines) -- `Start-
  Transcript`, params `-SkipInfra` / `-KeepInfra` /
  `-SkipEureka`, `docker compose -f infra/local/docker-
  compose.smoke.yml up -d quickwit` then
  `Wait-Url $quickwit/health/livez 90s` +
  `Wait-Url $quickwit/api/v1/version 30s`, optional
  Eureka boot, boots log-indexer-service with env bag
  (`CORTEX_INDEXER_BACKEND=quickwit`,
  `CORTEX_INDEXER_SEARCH_BACKEND=quickwit`,
  `CORTEX_QUICKWIT_BASE_URL=http://localhost:7280`,
  `CORTEX_QUICKWIT_REQUEST_TIMEOUT=10s`,
  `CORTEX_QUICKWIT_DOC_MAPPING_VERSION=v1`,
  `EUREKA_DEFAULT_ZONE=http://localhost:8761/eureka/`),
  asserts `/actuator/health/quickwit` returns `status=UP` +
  `details.backend=quickwit`, asserts
  `/actuator/prometheus` exposes BOTH counter families with
  `# HELP` + `# TYPE counter` + at least one
  `backend="quickwit"` series for each, tears down via
  `pidFile` + `Register-EngineEvent`
  `PowerShell.Exiting` exit trap so the JVM cannot leak
  on Ctrl-C. New `infra/local/docker-compose.smoke.yml
  quickwit:` service entry (`image:
  quickwit/quickwit:0.8.1`, `container_name:
  cortex-smoke-quickwit`, `ports: ["7280:7280"]`, `command:
  ["run"]`, `healthcheck: /health/livez 10s/18/30s`,
  `restart: unless-stopped`). New
  `postman/log-indexer.postman_collection.json` (5 folders /
  13 requests / 55 assertions) -- Admin (actuator: health +
  liveness + readiness + `health/quickwit` binder-gate proof
  + info + metrics + prometheus with HELP+TYPE +
  `backend="quickwit"` series on both families) +
  Metrics-Baseline (snapshot per-family sums into
  `index_admin_baseline_quickwit` + `search_baseline_quickwit`
  env vars) + Quickwit-Admin (`/health/quickwit` re-check +
  direct Quickwit `/health/livez` + `/api/v1/indexes`;
  `pm.execution.skipRequest()` when `quickwit_base_url`
  empty) + Quickwit-Search (404-permanent wire contract probe
  at `/api/v1/cortex-no-such-index-v1/search` body `{"query":"*",
  "max_hits":1}`; same skip-gate) + Metrics-After (both
  families still exposed with HELP+TYPE + monotonically non-
  decreasing sums vs baseline). Three env files: `local`
  (`base_url=http://localhost:8097` +
  `quickwit_base_url=http://localhost:7280` +
  `tenant_id=tenant-IT`), `staging` (blank
  `quickwit_base_url`), `prod` (blank `quickwit_base_url`).
  `postman/README.md` matrix bump (LD116) adds a 12-row
  matrix for the new collection + a folder-order rationale
  block + the Newman invocation incantation. Boot order
  matters: `scripts/smoke-p7-1a.ps1 -KeepInfra` then
  `npx newman run postman/log-indexer.postman_collection.json
  -e postman/log-indexer.postman_environment_local.json
  --reporters cli --bail`. ADR-0043 ships in this PR with 4
  rejected alternatives (per-test `@RegisterExtension`
  WireMock, Testcontainers Quickwit-as-IT-backend, 3-5
  `@SpringBootTest` subclasses, inbound REST controller for
  the SPI) + the `@Lazy` cycle-break decision recorded as
  D2 + LD131 captured (canonical pattern for any future
  module where a `<X>Metrics`-style bootstrap loop injects
  `List<SomeSpi>` AND the SPI adapters inject the same
  metrics bean).
  - **New production fix code** (`log-indexer-service/src/main/java/`):
    `io.cortex.indexer.admin.quickwit.QuickwitHttpAdmin` ctor
    parameter `IndexerMetrics metrics` gains `@Lazy` (see the
    `### Fixed` entry below for full context);
    `io.cortex.indexer.search.quickwit.QuickwitHttpSearch`
    ctor parameter `IndexerMetrics metrics` gains the same
    `@Lazy` treatment.
  - **New test code** (`log-indexer-service/src/test/java/`):
    `io.cortex.indexer.closer.QuickwitCrossPhaseIT` (cross-
    phase Failsafe IT, 12 tests).
  - **New infra / scripts / Postman**:
    `infra/local/docker-compose.smoke.yml` (`quickwit:`
    service); `scripts/smoke-p7-1a.ps1`;
    `postman/log-indexer.postman_collection.json`;
    `postman/log-indexer.postman_environment_{local,staging,prod}.json`;
    `postman/README.md` matrix bump.
  - **Docs / ADR**: `docs/adr/0043-log-indexer-cross-phase-
    closer.md` (this ADR); `docs/adr/INDEX.md` count bump
    42 -> 43 + new ADR-0043 row under "Indexer pipeline
    (P7)" + `Last refreshed: 2026-06-08`;
    `log-indexer-service/README.md` banner flipped
    `P7.0+P7.1+P7.2+P7.3+P7.4 SHIPPED` ->
    `P7.0+P7.1+P7.2+P7.3+P7.4+P7.1a SHIPPED` + new section
    4z "Cross-phase closer (P7.1a, ADR-0043)".

### Fixed

- P7.1a: log-indexer-service production startup
  `BeanCurrentlyInCreationException` when
  `cortex.indexer.admin.backend=quickwit` and/or
  `cortex.indexer.search.backend=quickwit` (PR for #109,
  ADR-0043 D2, LD131). Spring closes the cycle
  `IndexerMetrics` (ctor injects `List<QuickwitIndexAdmin>`
  + `List<LogSearchClient>` to drive the LD106 + LD112
  bootstrap loop in `@PostConstruct`) -> `QuickwitHttpAdmin`
  / `QuickwitHttpSearch` (each injects `IndexerMetrics` to
  tick on outcomes) -> `IndexerMetrics`, and fails bean
  construction at startup. The per-phase
  `QuickwitHttpAdminWireMockIT` / `QuickwitHttpSearchWireMockIT`
  did NOT catch this -- they construct the adapter directly
  with `new`, completely bypassing Spring's bean factory.
  The defect was surfaced for the first time by the new
  `QuickwitCrossPhaseIT` (P7.1a closer), which is exactly
  the LD104 closer-pattern value proposition: cross-phase
  closers structurally catch wiring defects that per-phase
  unit + WireMock ITs cannot detect by construction. Fix:
  `@Lazy` on the `IndexerMetrics metrics` ctor parameter
  of both `QuickwitHttpAdmin` AND `QuickwitHttpSearch`.
  Spring substitutes a JDK proxy for the `IndexerMetrics`
  injection that resolves the real bean on first method
  call, by which time both ends of the cycle are fully
  constructed. The proxy-vs-real-bean swap is transparent
  at the call site -- the adapters continue to call
  `metrics.incIndexAdmin(...)` / `metrics.incSearch(...)`
  without caring whether the reference is a proxy or the
  real bean. Single-import + single-annotation change per
  adapter; no public API change to the SPI; no behaviour
  change in the metrics bootstrap loop. Multi-paragraph
  Javadoc added to both ctors cross-referencing ADR-0043
  D2 and LD131 so the next maintainer understands why
  `@Lazy` is essential and why removing it would re-
  introduce the cycle. This is canonical: any future module
  whose `<X>Metrics` bootstrap loop injects `List<SomeSpi>`
  AND whose SPI adapters inject the same metrics bean MUST
  apply the same `@Lazy` pattern, AND MUST add a cross-
  phase closer to detect the cycle by construction.

### Added

- P7.4: log-indexer-service tenant-scoped Quickwit search
  proxy via `LogSearchClient` SPI (PR for #107, ADR-0042).
  Adds the **read-side** of the indexer-service alongside the
  existing P7.0..P7.3 admin-side SPI. New `LogSearchClient`
  SPI in a brand-new `io.cortex.indexer.search` package with
  the same shape as `QuickwitIndexAdmin` -- one
  `String backendId()` + one
  `SearchResult search(SearchRequest)` method. A noop default
  `NoopLogSearchClient` `@ConditionalOnProperty(matchIfMissing=true)`
  ships gated by `cortex.indexer.search.backend=noop` so the
  scaffold boots green with zero Quickwit dependency; flipping
  the property to `quickwit` activates the real
  `QuickwitHttpSearch` adapter (mutually exclusive at the
  `@ConditionalOnProperty` level). New `SearchRequest(String
  tenantId, String indexId, String query, int maxHits)`
  immutable record in the same package with compact-ctor
  null/blank rejection on every String field plus `maxHits >
  0` rejection -- configuration bugs surface at construction
  time. New `SearchResult(String backend, String outcome,
  String reason, long numHits, List<Map<String,Object>> hits)`
  envelope with backend constants `BACKEND_NOOP/QUICKWIT`,
  outcome constants
  `OUTCOME_NOOP/SEARCH_OK/TRANSIENT_FAILURE/PERMANENT_FAILURE`,
  factory methods `noop/searchOk/transientFailure/permanentFailure`,
  defensive `List.copyOf(hits)` in the compact constructor so
  the published list is immutable and a mutation of the caller
  list after construction does not leak into the verdict.
  `QuickwitHttpSearch` adapter shares the existing
  `quickwitAdminRestClient` bean from `QuickwitHttpConfig`
  (P7.1) -- the wire posture is HTTP/1.1 pinned via
  `JdkClientHttpRequestFactory` (LD42) with dual connect+read
  timeout (LD121) for free. The adapter posts
  `{"query":"...","max_hits":N}` to
  `POST /api/v1/{indexId}/search` and parses the
  `{"num_hits":N,"hits":[...]}` response. **Strict client-side
  tenant-routing guardrail** per ADR-0042 D3: every request's
  `indexId` MUST start with `cortex-<tenantId>-` (mirror of
  `QuickwitHttpAdmin.INDEX_ID_PREFIX="cortex-"`); a mismatch
  returns `permanent_failure / quickwit:tenant-mismatch`
  WITHOUT contacting Quickwit at all, fail-closed at the
  lowest possible cost so a future controller that forgets
  to validate the input cannot leak across tenants. Full
  outcome table mirrors ADR-0039 / ADR-0040 with one explicit
  deviation: a **404** on the search endpoint is permanent
  (`quickwit:4xx:404`), NOT idempotent-success like
  `dropIndex` -- a missing index at search time is a
  caller-side configuration bug and must surface loudly to
  the operator. New sibling counter
  `cortex.indexer.search_total{backend, outcome, tenant_id}`
  joins the existing
  `cortex.indexer.index_admin_total{...}` family on the SAME
  Part 17 allowlist (3 tag keys); `IndexerMetrics` ctor gains
  `List<LogSearchClient> searchClients` injection and the
  `@PostConstruct` bootstrap loop registers
  `search_ok / transient_failure / permanent_failure` per
  backend's `backendId()` plus one all-`unknown` placeholder
  per LD106 + LD112 so `/actuator/prometheus` exposes the
  family on the very first scrape. ArchUnit contract gains
  a new `Search` layer as a sibling of `Admin`; both reach
  `Metrics`. SPI MUST NOT throw -- every error path
  (null-request, tenant-mismatch, JSON-serialise failure,
  HTTP non-2xx, transport failure, unexpected RuntimeException)
  is funneled into a `SearchResult` envelope and ticked into
  the counter (ADR-0042 D6).
  - **New production code** (`log-indexer-service/src/main/java/`):
    `io.cortex.indexer.search.LogSearchClient` SPI;
    `io.cortex.indexer.search.SearchRequest` immutable record;
    `io.cortex.indexer.search.SearchResult` envelope;
    `io.cortex.indexer.search.NoopLogSearchClient`
    `@ConditionalOnProperty(matchIfMissing=true)` default;
    `io.cortex.indexer.search.quickwit.QuickwitHttpSearch`
    `@ConditionalOnProperty(backend=quickwit)` real adapter
    (~360 lines incl. Javadoc; reuses the
    `quickwitAdminRestClient` bean, `ObjectMapper`, and
    `IndexerMetrics`); `io.cortex.indexer.metrics.IndexerMetrics`
    ctor gains `List<LogSearchClient>` parameter +
    `bootstrapSearch(backend, outcome)` helper +
    `incSearch(backend, outcome, tenantId)` public increment
    method + new `METRIC_SEARCH_TOTAL =
    "cortex.indexer.search_total"` constant.
    `application.yml` adds the
    `cortex.indexer.search.backend: ${CORTEX_INDEXER_SEARCH_BACKEND:noop}`
    binder gate.
  - **New tests** (`log-indexer-service/src/test/java/`):
    `SearchRequestTest` (9 tests: happy path + null/blank
    rejection per field + zero/negative `maxHits` rejection);
    `SearchResultTest` (9 tests: factory shape + defensive
    `List.copyOf` + null-hits coerced to empty + null-backend
    coerced to noop + negative-`numHits` clamped to zero);
    `NoopLogSearchClientTest` (3 tests: `backendId` + happy
    `search` + null-request permissive);
    `QuickwitHttpSearchTest` (5 Mockito-free unit tests:
    `backendId` + null-request guard + tenant-mismatch
    guard + missing-prefix guard + body-shape canonical
    keys); `QuickwitHttpSearchWireMockIT` (5 IT cases against
    in-process `WireMockServer` on dynamic port: happy 200
    with 2-hit body + JSON-path verify of `$.query` +
    `$.max_hits` + 404 permanent + 500 transient + 429
    transient + `Fault.CONNECTION_RESET_BY_PEER` transport
    per LD120 -- LD123 30s IT-local read-timeout applies);
    `IndexerMetricsTest` gains 2 tests for the new search
    counter (bootstrap series count >= 4 + `incSearch` tags
    + null-coerce to unknown); `QuickwitHttpAdminTest` and
    `QuickwitHttpAdminWireMockIT` updated to construct
    `IndexerMetrics` with the new 3-arg ctor (extra
    `List.of()` for `searchClients`); `ArchitectureTest`
    adds the `Search` layer + tightens access rules so
    `Admin` and `Search` are siblings with no mutual
    reference outside the shared `RestClient` bean.
  - **Docs**: `docs/adr/0042-quickwit-search-proxy.md` MADR
    D1-D7 + 4 rejected alternatives (native Quickwit Java
    SDK, gRPC search client, direct passthrough without
    tenant resolver, server-side multi-tenant view) +
    explicit rejection of a separate "validation" SPI
    method; `docs/adr/INDEX.md` total ADRs 41 -> 42 + new
    P7 row;
    `log-indexer-service/README.md` banner flipped to
    `Status: P7.0 + P7.1 + P7.2 + P7.3 + P7.4 SHIPPED`;
    section 5 package layout adds the new `search/`
    subtree; section 7 properties table adds
    `cortex.indexer.search.backend`; section 8 metrics
    table adds the new `cortex.indexer.search_total`
    counter family; section 4 design-decisions adds
    the ADR-0042 row; section 10 roadmap marks P7.4
    DONE.

- P7.3: log-indexer-service per-tenant cardinality budgets
  via `ensureIndex(spec, budget)` (PR for #105, ADR-0041).
  Adds a budget-aware overload of `ensureIndex` on the P7.0
  `QuickwitIndexAdmin` SPI (ADR-0038) -- `ensureIndex(IndexSpec
  spec, CardinalityBudget budget)` -- with implementations on
  both the noop default and the real `QuickwitHttpAdmin`
  adapter. The gate stops a misconfigured agent (e.g. a tenant
  flapping `docMappingVersion` on every boot) from blowing up
  the Quickwit metastore and the `cortex.indexer
  .index_admin_total{tenant_id}` cardinality surface by
  rejecting the (N+1)-th create per tenant before it hits the
  metastore. The 1-arg `ensureIndex(IndexSpec)` overload is
  unchanged -- callers opt in to the gate by passing the
  budget. New `CardinalityBudget(int maxIndexes)` immutable
  record in `io.cortex.indexer.admin` with compact-constructor
  positive-int validation: zero or negative ceilings throw
  `IllegalArgumentException` at construction so configuration
  bugs surface at bind time, not as a confusing
  `budget-exceeded` verdict on the first call. The
  `QuickwitHttpAdmin` implementation runs the existing
  `checkExists` GET probe first (idempotent re-check of an
  existing index skips the gate and returns `exists`), then
  on 404 fetches `GET /api/v1/indexes` and counts entries
  whose `index_config.index_id` starts with
  `cortex-<tenantId>-` (client-side prefix filter; Quickwit
  0.7 does not expose a server-side filter endpoint). When
  the count is at or above the ceiling the call returns
  `permanent_failure` with the new reason
  `quickwit:budget-exceeded` -- **reusing** the existing
  outcome, NOT adding a new one. Result: the Part 17 metrics
  allowlist holds unchanged (still only `backend`, `outcome`,
  `tenant_id` tag keys), the `IndexerMetrics.bootstrapMeters()`
  loop is unchanged at 7 series per backend, and existing
  alerts filtering `outcome="permanent_failure"` catch the
  rejection automatically with no Grafana edit. All HTTP /
  transport errors on the list endpoint flow through the
  existing `RestAdminTemplate.classify{Http,Transport,Unknown}`
  helpers -- zero new classification rules. ArchUnit contract
  unchanged.
  - **New production code** (`log-indexer-service/src/main/java/`):
    `io.cortex.indexer.admin.CardinalityBudget` immutable record
    (compact-ctor positive-int validation);
    `QuickwitIndexAdmin.ensureIndex(IndexSpec, CardinalityBudget)`
    SPI overload;
    `NoopQuickwitIndexAdmin.ensureIndex(IndexSpec, CardinalityBudget)`
    noop impl;
    `QuickwitHttpAdmin.ensureIndex(IndexSpec, CardinalityBudget)`
    + new private `enforceBudget` helper + new
    `INDEX_ID_PREFIX = "cortex-"` constant; `JsonNode` import
    added for the list-response traversal.
  - **New tests** (`log-indexer-service/src/test/java/`):
    `CardinalityBudgetTest` (5 tests: positive accepted; 1
    accepted as the smallest legal value; zero, negative, and
    `Integer.MIN_VALUE` rejected); extensions to
    `NoopQuickwitIndexAdminTest` (ensureIndex-with-budget noop
    verdict), `QuickwitHttpAdminTest` (null-spec +
    null-budget guard rails), `QuickwitHttpAdminWireMockIT`
    (4 new WireMock cases: budget-clear creates; budget-exceeded
    rejects with `quickwit:budget-exceeded`; existing index
    short-circuits without hitting the list endpoint; list
    500 returns transient `quickwit:5xx:500`).
  - **Docs**: `docs/adr/0041-quickwit-cardinality-budgets.md`
    MADR D1-D7 + 4 rejected alternatives (server-side
    Quickwit quota plugin, async cleanup of orphan indexes,
    soft warn-only budget mode, separate `enforceBudget`
    SPI method); `docs/adr/INDEX.md` total ADRs 40 -> 41 +
    new P7 row;
    `log-indexer-service/README.md` banner flipped to
    `Status: P7.0 + P7.1 + P7.2 + P7.3 SHIPPED`.

- P7.2: log-indexer-service `applyRetention` via Quickwit
  Delete API (PR for #102, ADR-0040). Adds the third lifecycle
  method on the P7.0 `QuickwitIndexAdmin` SPI (ADR-0038) --
  `applyRetention(IndexSpec spec, RetentionPolicy policy)` --
  with implementations on both the noop default and the real
  `QuickwitHttpAdmin` adapter. Used by the retention sweeper
  (P7.3+) to delete every document older than `now - ttl` from
  a tenant index without dropping the index itself. New
  `RetentionPolicy(Duration ttl)` immutable record in
  `io.cortex.indexer.admin` with compact-constructor strict
  validation: null / zero / negative TTLs throw
  `IllegalArgumentException` so configuration bugs surface at
  bind / construction time, not at the first Quickwit call.
  New `IndexAdminResult.OUTCOME_RETENTION_APPLIED =
  "retention_applied"` constant + `retentionApplied(backend)`
  factory join the existing outcome surface. `QuickwitHttpAdmin`
  POSTs `{"query":"*","end_timestamp":<epoch_seconds>}` to
  `/api/v1/{indexId}/delete-tasks` with the cutoff computed as
  `clock.instant().minus(policy.ttl()).getEpochSecond()`; the
  `Clock` is injected via a package-private test-seam ctor
  while the Spring `@Autowired` ctor delegates with
  `Clock.systemUTC()` (mirrors P5.4 `AnomaliesPublisher`). All
  HTTP outcomes flow through the existing
  `RestAdminTemplate.classify{Http,Transport,Unknown}` helpers
  with one explicit deviation: a 404 on the Delete API is
  classified as **permanent failure** (`quickwit:4xx:404`),
  NOT idempotent-success like `dropIndex` -- a missing index
  on retention is an operator config error and must surface
  loudly. The `IndexerMetrics.bootstrapMeters()` OCP loop
  gains exactly one new line registering the new outcome
  series so `/actuator/prometheus` exposes it on the first
  scrape (LD106 + LD112; Part 17 allowlist holds -- one new
  outcome value, zero new tag keys). ArchUnit contract
  unchanged.
  - **New production code** (`log-indexer-service/src/main/java/`):
    `io.cortex.indexer.admin.RetentionPolicy` immutable record;
    `QuickwitIndexAdmin.applyRetention` SPI method;
    `IndexAdminResult.OUTCOME_RETENTION_APPLIED` constant +
    `retentionApplied(backend)` factory;
    `NoopQuickwitIndexAdmin.applyRetention` noop impl;
    `QuickwitHttpAdmin.applyRetention` + `renderRetentionBody`
    + new `DELETE_TASKS_PATH` constant + new package-private
    test-seam ctor accepting `Clock`;
    `IndexerMetrics.bootstrapMeters` extended with
    `OUTCOME_RETENTION_APPLIED` entry.
  - **New tests** (`log-indexer-service/src/test/java/`):
    `RetentionPolicyTest` (4 tests: null / zero / negative
    rejected + positive accepted); extensions to
    `IndexAdminResultTest` (constants + `retentionApplied`
    factory + null-backend coercion), `NoopQuickwitIndexAdminTest`
    (applyRetention noop verdict), `QuickwitHttpAdminTest`
    (null-spec + null-policy guard rails + `renderRetentionBody`
    body shape + Clock.fixed wiring), `IndexerMetricsTest`
    (bootstrap series count 6 -> 7), `QuickwitHttpAdminWireMockIT`
    (6 new WireMock cases: happy 200 + 429 + 500 + 404 +
    400 + `Fault.CONNECTION_RESET_BY_PEER` transport fault).
  - **Docs**: `docs/adr/0040-quickwit-retention-admin.md`
    MADR D1-D7 + 5 rejected alternatives (per-index retention
    in `IndexConfig`, native Quickwit GC, client-side
    `search + delete-by-id` loop, external K8s CronJob, gRPC
    client); `docs/adr/INDEX.md` total ADRs 39 -> 40 + new
    P7 row;
    `log-indexer-service/README.md` banner flipped to
    `Status: P7.0 + P7.1 + P7.2 SHIPPED`.

- P7.1: log-indexer-service real `QuickwitHttpAdmin`
  REST API adapter + WireMock IT (PR for #100, ADR-0039).
  Lands the FIRST real backend impl behind the P7.0
  `QuickwitIndexAdmin` SPI (ADR-0038): a Quickwit HTTP admin
  client gated `cortex.indexer.admin.backend=quickwit`,
  mutually exclusive with the noop default at the
  `@ConditionalOnProperty` level. Talks to the real Quickwit
  REST admin surface (`POST /api/v1/indexes`, `GET
  /api/v1/indexes/<id>`, `DELETE /api/v1/indexes/<id>`) with
  the cluster-standard HTTP/1.1 pin (LD42) + dual connect+read
  timeout (LD121) via `JdkClientHttpRequestFactory` -- same
  wire-format shape as the P5.3 `QuickwitSink` writer + every
  P6 dispatcher. `ensureIndex` is GET-then-POST (D4 -- avoids
  parsing Quickwit's unstable `IndexAlreadyExists` 400 body);
  `dropIndex` is DELETE-and-classify-404-as-success per the
  ADR-0038 D5 SPI idempotence contract. Outcome classification
  lives in a composition-based `RestAdminTemplate` that mirrors
  the P6.0a `RestDispatchTemplate` pattern: 429 -> transient
  `quickwit:429`; 5xx -> transient `quickwit:5xx:<n>`; other
  4xx -> permanent `quickwit:4xx:<n>`; timeout cause ->
  transient `quickwit:timeout`; other transport -> transient
  `quickwit:transport`; unknown -> transient `quickwit:unknown`.
  Body shape (D6) is a static Quickwit `IndexConfig` v0.7
  doc-mapping mirroring the P5.3 `QuickwitSink.renderDoc`
  field set (id + tenant_id + event_id + ts + level + service +
  message + anomaly + severity + reason) so the indexer's
  create call produces an index that accepts the processor's
  writes. The IndexerMetrics OCP bootstrap loop picks up the
  new backend's `backendId()=quickwit` automatically -- zero
  edits in `IndexerMetrics`.
  - **New production code** (`log-indexer-service/src/main/java/`):
    `io.cortex.indexer.admin.quickwit` package with
    `QuickwitProperties` (`@Validated`
    `@ConfigurationProperties(prefix=cortex.indexer.quickwit)`
    record carrying `baseUrl` + `requestTimeout` +
    `docMappingVersion` with defensive defaults),
    `QuickwitHttpConfig` (`@Configuration` publishing the
    HTTP/1.1-pinned `RestClient` bean), `RestAdminTemplate`
    (package-private composition helper with `classifyHttp` /
    `classifyTransport` / `classifyUnknown` methods),
    `QuickwitHttpAdmin` (`@Component implements
    QuickwitIndexAdmin`, gated `havingValue=quickwit`).
    Plus the new `io.cortex.indexer.constants.IndexerHttp`
    holder (`TOO_MANY_REQUESTS=429`, `SERVER_ERROR_FLOOR=500`,
    `NOT_FOUND=404`) -- mirror of
    `io.cortex.remediation.constants.RemediationHttp`.
  - **New tests** (`log-indexer-service/src/test/java/`):
    `QuickwitPropertiesTest` (9 tests covering happy-path +
    null/blank/zero/negative coercion to defaults),
    `RestAdminTemplateTest` (12 tests covering 429 / 500 / 503
    / 400 / 401 / 403 / 415 + HttpTimeoutException +
    TimeoutException + non-timeout transport + bare transport
    + unknown RuntimeException), `QuickwitHttpAdminTest`
    (9 tests covering backendId + properties accessor +
    null-spec + null/blank-indexId guard rails + create body
    shape), and the headline
    `QuickwitHttpAdminWireMockIT` -- 14 Failsafe IT tests
    against an in-process WireMock dynamic-port server
    covering the full outcome table for both `ensureIndex`
    (creates when absent / short-circuits when exists / 429
    / 500 / 401 / POST 400 / POST 503 / transport fault) and
    `dropIndex` (happy 200 / idempotent 404 / 429 / 500 / 403
    / transport fault). Transport-fault tests use
    `Fault.CONNECTION_RESET_BY_PEER` per LD120 deterministic
    transport-fault forward rule (vs timing-based stub).
    LD123 cold-start 30 s read-timeout bump in the IT-local
    `RestClient` to absorb JIT-cold WireMock dispatch.
  - **Modified files**: `log-indexer-service/pom.xml` adds
    `org.wiremock:wiremock-standalone` test-scope dep
    (version inherited from parent's `${wiremock.version}`
    = 3.9.2); both main + test `application.yml` add the
    `request-timeout` + `doc-mapping-version` placeholders
    under `cortex.indexer.quickwit` per LD100 shadow rule;
    `ArchitectureTest` re-asserts the layered contract to
    allow `Admin -> Metrics` (P7.0 left that edge App-only
    with a `P7.1` follow-up comment that this PR now
    discharges). `NoopQuickwitIndexAdmin` is UNTOUCHED --
    its `matchIfMissing=true` still wins when the property
    is unset, so the default-dev boot is identical to P7.0.
  - **New ADR**: `docs/adr/0039-quickwit-http-admin-client.md`
    with 7 decision drivers (D1-D7) + 5 considered options
    rejected (reactive WebClient; auto-config bean discovery;
    throw-on-failure semantics; POST-and-classify-409-as-exists;
    native Quickwit Java SDK). `docs/adr/INDEX.md` row
    appended under Indexer pipeline (P7); count bumped
    38 -> 39; `Last refreshed:` bumped.
  - **Tests**: 44 unit/IT tests across 11 classes / 0 failures
    / 0 Checkstyle / 0 SpotBugs / JaCoCo BUNDLE 0.80 line
    + 0.80 branch met. Scope-of-work covers Leg A only per
    LD104 -- Postman + boot smoke + cross-phase Testcontainers
    Quickwit IT deferred to the P7.1a closer.
  - **README banner**: `Status: P7.0 + P7.1 SHIPPED`; tech-stack
    + design-decisions + config + observability + tests + roadmap
    sections all reflect the new `QuickwitHttpAdmin` surface.

- P7.0: log-indexer-service scaffold + `QuickwitIndexAdmin`
  SPI + per-backend admin contract (PR for #98, ADR-0038).
  Opens the P7 epic by shipping the SCAFFOLD-only module per
  the LD104 scaffold-phase precedent (P3.0 + P6.0): module
  compiles, Spring Boot context loads with Eureka + actuator
  + Prometheus exposition on `:8097`, `QuickwitIndexAdmin`
  SPI is in place with a default `NoopQuickwitIndexAdmin`
  (gated `cortex.indexer.admin.backend=noop`,
  `matchIfMissing=true`) so the service boots green with no
  Quickwit dependency, one Micrometer counter family
  (`cortex.indexer.index_admin_total{backend, outcome,
  tenant_id}`) is bootstrap-registered at `@PostConstruct`
  via an OCP-flipped loop over the injected
  `List<QuickwitIndexAdmin>` so adding a new admin backend
  ships zero edits in `IndexerMetrics`, and
  `QuickwitHealthIndicator` surfaces on
  `/actuator/health/quickwit`. Carves the OWNERSHIP BOUNDARY
  against `log-processor-service` P5.3 + ADR-0030 in writing
  + in code: this module owns Quickwit ADMIN (create / drop
  / retention / cardinality / future search proxy); P5.3 +
  ADR-0030's `QuickwitSink` keeps owning the WRITER side.
  ADR-0038 documents the seven decision drivers + seven
  considered options + decision outcome + consequences.
  Mirror of the ADR-0032 (`RemediationDispatcher`) one-tier-up
  pattern.
  - **New module** `log-indexer-service/`: 11 production
    java files (App + 3 admin SPI files
    `QuickwitIndexAdmin`/`IndexAdminResult`/`IndexSpec` + 1
    noop default `NoopQuickwitIndexAdmin` + 1 metrics
    `IndexerMetrics` + 1 health `QuickwitHealthIndicator` +
    4 `package-info` files) + 7 test files (`ArchitectureTest`,
    `CortexIndexerApplicationTests`,
    `NoopQuickwitIndexAdminTest`, `IndexAdminResultTest`,
    `IndexSpecTest`, `IndexerMetricsTest`,
    `QuickwitHealthIndicatorTest`) + 3 resources files
    (`application.yml` main + test shadow + `logback-spring.xml`)
    + README + ADR-0038 + INDEX bump 37 -> 38 + this
    CHANGELOG entry.
  - **Surface**: GET `/actuator/{health, liveness, readiness,
    info, metrics, prometheus, beans}` + GET
    `/actuator/health/quickwit` (UP for the noop backend with
    `{"backend":"noop"}` detail). No data-path REST contract
    at P7.0; the P7.4 search proxy + admin endpoints land
    later.
  - **Tests**: 29 tests / 0 failures /
    0 Checkstyle violations / 0 SpotBugs / JaCoCo BUNDLE
    0.80 line + 0.80 branch met from day one (no relaxed
    override block in the child pom). ArchUnit layered
    contract enforces App/Admin/Metrics/Health seams.
  - **Roadmap** (locked by ADR-0038): P7.1 real
    `QuickwitHttpIndexAdmin` HTTP client; P7.2 retention
    sweeper; P7.3 per-tenant cardinality budgets; P7.4
    search proxy; P7.1a cross-phase closer per LD104.

- P6.1a: log-remediation-service cross-phase closer
  (PR for #93, ADR-0037). Closes the P6 epic by shipping the
  Leg B/C/D/E artifacts that P6.0..P6.3 + P6.0a deferred per
  the LD104 closer-pattern -- ONE closer for all three real
  channels. No production behaviour change; this PR is
  100% test + script + doc additions.
  - **Cross-phase Failsafe IT** under
    `log-remediation-service/src/test/java/io/cortex/remediation/closer/`:
    new abstract base `AnomalyCrossPhaseBaseIT` (~234 lines)
    owns a singleton Testcontainers Kafka container
    (`apache/kafka:3.8.0`) + an in-process WireMock server
    (`WireMockServer(options().dynamicPort())`) shared across
    all 3 subclasses. Static block starts both before any
    Spring context boots. `@DynamicPropertySource baseProperties`
    registers `spring.kafka.bootstrap-servers` + disables
    Eureka. 3 sealed-shape `@SpringBootTest` subclasses
    (`SlackCrossPhaseIT`, `PagerDutyCrossPhaseIT`,
    `JiraCrossPhaseIT`), each on its own per-channel topic
    `cortex.anomalies.v1.cross-phase.<channel>` + its own
    unique consumer group-id + neutral LD123 credentials.
    Each subclass ships 2 tests (happy 2xx ->
    `outcome=dispatched` counter tick + WireMock POST
    verified + DLT empty; transient 500 ->
    `outcome=transient_failure` counter tick + same WireMock
    + DLT checks). Failsafe count: 16 prior + 6 new = 22/22
    PASS in ~3:44 wall clock; Surefire 76/76; 0 Checkstyle;
    0 SpotBugs; JaCoCo BUNDLE 0.80/0.80 met.
  - **Full-stack PowerShell boot smoke**
    `scripts/smoke-p6-1a.ps1` -- starts the actual service
    JAR three times (once per channel) against a per-channel
    Kafka topic via `CORTEX_REMEDIATION_TOPIC` env (LD125,
    eliminates cross-channel envelope replay from shared topic
    + `auto.offset.reset=earliest`) + a per-channel WireMock
    container. Publishes via `docker cp` + `docker exec sh -c
    "...producer.sh < /tmp/..."` (avoids PowerShell pipeline
    CRLF + trailing-newline mangling). Uses
    `CORTEX_REMEDIATION_DISPATCHER_PROVIDER` (NOT
    `CORTEX_REMEDIATION_DISPATCHER` -- the latter falls back
    to noop and the smoke would silently pass). ISO 8601
    timestamps formatted under `InvariantCulture` (en-IN
    locale renders `:` as `.` and breaks CloudEvents `time`
    schema). Order-independent `Get-PromCounter` filter walks
    each label substring independently (PowerShell hashtable
    enumeration is non-deterministic). All 3 channels GREEN
    end-to-end; transcript
    `scripts/logs/p6-1a/smoke-all-20260606-220655.log`.
  - **Postman collection**
    `postman/log-remediation.postman_collection.json` +
    3 environment files (`local`, `staging`, `prod`). 4
    folders / 10 requests / 25 assertions mirror the smoke
    1:1: Admin (actuator probes) + Metrics-Baseline +
    Channel-Mock-Smoke (Slack 200|404; PagerDuty 202|404;
    Jira 201|404) + Metrics-After.
    `pm.execution.skipRequest()` gates the WireMock folder
    on `wiremock_base_url` so staging + prod env runs
    exercise admin-only surfaces. Top-level test asserts
    `responseTime < 5000ms`. `postman/README.md` updated
    with the new collection's matrix + Newman snippet.
  - **ADR-0037** documents the closer + INDEX bump 36 -> 37
    + this CHANGELOG entry + `log-remediation-service/README.md`
    banner flip `P6.0..P6.3 + P6.0a SHIPPED` ->
    `P6.0..P6.3 + P6.0a + P6.1a SHIPPED` + new README
    section 4d "Cross-phase closer (P6.1a, ADR-0037)".
  - **Two new LDs** captured in `memory.md`:
    - **LD125** -- per-channel kafka topic isolation in
      multi-channel smoke runs. Root cause: shared
      `cortex.anomalies.v1` topic + `auto.offset.reset=
      earliest` causes the next channel's consumer to
      replay the previous channel's envelopes through
      different WireMock stubs, mis-classifying the
      outcome. Fix: every channel publishes/consumes on
      `cortex.anomalies.v1.<runId>.<channel>` via
      `CORTEX_REMEDIATION_TOPIC`.
    - **LD126** -- Javadoc comments CANNOT contain `*/`
      anywhere, including inside `{@code ...}` blocks. The
      Javadoc lexer terminates greedily on the first `*/`.
      JDT (Eclipse / VS Code Java language server) does
      NOT catch this; only `javac` does. Always run
      `mvn compile` after Javadoc edits; rephrase to
      avoid `*/` or use `*&#47;`.

### Changed

- P6.0a: log-remediation-service strict-rules cleanup
  (PR for #95, ADR-0036; no behavioural change — refactor only).
  Brings `log-remediation-service` to the A2 / A3 / A4 / A6 / A7
  conformance bar BEFORE the P6.1a closer ships, so the closer
  cross-phase IT runs against the cleaned-up dispatcher surface
  (LD104 closer-pattern).
  - **A6.1 — `@Validated`** added to all three
    `@ConfigurationProperties` records
    (`SlackProperties`, `PagerDutyProperties`, `JiraProperties`)
    so Spring's validation hook is wired at the configuration
    boundary even though no field-level constraints exist yet.
  - **A3.1 / A3.2 / DRY — composition over inheritance**: new
    package-private `dispatch/RestDispatchTemplate` helper
    (Effective Java item 18) owns the outer try/catch + HTTP
    outcome classification (429 -> `transient`; 5xx ->
    `transient`; other 4xx -> `permanent`; timeout ->
    `transient`; IO -> `transient`; unknown -> `transient`) +
    transport classification + no-throw-on-transient discipline
    (ADR-0032 D6). Every real-channel adapter
    (`Slack/PagerDuty/Jira RemediationDispatcher`) is now
    `final class` with its `dispatch(event)` body collapsed to
    `return template.dispatch(event, this::isConfigured, this::executePost);`.
    Each adapter carries only its channel-specific concerns
    (configured-check, endpoint, body builder, auth header).
    Behaviour preserved bit-for-bit -- every WireMock IT
    `DispatchResult` reason string is byte-identical to P6.3.
  - **A3.2 — OCP `RemediationMetrics` refactor**: constructor
    signature flipped to
    `RemediationMetrics(MeterRegistry, List<RemediationDispatcher>)`;
    `@PostConstruct bootstrapMeters()` loops over the injected
    dispatcher list and calls `bootstrap(d.channelId(), OUTCOME_*)`
    for each of the three failable outcomes. The hand-coded
    9-call bootstrap block is gone. Adding the future P6.4
    retry/DLQ channel now requires zero edits to this class.
  - **A4.2 — Lombok constructor injection**: `AnomalyConsumer`,
    `AnomalyEnvelopeParser`, `RemediationMetrics`, and all
    three real-channel adapters now use
    `@RequiredArgsConstructor` (+ `@Slf4j` where the logger
    field was hand-rolled). Hand-rolled public constructors +
    `this.x = x` blocks deleted.
  - **A2.3 — Javadoc placement / LD5 SUPERSEDED**: every
    private-method Javadoc block across the module deleted
    (was an artifact of the universal-Javadoc enforcer recorded
    as `memory.md` LD5). The Checkstyle enforcer itself
    (`checkstyle.xml`) updated to honour A2.3: scope on
    `MissingJavadocMethod` / `MissingJavadocType` / `JavadocType`
    raised from `private` to `public`/`protected`,
    `JavadocMethod.accessModifiers` reduced to
    `"public,protected"`. Header comment cites A2.3 + the LD5
    supersede.
  - **A7 — constants centralisation**: new package
    `io.cortex.remediation.constants` with `RemediationHttp.java`
    (final utility class, private `UnsupportedOperationException`
    ctor, single constant `TOO_MANY_REQUESTS = 429`) +
    Part-9.5-shaped `package-info.java`. Removes the duplicated
    `HTTP_TOO_MANY_REQUESTS = 429` declarations from each
    real-channel adapter.
  - **SPI extension**: new method
    `String channelId()` on `RemediationDispatcher`; every
    adapter returns its `DispatchResult.CHANNEL_*` constant.
    Used by the OCP-flipped `RemediationMetrics` bootstrap
    loop.
  - **Test surface**: `RemediationMetricsTest` rewritten (8
    tests) with a `fakeDispatcher(channelId)` helper +
    `bootstrapIteratesOverMultipleDispatchers` regression pin
    for the OCP loop semantics. `AnomalyConsumerTest` +
    `AnomalyConsumerKafkaIT` updated to construct
    `RemediationMetrics` with the new
    `(registry, List<RemediationDispatcher>)` signature.
    `ArchitectureTest` extended with a `Constants` ArchUnit
    layer accessible only by `Dispatch`. All 16 ITs + every
    unit test green; 0 Checkstyle errors; JaCoCo BUNDLE
    0.80/0.80 met.
  - ADR-0036 -- documents the supersede, the composition vs
    inheritance decision, the OCP flip, and the four rejected
    alternatives (abstract base, utility class, Spring AOP
    aspect, hand-coded list with TODO). `docs/adr/INDEX.md`
    row + count bump 35 -> 36 + refreshed-on date.

### Added

- P6.3: log-remediation-service Jira Cloud REST API v3 adapter
  (PR for #91, ADR-0035; LD104 closer-pattern -- Legs B-E still
  roll forward to the P6.1a closer that ships smoke + Postman +
  cross-phase regression ONCE for Slack + PagerDuty + Jira
  together after this ship).
  - New `dispatch/JiraProperties` `@ConfigurationProperties`
    record (`cortex.remediation.jira.{base-url, email, api-token,
    request-timeout, project-key, issue-type,
    severity-label-prefix}`); blank `base-url` / `email` /
    `api-token` / `project-key` tolerated per ADR-0035 D7 so
    preview/smoke envs boot green. Compact ctor coerces null/blank
    inputs to documented defaults for every field EXCEPT the four
    credential / target fields (which stay blank as the
    unconfigured signal). Default `issue-type` is `Bug`; default
    `severity-label-prefix` is `anomaly-severity` (joined to the
    lowercased AnomalyEvent severity via `-`).
  - New `dispatch/JiraHttpConfig` `@Configuration` providing the
    `jiraRestClient` bean wired with HTTP/1.1-pinned
    `JdkClientHttpRequestFactory` (LD42 symmetry with
    `LokiSink`/`QuickwitSink`/`SlackHttpConfig`/`PagerDutyHttpConfig`)
    AND pinning BOTH `HttpClient.connectTimeout(...)` and
    `factory.setReadTimeout(...)` per LD121; gated by
    `cortex.remediation.dispatcher.provider=jira` +
    `@EnableConfigurationProperties(JiraProperties.class)`
    (the boot app deliberately does NOT carry
    `@ConfigurationPropertiesScan`).
  - New `dispatch/JiraRemediationDispatcher` -- third real
    `RemediationDispatcher` implementation; posts the REST API v3
    create-issue envelope (`{fields:{project:{key}, summary,
    description{ADF doc}, issuetype:{name}, labels}}`) to
    `{baseUrl}/rest/api/3/issue` with an `Authorization: Basic
    <Base64(email:apiToken)>` header per ADR-0035 D2 and the
    ADR-0035 D3 HTTP outcome -> `DispatchResult` classification
    table (2xx -> `dispatched`; 429 ->
    `transient_failure/jira:429`; 5xx ->
    `transient_failure/jira:5xx:<code>`; other 4xx ->
    `permanent_failure/jira:4xx:<code>`; timeout ->
    `transient_failure/jira:timeout`; transport ->
    `transient_failure/jira:transport`; unknown ->
    `transient_failure/jira:unknown`; blank credentials ->
    `skipped/jira:unconfigured`; null event ->
    `skipped/jira:null-event`). ADF description is built as one
    paragraph node per non-blank `AnomalyEvent` field so Jira's
    UI renders each detail on its own line. Labels rendered as
    `["cortex-remediation", "tenant:<tenantId>",
    "<severityLabelPrefix>-<severity-lowercased>"]`. Honours
    ADR-0032 D6 (never throws on transient) + D7 (stays agnostic
    to future P6.4 retry-budget).
  - `DispatchResult` extension: `CHANNEL_JIRA` constant sits
    beside the existing `CHANNEL_SLACK` + `CHANNEL_PAGERDUTY`.
  - `RemediationMetrics` extension: bootstrap-register the three
    Jira outcome series (`{channel=jira,
    outcome=dispatched|transient_failure|permanent_failure,
    tenant_id=unknown}`) at construct time per LD106 + LD112.
  - `application.yml` + `src/test/resources/application.yml`:
    `cortex.remediation.jira.*` block with env-var defaults;
    main yml uses blank env-var default for `base-url` / `email` /
    `api-token` / `project-key`; test yml uses literal `""`
    values so the boot stays green even when the Jira provider is
    selected by an opt-in test slice.
  - Test surface: `JiraPropertiesTest` (4 tests -- compact-ctor
    null-coerce, blank-coerce, verbatim round-trip, default
    request timeout), `JiraRemediationDispatcherTest` (13 Mockito
    tests covering every outcome-table row + body renderer + ADF
    description shape + Basic-auth header build + labels;
    LD119-compliant `doReturn(bodySpec).when(bodySpec).body(any(Object.class))`
    self-type stub for `RequestBodySpec`),
    `JiraRemediationDispatcherWireMockIT` (5 IT tests against an
    in-process WireMock server on a dynamic port -- happy 201,
    401, 429, 500, transport-fault via LD120
    `Fault.CONNECTION_RESET_BY_PEER`; happy-path asserts both
    the `Authorization: Basic <Base64>` header and JSON-path
    matchers on `$.fields.project.key`, `$.fields.summary`,
    `$.fields.description.type`, `$.fields.issuetype.name`, and
    the three labels), plus 1 new
    `bootstrapRegistersAllThreeJiraOutcomeSeries` test in
    `RemediationMetricsTest`.
  - ADR-0035 -- Jira `RemediationDispatcher` adapter (REST API v3
    create-issue + Basic-auth-with-API-token + ADF description +
    label-based severity + create-issue-only + outcome
    classification); 8 rejected alternatives (Jira Service
    Management REST endpoints, Jira Cloud OAuth 2.0, Spring
    Retry, Resilience4j `@CircuitBreaker`, fail-closed boot,
    plain-text description vs ADF, auto-assign by component
    owner, account-level API token vs per-user). `docs/adr/INDEX.md`
    row + count bump 34 -> 35 + refreshed-on date.
  - `log-remediation-service/README.md` status banner bump
    (`P6.0..P6.2 SHIPPED` -> `P6.0..P6.3 SHIPPED`) +
    "Channel adapters -> Jira (P6.3, ADR-0035)" section.

- P6.2: log-remediation-service PagerDuty Events API v2 adapter
  (PR for #89, ADR-0034; LD104 closer-pattern -- Legs B-E still
  roll forward to the P6.1a closer that ships smoke + Postman +
  cross-phase regression ONCE for Slack + PagerDuty + Jira
  together after P6.3).
  - New `dispatch/PagerDutyProperties` `@ConfigurationProperties`
    record (`cortex.remediation.pagerduty.{routing-key,
    request-timeout, events-url, dedup-key-template, source,
    severity-default}`); blank routing key tolerated per
    ADR-0034 D7 so preview/smoke envs boot green. Compact ctor
    coerces null/blank inputs to documented defaults for every
    field EXCEPT `routingKey` (which stays blank as the
    unconfigured signal). Default dedup-key template is the
    single-brace literal `{tenantId}:{eventId}` (NOT Spring
    `${...}` syntax) to dodge the property-placeholder parser at
    boot.
  - New `dispatch/PagerDutyHttpConfig` `@Configuration` providing
    the `pagerDutyRestClient` bean wired with HTTP/1.1-pinned
    `JdkClientHttpRequestFactory` (LD42 symmetry with
    `LokiSink`/`QuickwitSink`/`SlackHttpConfig`) AND pinning BOTH
    `HttpClient.connectTimeout(...)` and
    `factory.setReadTimeout(...)` per LD121; gated by
    `cortex.remediation.dispatcher.provider=pagerduty` +
    `@EnableConfigurationProperties(PagerDutyProperties.class)`
    (the boot app deliberately does NOT carry
    `@ConfigurationPropertiesScan`).
  - New `dispatch/PagerDutyRemediationDispatcher` -- second real
    `RemediationDispatcher` implementation; posts the Events API
    v2 envelope (`{routing_key, event_action:"trigger",
    dedup_key, payload:{summary, severity, source,
    custom_details}}`) to `https://events.pagerduty.com/v2/enqueue`
    with the ADR-0034 D3 HTTP outcome -> `DispatchResult`
    classification table (2xx -> `dispatched`; 429 ->
    `transient_failure/pagerduty:429`; 5xx ->
    `transient_failure/pagerduty:5xx:<code>`; other 4xx ->
    `permanent_failure/pagerduty:4xx:<code>`; timeout ->
    `transient_failure/pagerduty:timeout`; transport ->
    `transient_failure/pagerduty:transport`; unknown ->
    `transient_failure/pagerduty:unknown`; blank routing key ->
    `skipped/pagerduty:unconfigured`; null event ->
    `skipped/pagerduty:null-event`). Severity mapping per
    ADR-0034 D6: pass-through `critical|error|warning|info`,
    else fall back to `severity-default` (default `"error"`)
    with the raw upstream value preserved in
    `payload.custom_details.rawSeverity`. Dedup-key substitution
    via two `String.replace` calls against `{tenantId}` +
    `{eventId}`. Honours ADR-0032 D6 (never throws on transient)
    + D7 (stays agnostic to future P6.4 retry-budget).
  - `DispatchResult` extension: `CHANNEL_PAGERDUTY` constant
    sits beside the existing `CHANNEL_SLACK`.
  - `RemediationMetrics` extension: bootstrap-register the three
    PagerDuty outcome series
    (`{channel=pagerduty, outcome=dispatched|transient_failure|permanent_failure,
    tenant_id=unknown}`) at construct time per LD106 + LD112.
  - `application.yml` + `src/test/resources/application.yml`:
    `cortex.remediation.pagerduty.*` block with env-var defaults;
    main yml uses blank env-var default for `dedup-key-template`
    (compact-ctor coerces) to dodge the Spring `${...}` parser's
    inner-colon ambiguity; test yml uses the literal quoted
    `"{tenantId}:{eventId}"`.
  - Test surface: `PagerDutyPropertiesTest` (4 tests --
    compact-ctor null-coerce, blank-coerce, verbatim round-trip,
    default request timeout), `PagerDutyRemediationDispatcherTest`
    (14 Mockito tests covering every outcome-table row + body
    renderer + severity pass-through + severity fallback;
    LD119-compliant `doReturn(bodySpec).when(bodySpec).body(any(Object.class))`
    self-type stub for `RequestBodySpec`),
    `PagerDutyRemediationDispatcherWireMockIT` (5 IT tests
    against an in-process WireMock server on a dynamic port --
    happy 202, 429, 500, 400, transport-fault via LD120
    `Fault.CONNECTION_RESET_BY_PEER`), plus 1 new
    `bootstrapRegistersAllThreePagerDutyOutcomeSeries` test in
    `RemediationMetricsTest`.
  - ADR-0034 -- PagerDuty `RemediationDispatcher` adapter
    (Events API v2 enqueue + trigger-only + deterministic
    dedup-key + outcome classification + severity-mapping
    fallback); 8 rejected alternatives (REST API v2 Incidents
    endpoint, full trigger/ack/resolve workflow, PagerDuty Apps
    OAuth, Spring Retry `@Retryable`, Resilience4j
    `@CircuitBreaker`, fail-closed boot, raise on unknown
    severity). `docs/adr/INDEX.md` row + count bump 33 -> 34 +
    refreshed-on date.
  - `log-remediation-service/README.md` status banner bump
    (`P6.0..P6.1 SHIPPED` -> `P6.0..P6.2 SHIPPED`) +
    "Channel adapters -> PagerDuty (P6.2, ADR-0034)" section.

- P6.1: log-remediation-service Slack webhook adapter (PR for #87,
  ADR-0033; LD104 closer-pattern -- Legs B-E roll forward to the
  P6.1a closer that ships smoke + Postman + cross-phase regression
  ONCE for Slack + PagerDuty + Jira together after P6.2 + P6.3).
  - New `dispatch/SlackProperties` `@ConfigurationProperties` record
    (`cortex.remediation.slack.{webhook-url, request-timeout,
    username, channel-override}`); blank webhook URL tolerated per
    ADR-0033 D5 so preview/smoke envs boot green.
  - New `dispatch/SlackHttpConfig` `@Configuration` providing the
    `slackRestClient` bean wired with HTTP/1.1-pinned
    `JdkClientHttpRequestFactory` (LD42 symmetry with
    `LokiSink`/`QuickwitSink`); gated by
    `cortex.remediation.dispatcher.provider=slack`.
  - New `dispatch/SlackRemediationDispatcher` -- first real
    `RemediationDispatcher` implementation; posts plain-text JSON
    body to Slack Incoming Webhook with the ADR-0033 D3 HTTP
    outcome -> `DispatchResult` classification table (2xx ->
    `dispatched`; 429 -> `transient_failure/slack:429`; 5xx ->
    `transient_failure/slack:5xx:<code>`; other 4xx ->
    `permanent_failure/slack:4xx:<code>`; timeout ->
    `transient_failure/slack:timeout`; transport ->
    `transient_failure/slack:transport`; blank URL ->
    `skipped/slack:unconfigured`; null event ->
    `skipped/slack:null-event`). Honours ADR-0032 D6 (never throws
    on transient) + D7 (stays agnostic to future P6.4 retry-budget).
  - `DispatchResult` extension: `CHANNEL_SLACK` constant +
    `dispatched(channel)` / `transientFailure(channel, reason)` /
    `permanentFailure(channel, reason)` factory methods.
  - `RemediationMetrics` extension: bootstrap-register the three
    Slack outcome series
    (`{channel=slack, outcome=dispatched|transient_failure|permanent_failure,
    tenant_id=unknown}`) at construct time per LD106 + LD112.
  - `application.yml` + `src/test/resources/application.yml`:
    `cortex.remediation.slack.*` block with env-var defaults; blank
    webhook URL keeps both prod + test boot green.
  - Test surface: `SlackPropertiesTest` (3 tests -- compact-ctor
    defaults + verbatim round-trip + default constant),
    `SlackRemediationDispatcherTest` (12 Mockito tests covering
    every outcome-table row + body renderer username/channel
    overrides), `SlackRemediationDispatcherWireMockIT` (5 IT tests
    against an in-process WireMock server on a dynamic port --
    happy 200, 429, 500, 400, slow-timeout), plus 2 new assertions
    in `RemediationMetricsTest` for the Slack bootstrap series +
    the null-tag fallback path.
  - New parent-managed test dep usage: `org.wiremock:wiremock-
    standalone` (first Java-test consumer in this module).
  - ADR-0033 -- Slack `RemediationDispatcher` adapter (Incoming
    Webhook + plain-text body + outcome classification + no
    in-adapter retry); 6 rejected alternatives (OAuth
    `chat.postMessage`, Slack Workflow Builder webhooks, Block
    Kit rich layout, Spring Retry `@Retryable`, Resilience4j
    `@CircuitBreaker`, fail-closed boot). `docs/adr/INDEX.md` row
    + count bump 32 -> 33 + refreshed-on date.
  - `log-remediation-service/README.md` status banner bump +
    "Channel adapters -> Slack (P6.1)" section.

- P6.0: log-remediation-service scaffold (PR for #84, ADR-0032).
  - New Maven module `log-remediation-service` on port `:8096`
    (parent `pom.xml` `<module>` block uncommented).
  - `CortexRemediationApplication` `@SpringBootApplication`
    `@EnableKafka` `@EnableDiscoveryClient` boot class.
  - `consume/AnomalyConsumer` `@KafkaListener` on
    `${cortex.remediation.topic}` (default
    `cortex.anomalies.v1`) with manual `Acknowledgment` per
    LD79 + ADR-0028 D1 symmetry.
  - `parse/AnomalyEnvelopeParser` -- decodes CloudEvents 1.0
    structured-mode JSON via `cloudevents-json-jackson`;
    enforces `specversion="1.0"` + `type="io.cortex.anomaly.v1"`
    per `docs/p5-to-p6-handoff.md` section 3.
  - `parse/AnomalyEvent` 8-field record (eventId, tenantId,
    severity, reason, ts, level, service, message) matching
    the producer-side ADR-0031 contract.
  - `parse/FailureReason` enum (`INVALID_ENVELOPE`,
    `WRONG_TYPE`, `MISSING_DATA`) + `parse/ParseException`.
  - `dispatch/RemediationDispatcher` SPI +
    `dispatch/DispatchResult` record + default
    `dispatch/NoopRemediationDispatcher` gated by
    `cortex.remediation.dispatcher.provider=noop`
    (`matchIfMissing=true`).
  - `metrics/RemediationMetrics`
    `cortex.remediation.dispatched_total{channel, outcome,
    tenant_id}` counter, bootstrap-registered at construct time
    with all-`unknown` placeholder tags per LD106 + LD112
    (Part 17 tag-key allowlist).
  - Test surface: 23 tests across `ArchitectureTest`,
    `CortexRemediationApplicationTests` (`@SpringBootTest
    @EmbeddedKafka`), `AnomalyConsumerTest` (Mockito unit, 7
    tests), `AnomalyConsumerKafkaIT` (Testcontainers Kafka
    3.8.0 IT), `NoopRemediationDispatcherTest`,
    `RemediationMetricsTest`, `AnomalyEnvelopeParserTest` (8
    tests covering every branch).
  - ADR-0032 -- `RemediationDispatcher` SPI + per-channel
    adapter contract (Slack/PagerDuty/Jira); 6 rejected
    alternatives documented (reactive SPI, native channel
    SDKs, single shared HTTP adapter, server-side per-channel
    topics, Spring AI tools framework). `docs/adr/INDEX.md`
    row + count bump 31 -> 32 + new "Remediation pipeline
    (P6)" section.
  - `log-remediation-service/README.md` ten-section pattern
    mirroring `log-processor-service/README.md`.
  - `docs/p5-to-p6-handoff.md` section 2 heading typo fixed
    (`binary mode` -> `structured-mode JSON`; body was
    already correct).

- P0: Repository bootstrap.
  - Parent Maven POM (Java 17, Spring Boot 3.3.5, Spring Cloud 2023.0.4,
    Spring AI 1.0.0).
  - Maven wrapper (script-only) pinned to Maven 3.9.9.
  - Universal Javadoc enforcement via Checkstyle (Rule 0.1.6).
  - SpotBugs + FindSecBugs at High threshold.
  - JaCoCo with 80% line + branch gates.
  - OWASP Dependency-Check (CVSS >= 8 fails build).
  - CycloneDX SBOM generation.
  - Maven Enforcer: Java 17, dependency convergence, ban duplicate versions.
  - Renovate config for weekly dependency updates.
  - Conventional Commits enforcement via commitlint.
  - LF line endings via `.gitattributes` and `.editorconfig`.
  - `.github/CODEOWNERS` and PR template.
  - Apache License 2.0.

- P1: Documentation and Architecture Decision Records.
  - `docs/ARCHITECTURE.md` - canonical architecture reference with module
    map, three-tier search routing, API surfaces, and tenant isolation.
  - `docs/PHASES.md` - public phase plan mirroring GitHub milestones.
  - `docs/adr/0000-template.md` - MADR template for future ADRs.
  - ADR-0001: Java 17 LTS runtime (no virtual threads).
  - ADR-0002: Single repo with seven service modules.
  - ADR-0003: Three-tier search (Postgres GIN + Loki + Quickwit) Day 1.
  - ADR-0004: REST + GraphQL parity on four query operations; no
    GraphQL mutations.
  - ADR-0005: RabbitMQ locally, Azure Service Bus in production via
    Spring Cloud Stream binders.
  - ADR-0006: AI provider abstraction (Ollama local, Azure OpenAI prod).
  - ADR-0007: Self-healing via runnable Ansible playbooks with two-step
    dry-run gate and per-tenant kill-switch.
  - ADR-0008: Resilience4j on every egress call (circuit breaker,
    retry, time limiter, rate limiter, fallback).
  - ADR-0009: Tenant isolation (`tenant_id` column + B-tree composite
    index + propagation through MDC, OTel baggage, bus headers).
  - ADR-0010: Storage tiering (hot Loki -> warm Blob -> archive Blob)
    with explicit `X-Allow-Cold-Read` opt-in for archive reads.
  - ADR-0011: Observability stack (OpenTelemetry traces + Micrometer
    metrics + loki4j self-logs).
  - ADR-0012: Build and quality gates (universal Javadoc + JaCoCo 80%
    + OWASP DC + SBOM, all enforced by `./mvnw verify`).

- P2: Shared agent SDK (`log-agent-lib`, PR #20-#27).
  - Lombok-free DTO contracts (`IngestBatch`, `IngestEntry`,
    `PiiMasker`) consumed by every Spring Boot service downstream.
  - Logback JSON appender with MDC propagation (`traceId`,
    `requestId`, `tenantId`, `userId`) per ADR-0011 + Rule 26.10.6.
  - ADR-0013: `log-agent-lib` is Lombok-free.

- P3.0: `log-gateway` scaffold (PR #28-#32).
  - Spring Cloud Gateway MVC (servlet, not WebFlux) per ADR-0014.
  - Eureka discovery client (`lb://` routing) per ADR-0016.
  - Actuator + Prometheus + OpenAPI v3 wired.

- P3.1: Gateway JWT auth chain (PR #33-#37).
  - HMAC-256 JWT resource server with custom converter (ADR-0015).
  - Bearer access tokens + opaque single-use refresh tokens (rule B7.5).
  - RFC 7807 problem responses with `errorCode` enum + `traceId`.
  - 22-test smoke-p3-1.ps1 covers bad-creds 401, blank 400,
    refresh rotation, tampered-token 401, OpenAPI listing, etc.

- P3.2: Bucket4j + Lettuce Redis rate limit (PR #38-#42).
  - ADR-0017 captures: global bucket per principal + anon sub-bucket +
    per-feature sub-buckets via `@RateLimitFeature` annotation.
  - `X-RateLimit-{Limit,Remaining,Retry-After}` headers on every
    rate-limited path (LD47: sub-bucket emits the global headers).
  - Excluded paths (`/api/v1/health`, swagger, prometheus) emit no
    rate-limit headers.

- P3.3: NL->LogQL via Spring AI + WireMock (PR #43-#46).
  - ADR-0018 + ADR-0006: Spring AI 1.0.0 + Ollama (local) /
    Azure OpenAI (prod) / WireMock (smoke). Auto-binder selection
    via `@ConditionalOnProperty` on `cortex.ai.provider`.
  - JSON-strict response contract `{ logql, confidence, explanation }`.
  - 422 errorCodes `NL_QUERY_INVALID` + `NL_QUERY_REFUSED` for schema
    miss / refusal; 502 `NL_QUERY_UPSTREAM_FAILED` for transport.

- P3.4: SCG RouteLocator + `@RateLimitFeature` end-to-end (PR #47-#49).
  - ADR-0021: custom annotation + RouteLocator pattern unifies
    declarative routes with per-feature rate limits.
  - Three live routes: `/api/v1/logs/**` and `/api/v1/search/**`
    (both `lb://log-echo-service`) + `/api/v1/auth/login` (sub-bucket
    5/PT10M to gate brute-force).
  - LD68: `Find-CortexLogErrors` filter knows the 6 Eureka boot-race
    INFO/WARN patterns and does NOT report them as ERROR.

- P4.0: `log-ingest-service` scaffold + RFC 7807 contract (PR #57).
  - `POST /api/v1/ingest/batch` with Bean Validation on the request
    body (`@Valid` + jakarta annotations).
  - `GlobalExceptionHandler` returning RFC 7807 envelopes with
    `errorCode` enum (`VALIDATION_FAILED`, `BAD_REQUEST`, `NOT_FOUND`,
    `INTERNAL_ERROR`, ...).
  - Eureka registration + actuator + prometheus.

- P4.1: Raw-log persistence + cold-path dedupe (PR #48).
  - ADR-0022: `raw_logs` schema with server-computed `event_id`
    (SHA-256 of canonical pre-image), tenant-FK, JSONB `labels`,
    `received_at` server timestamp, `UNIQUE (tenant_id, event_id)`.
  - JSONB roundtrip via `AbstractJdbcConfiguration.userConverters` +
    `JdbcValue.of(PGobject, JDBCType.OTHER)`.
  - Cold-path dedupe: `DbActionExecutionException` -> unique-violation
    unwrap -> 202 absorbed.
  - JaCoCo 0.80 line + branch gate ON for `log-ingest-service`.

- P4.2: Hot-path Redis dedupe + server-side PII masking (PR #51).
  - ADR-0023: Redis SETNX with PT24H TTL keyed
    `cortex:ingest:idem:{tenantId}:{idempotencyKey}`, fail-open on
    `DataAccessException`, `@ConditionalOnProperty` gate.
  - Hash-before-mask sequence so masked output does not change the
    `event_id` (LD63).
  - 3 Micrometer counters: dedupe.hit.hot, dedupe.hit.cold,
    mask.applied.total.

- P4.3: Server-side enrichment (PR #55 + fix(postman) PR #56).
  - ADR-0024: 7 decisions D1-D7. Single correlation id under three
    header aliases (`X-Request-Id` / `X-Correlation-Id` / `traceparent`),
    JWT `tid` wins over `X-Tenant-Id`, label normalization (lowercase
    keys via `Locale.ROOT`, trim values, drop blanks, last-write-wins),
    server-stamped labels excluded from `event_id` pre-image, geo
    enrichment stub (`labels.geo_country`).
  - LD73: 5-leg triangle gate (mvn verify + smoke-pN + regression
    default + regression rate-burst + newman) mandatory BEFORE
    squash-merge.

- P4.4a: Transactional outbox foundation (PR #58).
  - ADR-0025: V3 Flyway migration adds `outbox_events (id,
    tenant_id, event_id, payload_bytes, status, attempts,
    last_error, created_at, last_attempt_at)` with
    `UNIQUE (tenant_id, event_id)`.
  - `RawLogTransactionalWriter` writes `raw_logs` + `outbox_events`
    in the SAME `@Transactional(propagation=REQUIRES_NEW)` boundary
    so a Kafka publish failure can never leak `raw_logs` rows.

- P4.4b: CloudEvents 1.0 on Kafka (PR #59 + PR #60).
  - ADR-0026: SCSt outbound silently dropped sends (LD79); pivoted
    to direct `KafkaTemplate` with explicit producer contract
    (acks=all + enable.idempotence=true + retries=MAX_VALUE +
    max.in.flight=5 + compression=zstd + request.timeout=30s +
    delivery.timeout=120s).
  - CloudEvents 1.0 structured-mode JSON envelope
    (`specversion=1.0`, `type=io.cortex.logs.event.v1`,
    `source=/cortex/log-ingest-service`, `subject=tenant_id`,
    `id=event_id`, `time=ingest_time`,
    `datacontenttype=application/json`).
  - `OutboxPoller` @Scheduled fixedDelay=1s with master-switch
    short-circuit, per-row sync `send().get(10s)`, exponential
    backoff cap 5 min.
  - Detour PR #61: README architecture diagram rewrite to match
    P3+P4 shipped reality (Kafka box, agent-lib promotion,
    Control plane row, `[E]` / `[M]` tags).

- P4.4c: DLQ + Service Bus binder gate + outbox metrics (PR #63).
  - ADR-0027 D1-D6: DLQ topic `cortex.logs.events.v1.dlq` + two
    Kafka headers (`x-orig-topic`, `x-failure-reason`); retry
    exhaustion via doubling-with-cap backoff
    (`OutboxPollerProperties.isRetryExhausted`); 3 counters
    `cortex.ingest.outbox.{published,failed,dlq}_total` tagged by
    topic + tenant_id + reason; Service Bus binder profile gate via
    `@ConditionalOnProperty(name="cortex.outbox.publisher",
    havingValue="kafka", matchIfMissing=true)` with stub
    `ServiceBusOutboxPublisher` deferred to P10.
  - `OutboxEventPublisher` SPI + `KafkaOutboxPublisher` (default) +
    `ServiceBusOutboxPublisher` stub (`UnsupportedOperationException`).
  - `FailureReason` enum allowlist (5 strings) is the Prometheus
    cardinality guard for the `reason` tag.
  - `OutboxStatus.DEAD` + V4 Flyway migration
    (`outbox_events_status_dead.sql`).
  - Tests: 130 unit (+14) + 14 IT (+1 poison-row), JaCoCo LINE 0.93
    BRANCH 0.84.
  - LD79 + LD80 + LD85 + LD86 + LD87 + LD88 captured in `memory.md`.

- P4.5: P4 epic closer (PR #65, `2af00d1`, 2026-06-03).
  - `docs/adr/INDEX.md` -- single-page directory of all 28 ADRs
    (`0000` template + `0001` .. `0027`) grouped by phase.
  - `scripts/p4-5/smoke-p4-5.ps1` -- cross-phase regression wrapper
    around `smoke-all.ps1` enforcing the LD69 union contract (every
    smoke passes in at least one mode AND no smoke FAILs in the mode
    it is compatible with).
  - `.github/PULL_REQUEST_TEMPLATE.md` -- adds Part 26.10 / 26.11 +
    LD73 / LD86 / LD89 checklist items (triangle gate, scripts-first,
    LD89 false-ship antidote).
  - `README.md` -- top-line status flipped to "P0..P4 SHIPPED",
    What's-working bullets cover every shipped sub-phase through
    P4.4c, tech-stack table cites every PR, repo layout points at
    `docs/adr/INDEX.md` + `scripts/p4-{4c,5}/`.
  - `CHANGELOG.md` (this entry) -- gap-fills P2 / P3.x / P4.x and
    P4.5 closer.
  - LD89 (false-ship hallucination antidote): never claim a `gh` /
    `git` operation succeeded without quoting the actual CLI stdout
    in the same turn. Inline `Test-Path` proof for every file-system
    operation.
  - Atomic 4-file flip: P4.4c + P4.5 both -> SHIPPED, P5 -> IN
    PROGRESS. Compound flip in the same edit batch per LD46.

- P5.0: `log-processor-service` scaffold + Kafka consumer + classifier
  SPI + metrics (PR #67, `068a3f8`, 2026-06-03).
  - New Maven module `log-processor-service` with `pom.xml`, Spring Boot
    main, `application.yml` on port `:8095` (LD92 multi-NIC port pick to
    avoid the `:8094` WireMock collision shipped in P3.3).
  - Direct `@KafkaListener` with manual offset commit on
    `cortex.logs.events.v1`, container factory wired with
    `ContainerProperties.AckMode.MANUAL_IMMEDIATE`; ADR-0028 over the
    SCSt + binder approach attempted in P4.4b (LD79 carry-forward).
  - `LogEventClassifier` SPI with a `noop` default implementation
    (every record routes to `outcome=normal`), behind
    `cortex.processor.classifier.provider` so the P5.0 scaffold ships
    without any AI dependency on the hot path.
  - Micrometer counters
    `cortex.processor.events.{consumed,parsed,classified,dlq_replay}_total`
    with the Part 17 allowlist tags `topic`, `tenant_id`, `outcome`
    (allowlist enforced by `MicrometerCardinalityFilter`).
  - Eureka registration + actuator (`health,info,metrics,prometheus,beans`).
  - JaCoCo BUNDLE gate 0.80 line + 0.80 branch ON for this module from
    day one (LD23).

- P5.0a: Closer follow-ups (PR #68, `a8e539c`, 2026-06-03).
  - `log-agent-lib` `CloudEventEnvelope` + builder + JSON
    `(de)serializer` so producer + consumer agree on the envelope shape
    (`specversion=1.0`, `type=io.cortex.logs.event.v1`,
    `source=/cortex/log-ingest-service`, `subject=tenant_id`,
    `id=event_id`, `time=ingest_time`,
    `datacontenttype=application/json`).
  - log-gateway `/api/v1/logs/**` route flipped from the retired
    `lb://log-echo-service` placeholder to `lb://log-ingest-service`
    with a rewrite to `/api/v1/ingest/batch`, so external producers can
    push through the gateway and the resulting CloudEvent flows
    end-to-end through ingest -> Kafka -> processor.
  - `log-processor-service` port migration from the originally proposed
    `:8094` to `:8095` (LD92) because WireMock owns `:8094` in every
    smoke profile shipped from P3.3 forward.

- multi-NIC eureka loopback fix (PR #71, `f8146a9`, 2026-06-03).
  - `eureka.instance.preferIpAddress=true` +
    `eureka.instance.ipAddress=127.0.0.1` in the `dev` profile of every
    Spring Boot service so multi-NIC dev hosts (corporate VPN +
    Hyper-V switch + Docker bridge) register the loopback IP instead
    of an unreachable virtual adapter and `lb://` resolution stops
    racing.
  - LD93 added: dev profile must pin Eureka instance to loopback on
    multi-NIC dev hosts; otherwise `lb://log-processor-service`
    sporadically resolves to a stale virtual NIC address that no
    smoke can reach.

- P5.1: Parser + JSON-schema validator + DLQ publisher (PR #70,
  `65e2ab8`, 2026-06-03).
  - `CloudEventEnvelopeParser` walks the structured-mode JSON envelope
    from `cortex.logs.events.v1` and unmarshals the `data` payload to
    the canonical `LogEvent` POJO; rejects unknown `type`, unknown
    `specversion`, or missing required envelope fields.
  - `LogEventSchemaValidator` enforces required fields
    (`tenant_id`, `event_id`, `timestamp`, `level`, `message`) +
    `level` enum allowlist + `timestamp` ISO-8601 parsing; failures
    bubble as `LogEventSchemaException` with a
    `FailureReason` enum value used as the Prometheus `reason` tag
    (carry-forward of ADR-0027's cardinality guard).
  - `DlqPublisher` writes rejected envelopes to
    `cortex.logs.events.v1.dlq` with `x-orig-topic` +
    `x-failure-reason` headers (mirror of ingest-side ADR-0027
    contract) and increments
    `cortex.processor.events.dlq_replay_total{reason}`.
  - The consumer commits the offset AFTER the DLQ publish succeeds so a
    DLQ publish failure becomes a Kafka redelivery (LD94 ordering
    invariant); successful parses bump
    `cortex.processor.events.parsed_total` and successful classifies
    bump `cortex.processor.events.classified_total{outcome=...}`.
  - Tests: parser IT + schema validator IT + DLQ publisher IT using
    Kafka 3.8.0 KRaft single-node Testcontainer (`cortex-smoke-kafka`);
    JaCoCo BUNDLE LINE >= 0.80 BRANCH >= 0.80 holds.

- P5.2: Spring AI 1.0 GA anomaly classifier (PR #73, `e92efaf`,
  2026-06-03).
  - `SpringAiAnomalyClassifier` implements `LogEventClassifier` and
    calls `ChatClient.call(...)` against the
    `spring-ai-starter-model-ollama` binder; the prompt template is
    rendered via literal `.replace("{tenant_id}", ...)` (LD42) instead
    of ST4 so curly-brace tokens inside the user log message cannot
    crash the renderer.
  - ADR-0029 captures the provider matrix: Ollama in dev,
    Azure OpenAI in prod, WireMock-stubbed Ollama on `:8094` in smoke;
    selection is via `cortex.processor.classifier.provider` +
    `spring.ai.ollama.base-url` so the same code path is exercised in
    every environment.
  - LD42 carry-forward: `OllamaApi` is pinned to HTTP/1.1 via
    `JdkClientHttpRequestFactory` so the OkHttp default does not
    silently downgrade HTTP/2 stream resets to retried 5xx responses.
  - Classifier outcome tagged on
    `cortex.processor.events.classified_total{outcome=anomaly|normal|error|skipped}`:
    classifier errors fall back to `outcome=error` (the event is still
    parsed + committed, just not classified), the AI call timeout maps
    to `outcome=skipped`.
  - 5-leg gate (LD73) green: `mvn verify` + `smoke-p5-2.ps1` +
    `smoke-all.ps1 -Mode default` + `smoke-all.ps1 -Mode rate-burst`
    + Newman log-gateway collection. LD100 captured: the post-merge
    atomic 4-file flip MUST cite the actual squash-merge SHA per LD89
    not the pre-merge feature-branch SHA.

- P5.2a: Postman + docs gap-fill closer (this PR, `feat/p5-2a-postman-docs-closer`).
  - `postman/log-processor.postman_collection.json` (NEW): Auth ->
    Admin (actuator: health/liveness/readiness/info/metrics/prometheus)
    -> Metrics-Baseline -> Pipeline (publish via gateway logs route)
    -> Metrics-After (assert `cortex.processor.events.consumed_total`
    and `classified_total{outcome=anomaly}` both incremented) -> Error
    Scenarios. 13 requests / 30+ assertions, runs with `--bail`.
  - `postman/log-ingest.postman_collection.json` (BUMP): name flipped
    from "P4.3 enrich" to "P4.4c outbox + P5.0a logs route"; +2 new
    items asserting `cortex_ingest_outbox_{published,failed,dlq}_total`
    counter families are registered on `/actuator/prometheus`, +1 item
    asserting `cortex_ingest_outbox_published_total` strictly increases
    after a `/api/v1/ingest/batch` round-trip with a 4-second settle
    window for the `@Scheduled(fixedDelay=1s)` outbox poller. Total
    is now 18 top-level items.
  - `postman/log-processor.postman_environment_{local,staging,prod}.json`
    (NEW) + `postman/log-ingest.postman_environment_{staging,prod}.json`
    (NEW): closes the 7.6 / 25.2 / 26.6.1 staging+prod env gap.
  - `log-processor-service/README.md` (NEW): Part 4 D1 ten-section
    format (overview, architecture, tech stack, design decisions,
    SOLID + Clean Code notes, logging, run locally, docker, API docs,
    future improvements).
  - `README.md` (PATCH): SHA `21b25b9` -> `2af00d1` (LD90 false-SHA
    fix for PR #65); phase status bumped from "P0..P4 SHIPPED" to
    "P0..P5 SHIPPED" with the five new PR rows; new "log-processor"
    bullet under "What's working today on main"; project layout note
    flipped to "P5.0..P5.2 SHIPPED; P5.3 next"; scripts/ layout adds
    `p5-{0,1,2,2a}`.
  - `CHANGELOG.md` (GAP-FILL): retroactive entries for P5.0, P5.0a,
    multi-NIC, P5.1, P5.2, P5.2a with real SHAs verified via
    `git log --grep '#NN'` per LD89.
  - `scripts/p5-2a/` (NEW): five-script bundle per Part 26.10.8.3
    (`boot-full-stack.ps1`, `smoke-p5-2a.ps1`, `newman-leg-c.ps1`,
    `teardown-full-stack.ps1`, `README.md`). The full stack runs
    Eureka + gateway + ingest + echo + processor with the
    spring-ai classifier and WireMock-stubbed Ollama so the Pipeline
    folder in the Newman collection observes a deterministic anomaly
    verdict.
  - LD101 (NEW): Newman is Leg C of the LD73 5-leg gate. A smoke
    script substituted for Newman does not satisfy the gate; the
    Newman run against the per-phase collection must execute as its
    own step with `--bail` and EXIT 0.
  - LD102 (NEW): autopilot mode bans "shall I ship X?" pauses
    after a plan-first gate has been cleared. Standing approval
    covers every step that is already mandated by
    `agent-strict-rules-prompts.md`.
  - Part 26.11.8 throughput stamp on the previous shipped block
    (P5.2): "~140 tool calls / 1 subagent / 5 scripts / 19 files
    +1558/-51 / LD99+LD100".

- P5.3: `ParsedEventSink` fan-out to Loki + Quickwit
  (this PR, `feat/76-p5-3-loki-quickwit-sinks`).
  - `log-processor-service/src/main/java/io/cortex/processor/sink/`
    (NEW package, 5 production files):
    - `ParsedEventSink` -- SPI:
      `void send(RawLogEvent, Classification)` + `String name()`.
      Contract: implementations MUST NOT throw; they MUST tick
      `SinkMetrics.*Failed(...)` on every failure category and
      return.
    - `SinkProperties` --
      `@ConfigurationProperties("cortex.processor.sinks")` record
      with nested `Loki(enabled, baseUrl, requestTimeout)` +
      `Quickwit(enabled, baseUrl, index, requestTimeout)` records.
      Defensive defaults on canonical ctors so blank yml entries
      do not NPE. Both sinks default to `enabled=false`.
    - `SinkMetrics` -- `@Component("cortexSinkMetrics")`. Two
      counter families per sink:
      `cortex.processor.sink.{loki|quickwit}.published_total{tenant_id}`
      and
      `cortex.processor.sink.{loki|quickwit}.failed_total{tenant_id, reason}`
      where `reason` is the bounded enum
      `{HTTP_STATUS, TIMEOUT, TRANSPORT, SERIALIZATION, UNKNOWN}`.
      Counters lazy-registered through a `ConcurrentMap` keyed by
      `metric|tenant|reason` per LD106.
      `@EnableConfigurationProperties(SinkProperties.class)` is
      placed on `SinkMetrics` (always loaded) so the properties
      bind even when both sinks are disabled.
    - `LokiSink` -- `@Component @ConditionalOnProperty("cortex.processor.sinks.loki.enabled" = true)`.
      Posts `{streams:[{stream:{tenant_id,level,anomaly}, values:[[tsNanos,line]]}]}`
      to `{base-url}/loki/api/v1/push`. HTTP/1.1-pinned `RestClient`
      via `JdkClientHttpRequestFactory` per LD42.
    - `QuickwitSink` -- `@Component @ConditionalOnProperty("cortex.processor.sinks.quickwit.enabled" = true)`.
      Posts an NDJSON doc per event to
      `{base-url}/api/v1/{index}/ingest`. `id = event.eventId()` for
      server-side dedupe on Kafka rebalance redelivery (ADR-0030 D6).
  - `log-processor-service/src/main/java/io/cortex/processor/consume/LogEventConsumer.java`
    (MOD): ctor gains `List<ParsedEventSink> sinks` (null-safe to
    empty list); new private `fanOut(...)` method iterates the list
    inside a `try { ... } catch (RuntimeException)` so a defective
    sink can never bubble up and rewind the Kafka offset. Called
    inline AFTER the `events.classified_total{outcome}` tick and
    BEFORE `ack.acknowledge()` on the success branch.
  - `log-processor-service/src/test/java/io/cortex/processor/sink/`
    (NEW, 4 unit tests):
    - `SinkPropertiesTest` -- defensive default coverage.
    - `SinkMetricsTest` -- lazy registration + per-(tenant,reason)
      series isolation + null reason -> UNKNOWN + blank tenant ->
      unknown.
    - `LokiSinkTest` -- in-process JDK `HttpServer` stand-in;
      asserts happy-path body shape, anomaly suffix, http_status,
      transport, null event no-op, null tenant coercion,
      `name()=="loki"`.
    - `QuickwitSinkTest` -- in-process JDK `HttpServer` stand-in;
      asserts NDJSON shape, `id=eventId` dedupe key, http_status,
      transport, anomaly fields, `name()=="quickwit"`.
  - `log-processor-service/src/test/java/io/cortex/processor/consume/LogEventConsumerTest.java`
    (MOD): two `Mockito.mock(ParsedEventSink.class)` instances
    injected; existing test cases extended with sink-call
    assertions; +3 new tests:
    `sinkExceptionDoesNotBlockAckOrFanoutToPeer`,
    `emptySinkListIsTolerated`, `nullSinkListIsTolerated`.
  - `log-processor-service/src/main/resources/application.yml`
    (MOD) + `log-processor-service/src/test/resources/application.yml`
    (MOD per LD100): new `cortex.processor.sinks.{loki,quickwit}.*`
    block with env-var overrides + `enabled=false` defaults.
  - `infra/local/wiremock/mappings/loki-push.json` (NEW): WireMock
    stub `POST /loki/api/v1/push -> 204`.
  - `infra/local/wiremock/mappings/quickwit-ingest.json` (NEW):
    WireMock stub `POST /api/v1/{index}/ingest -> 200`.
  - `scripts/p5-3/` (NEW per Part 26.10.8.3, gitignored per LD86):
    `boot-full-stack.ps1` (mirrors P5.2a but flips
    `CORTEX_PROCESSOR_SINKS_{LOKI,QUICKWIT}_ENABLED=true` and points
    both base-urls at WireMock :8094), `smoke-p5-3.ps1` (extends
    P5.2a smoke with sink published_total counter delta + WireMock
    journal assertions on the two sink endpoints), `newman-leg-c.ps1`,
    `teardown-full-stack.ps1`, `README.md`.
  - `docs/adr/0030-loki-quickwit-fanout-sinks.md` (NEW): D1..D6
    plus five rejected alternatives (Kafka Connect, Vector forwarder,
    two-binder approach, synchronous blocking sinks, dedicated ML
    service is rejected via ADR-0006 cross-ref).
  - `docs/adr/INDEX.md` (BUMP): 29 -> 30; new row under "Processor
    pipeline (P5)".
  - `log-processor-service/README.md` (PATCH): banner block now
    mentions P5.3; ADR pointers section adds ADR-0030; Run locally
    section points at `scripts\p5-3\boot-full-stack.ps1`; Future
    improvements section P5.3 bullet is replaced with a P5.4 outbox
    bullet (since P5.3 is now shipped).

- P5.4: Synchronous `cortex.anomalies.v1` CloudEvents publisher
  for the future P6 `log-remediation-service` handoff
  (this PR, `feat/80-p5-4-anomalies-publisher`).
  - `log-processor-service/src/main/java/io/cortex/processor/consume/AnomaliesPublisher.java`
    (NEW): mirrors the `DlqPublisher` shape exactly. Public
    `@Autowired` Spring ctor + package-private test-seam ctor
    taking an explicit `Clock`. Reuses the existing
    `KafkaTemplate<byte[], byte[]>` bean from
    `ProcessorKafkaProducerConfig` (no new producer factory; the
    P5.1 byte[]/byte[] producer with `acks=all` +
    `enable.idempotence=true` already satisfies the contract).
    `publish(RawLogEvent, Classification)` builds a CloudEvents
    1.0 structured-mode JSON envelope (`id=eventId`,
    `source=/cortex/log-processor-service`,
    `type=io.cortex.anomaly.v1`, `subject=tenantId`,
    `datacontenttype=application/json`, `data` map of
    `{eventId, tenantId, severity, reason, ts, level, service,
    message}` in deterministic field order). Sends synchronously
    with `kafkaTemplate.send(record).get(10, SECONDS)`; on
    interrupt / NACK / timeout throws `IllegalStateException` so
    the consumer's catch leaves the source record un-acked and
    Kafka rebalance redelivery re-attempts the publish on the next
    poll. Two Kafka headers per ADR-0031 D4:
    `content-type=application/cloudevents+json` +
    `x-source-topic=${cortex.processor.topic}`. Record key =
    `eventId` bytes for downstream dedupe.
  - `log-processor-service/src/main/java/io/cortex/processor/consume/LogEventConsumer.java`
    (MOD): ctor gains a 7th argument `AnomaliesPublisher` placed
    between `dlqPublisher` and `sinks`
    (`@SuppressWarnings("checkstyle:ParameterNumber")` because the
    six cooperating collaborators of the Kafka consumer pipeline
    are the intended design). Anomaly branch becomes
    `metrics.incAnomaliesPublished(...) ->
    anomaliesPublisher.publish(...) -> fanOut(...) ->
    ack.acknowledge()`. A publish failure surfaces as
    `IllegalStateException`, is logged at ERROR with
    `eventId+tenantId`, and the method returns early WITHOUT
    calling `ack.acknowledge()` so Kafka redelivery retries the
    publish.
  - `log-processor-service/src/main/java/io/cortex/processor/metrics/ProcessorMetrics.java`
    (MOD): new counter
    `cortex.processor.anomalies.published_total{topic, tenant_id}`
    bootstrap-registered at construct-time with
    `topic=cortex.anomalies.v1` + `tenant_id=unknown` per LD106 +
    LD112 so the counter family is visible on the very first
    Prometheus scrape (Postman / smoke baseline + delta is
    unconditional; no family-presence gate required).
  - `log-processor-service/src/main/java/io/cortex/processor/config/ProcessorKafkaProducerConfig.java`
    (MOD): Javadoc bumped to reflect the second consumer of the
    same `KafkaTemplate` bean; bean wiring unchanged.
  - `log-processor-service/src/main/resources/application.yml`
    (MOD) + `log-processor-service/src/test/resources/application.yml`
    (MOD per LD100): new
    `cortex.processor.anomalies.topic: ${CORTEX_PROCESSOR_ANOMALIES_TOPIC:cortex.anomalies.v1}`.
  - `log-processor-service/src/test/java/io/cortex/processor/consume/AnomaliesPublisherTest.java`
    (NEW): builds the envelope with an in-memory `ObjectMapper`
    + a fixed `Clock`; asserts the payload bytes round-trip back
    through `JsonFormat.deserialize` to a CloudEvent with the
    documented attributes + the same `data` JSON object.
  - `log-processor-service/src/test/java/io/cortex/processor/consume/LogEventConsumerTest.java`
    (MOD): every existing test extended with a
    `Mockito.mock(AnomaliesPublisher.class)`; verifies the anomaly
    branch invokes `publish(...)` exactly once and the
    non-anomaly branches NEVER invoke it. +1 new test
    `anomalyPublishFailureLeavesRecordUnacked` proving that an
    `IllegalStateException` from the publisher prevents
    `ack.acknowledge()` from being called.
  - `log-processor-service/src/test/java/io/cortex/processor/consume/LogEventConsumerKafkaIT.java`
    (MOD): new `@Order(4) errorEnvelopeIsClassifiedAndPublishedToAnomaliesTopic()`
    + new `@TestConfiguration StubAnomalyClassifierConfig` that
    forces `level=ERROR` -> verdict `severity=HIGH`. The IT
    pre-creates `cortex.anomalies.v1` on the Testcontainers
    broker, publishes an ERROR-level envelope to the source topic,
    drains the anomalies topic with a dedicated consumer, and
    asserts the envelope shape + the two headers + the record key
    + the `data` field values + the
    `cortex.processor.anomalies.published_total` counter delta.
  - `docs/adr/0031-log-processor-anomalies-publisher.md` (NEW):
    D1..D6 plus five rejected alternatives (Postgres outbox
    table + poller, async fire-and-forget, direct StreamBridge,
    reuse the P5.3 `ParsedEventSink` SPI, schema registry
    binding).
  - `docs/adr/INDEX.md` (BUMP): 30 -> 31; new row under
    "Processor pipeline (P5)".
  - `log-processor-service/README.md` (PATCH): banner block now
    mentions P5.4; ADR pointers section adds ADR-0031 +
    cross-references LD117 (no-outbox rule for Kafka -> Kafka
    relay services); tech-stack row for the new publisher; Run
    locally section points at `scripts\p5-4\boot-full-stack.ps1`;
    Future improvements section drops the P5.4 outbox bullet and
    adds a P5.5 epic-closer bullet.
  - `scripts/p5-4/` (NEW per Part 26.10.8.3, gitignored per
    LD86): `boot-full-stack.ps1` (mirrors P5.3 + adds an
    `anomalies-drain.ps1` helper that consumes the new topic for
    smoke validation), `smoke-p5-4.ps1` (extends P5.3 smoke with
    `cortex_processor_anomalies_published_total` counter delta +
    a one-shot Kafka topic read on `cortex.anomalies.v1` that
    asserts a CloudEvent envelope with the documented two
    headers + record key + `data` fields landed),
    `newman-leg-c.ps1`, `teardown-full-stack.ps1`, `README.md`.
  - LD117 (NEW): for Kafka consumer -> Kafka producer relay
    services, the Kafka offset itself IS the durability mechanism
    -- no outbox table is needed unless the source of the verdict
    is non-Kafka. Synchronous publish on the consumer thread with
    `KafkaTemplate.send().get(timeout)` + on failure throw
    `IllegalStateException` + don't `ack.acknowledge()` lets
    Kafka rebalance redelivery retry the publish. P4.4 needed an
    outbox because HTTP ingest returns 202 Accepted to the client
    BEFORE durable persistence; P5.4 does not have that
    asymmetry.

- P5.5: Close the P5 epic (this PR, `chore/82-p5-5-close-epic`,
  closes #6 + #66 + #69 + #72 + #76 + #79 + #82).
  Docs-only closer; zero production code changes; Leg A
  `mvn verify` is the only safety check.
  - `log-processor-service/README.md` (PATCH): banner block
    flipped to `Status: P0..P5 SHIPPED` with the full P5.0..P5.5
    PR + merge SHA chain (P5.0 #67/`068a3f8`, P5.0a #68/`a8e539c`,
    P5.1 #70/`65e2ab8`, P5.2 #73/`e92efaf`, P5.2a #75/`43a94e9`,
    P5.3 #77/`6e2f51c`, P5.3a #78/`5579186`, P5.4 #81/`d2e6acc`).
    Future improvements section drops the P5.5 bullet (now done)
    and adds an explicit pointer to `docs/p5-to-p6-handoff.md`
    from the P6 bullet.
  - `docs/adr/INDEX.md` (PATCH): `Last refreshed:` bumped from
    "2026-06-04 (P5.4, PR for #80)" to "2026-06-04
    (P5.5 close-epic, PR for #82)". Total ADR count unchanged
    (31; this is a docs-only closer).
  - `docs/p5-to-p6-handoff.md` (NEW): single-page contract that
    pins everything the future P6 `log-remediation-service`
    consumer needs to subscribe to `cortex.anomalies.v1` without
    reading the P5 producer source. Covers Kafka coordinates
    (topic name, source topic, producer mode, partitions, key,
    mandatory headers), the CloudEvents 1.0 envelope shape (per
    ADR-0031), the `data` payload schema, six concrete consumer
    guidance items (idempotency, deserialization, schema
    enforcement, bootstrap counter, header forwarding, DLQ), and
    cross-references to ADR-0027 / 0029 / 0030 / 0031 + LD117 +
    PR #81. No new ADR -- the contract content already lives in
    ADR-0031; this is a navigation aid for the P6 author.
