# CORTEX :: infra :: eureka-server (local-dev only)

Standalone Eureka service-registry for CORTEX local-dev. See
[ADR-0016](../../../docs/adr/0016-cortex-uses-eureka-for-local-discovery.md).

**This is NOT a Cortex production artifact.** It is a throwaway,
local-only discovery registry that lets `log-gateway` resolve
`lb://<service-id>` predicates without a Kubernetes control plane.
Replaced by Kubernetes `Service` DNS in prod (Part 23.3).

## Run

From this directory:

```powershell
..\..\..\mvnw.cmd spring-boot:run     # Windows
../../../mvnw spring-boot:run          # Unix
```

Or via the smoke docker-compose:

```powershell
docker compose -f infra\local\docker-compose.smoke.yml up eureka
```

## Verify

```powershell
Invoke-RestMethod http://localhost:8761/actuator/health
# {status: UP}

Invoke-WebRequest http://localhost:8761/ | Select-Object -Expand Content
# HTML dashboard listing registered applications
```

## Properties

| Key                                              | Value                              |
|--------------------------------------------------|------------------------------------|
| `server.port`                                    | `8761`                             |
| `eureka.client.register-with-eureka`             | `false` (standalone)               |
| `eureka.client.fetch-registry`                   | `false` (standalone)               |
| `eureka.server.enable-self-preservation`         | `false` (fast local-dev eviction)  |
| `eureka.server.eviction-interval-timer-in-ms`    | `15000`                            |

## Why it lives outside the parent reactor

Per LD24: the registry is throwaway local-only infrastructure, NOT a
Cortex production artifact. Keeping it outside `cortex-parent` means it
does NOT inherit the universal-Javadoc / SpotBugs / JaCoCo / ArchUnit
gates and never accidentally ships in a Cortex release.
