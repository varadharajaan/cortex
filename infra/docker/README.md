# `infra/docker/` — CORTEX service container images (P10.0)

One hand-rolled multi-stage Dockerfile per runnable service. These images
are the build layer that the P10.1 `docker-compose.yml` stack runs on top
of the existing smoke datastores. See **ADR-0050** for the full rationale
and **`/memories/repo/docker-image-build-recipe.md`** for the build traps.

## Images

| Service | Dockerfile | Port | Image tag |
|---|---|---|---|
| eureka-server (standalone, ADR-0016) | `eureka-server.Dockerfile` | 8761 | `cortex/eureka-server:0.1.0` |
| log-gateway | `log-gateway.Dockerfile` | 8090 | `cortex/log-gateway:0.1.0` |
| log-ingest-service | `log-ingest-service.Dockerfile` | 8092 | `cortex/log-ingest-service:0.1.0` |
| log-echo-service | `log-echo-service.Dockerfile` | 8093 | `cortex/log-echo-service:0.1.0` |
| log-processor-service | `log-processor-service.Dockerfile` | 8095 | `cortex/log-processor-service:0.1.0` |
| log-remediation-service | `log-remediation-service.Dockerfile` | 8096 | `cortex/log-remediation-service:0.1.0` |
| log-indexer-service | `log-indexer-service.Dockerfile` | 8097 | `cortex/log-indexer-service:0.1.0` |
| log-monitoring-service | `log-monitoring-service.Dockerfile` | 8098 | `cortex/log-monitoring-service:0.1.0` |

(`log-agent-lib` is a library, not a runnable service — no image.)

## Build

The build context is the **repo root** (the builder stage compiles from
source). On Docker Desktop / Windows, pass `--network=host` so BuildKit can
reach Maven Central:

```powershell
$env:DOCKER_BUILDKIT = '1'
docker build --network=host -f infra/docker/log-gateway.Dockerfile -t cortex/log-gateway:0.1.0 .
```

Design highlights (ADR-0050):

- **Multi-stage**: `eclipse-temurin:17-jdk-jammy` builds the fat jar,
  `eclipse-temurin:17-jre-jammy` runs it.
- **Pom-free fat jar**: the service poms don't declare
  `spring-boot-maven-plugin`, so the builder runs `-am … install` then an
  app-only `package spring-boot:repackage` in one lifecycle. No pom is
  edited. (eureka-server declares the plugin and builds with a single
  `package`.)
- **Tests + quality gates skipped** in the image build (`-Dmaven.test.skip`
  + `*.skip` flags) — those are `mvn verify` / CI's job (P14).
- **Hardened runtime**: non-root `cortex` user, `-XX:MaxRAMPercentage=75`,
  `HEALTHCHECK` on `/actuator/health`, exec-form entrypoint (JVM is PID 1).

## Run (standalone)

```powershell
# eureka + echo reach health UP with no infra:
docker run --rm -p 8761:8761 cortex/eureka-server:0.1.0

# log-gateway requires the dev profile (the base profile has no JWT secret):
docker run --rm -p 8090:8090 -e SPRING_PROFILES_ACTIVE=dev -e EUREKA_CLIENT_ENABLED=false cortex/log-gateway:0.1.0
```

Services with external dependencies (ingest→Postgres, processor→Kafka,
indexer→Quickwit, …) **boot their web context** standalone but report
`/actuator/health` `503 DOWN` until wired to their datastores. Full
wired-up health over the container network is the **P10.1** compose stack.

## Verify

```powershell
# build all 8 images + boot-probe each container:
powershell -File scripts/live-e2e/smoke-p10-0.ps1
# build only (no boot probes):
powershell -File scripts/live-e2e/smoke-p10-0.ps1 -BuildOnly
```

## Full stack — `docker-compose.yml` (P10.1)

`docker-compose.yml` (project `cortex`) composes the eight images into one
runnable stack on a single `cortex-net` bridge, on top of the datastores,
so the whole ring comes online with container-to-container DNS. See
**ADR-0050 Amendment 1**.

```powershell
# build (first time) + bring the whole stack up:
docker compose -f infra/docker/docker-compose.yml up -d --build
# prove the ring works end to end (all UP + Eureka registry + getLogById
# REST/GraphQL parity through the gateway over lb://):
powershell -File scripts/live-e2e/smoke-p10.ps1
# tear down (volumes included):
docker compose -f infra/docker/docker-compose.yml down -v
```

### Naming convention (Docker == Kubernetes)

One canonical name per component — `cortex-<component>` — used as the
container name, the in-network DNS host, and (P11) the K8s
Deployment/Service name + pod prefix.

| Component | Name / DNS host | Image | Published port |
|---|---|---|---|
| Discovery | `cortex-eureka` | `cortex/eureka-server` | 8761 |
| API gateway (public entry) | `cortex-gateway` | `cortex/log-gateway` | 8090 |
| Ingest | `cortex-ingest` | `cortex/log-ingest-service` | — (internal) |
| Processor | `cortex-processor` | `cortex/log-processor-service` | — |
| Remediation | `cortex-remediation` | `cortex/log-remediation-service` | — |
| Indexer | `cortex-indexer` | `cortex/log-indexer-service` | — |
| Monitoring | `cortex-monitoring` | `cortex/log-monitoring-service` | — |
| Echo (dev stub) | `cortex-echo` | `cortex/log-echo-service` | — |
| Postgres | `cortex-postgres` | `postgres:16-alpine` | — |
| Redis | `cortex-redis` | `redis:7-alpine` | — |
| Kafka (KRaft) | `cortex-kafka` | `apache/kafka:3.8.0` | — (`:9093` in-net) |
| Quickwit | `cortex-quickwit` | `quickwit/quickwit:0.8.1` | — |
| Ollama stub | `cortex-wiremock` | `wiremock/wiremock` | — |

Only `cortex-gateway` (`:8090`, the single public entry point) and
`cortex-eureka` (`:8761`, operator visibility) publish host ports. Eureka
IP registration (`prefer-ip-address=true`) lets `lb://` resolve
container-to-container on `cortex-net`.

### Secrets

Dev creds are plain env (the gateway `dev` profile bakes in the dev JWT
secret + bootstrap users). **There is no local secrets manager.** "Vault"
in this project means **Azure Key Vault**, prod-only, surfaced via the
External Secrets Operator in Kubernetes (P11/P12). Never put a prod secret
in the compose file.
