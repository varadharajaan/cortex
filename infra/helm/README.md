# `infra/helm/` - CORTEX Kubernetes charts (P11)

P11 translates the P10 Docker/compose contract into Kubernetes manifests.
The chart uses the same canonical component names as Docker:
`cortex-eureka`, `cortex-gateway`, `cortex-ingest`, `cortex-processor`,
`cortex-remediation`, `cortex-indexer`, `cortex-monitoring`, and
`cortex-echo`.

## Layout

```text
infra/helm/
└── cortex/                     # umbrella chart
    ├── Chart.yaml
    ├── values.yaml             # local-dev values aligned with P10
    ├── values-prod.example.yaml
    └── charts/
        ├── cortex-common/      # library templates
        ├── cortex-eureka/
        ├── cortex-gateway/
        ├── cortex-ingest/
        ├── cortex-processor/
        ├── cortex-remediation/
        ├── cortex-indexer/
        ├── cortex-monitoring/
        └── cortex-echo/
```

Each service chart renders:

- `ServiceAccount`
- optional `ConfigMap` for non-secret env
- optional `Secret` for local/dev secret env
- `Deployment`
- `Service`
- optional `Ingress`
- optional `HorizontalPodAutoscaler`

The umbrella chart deploys all eight service charts by default. The
production example disables `cortex-echo`.

## Scope

The P11 chart owns CORTEX application workloads only. It does **not** install
stateful backing stores. The defaults expect the P10-style DNS names to exist:

| Dependency | Default DNS / endpoint |
| --- | --- |
| Postgres | `cortex-postgres:5432` |
| Redis | `cortex-redis:6379` |
| Kafka | `cortex-kafka:9093` |
| Quickwit | `http://cortex-quickwit:7280` |
| Ollama/WireMock | `http://cortex-wiremock:8080` |

P12 is responsible for Azure infrastructure and environment-specific values.
Production credentials must come from Azure Key Vault / External Secrets, not
from committed Helm values.

## Verify

Run the P11 gate:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\live-e2e\smoke-p11-helm.ps1
```

The script:

1. Uses local `helm` when available; otherwise uses `alpine/helm:3.15.4`.
2. Runs `helm lint infra/helm/cortex`.
3. Renders default local values and asserts 8 Deployments + 8 Services.
4. Runs `kubectl apply --dry-run=client`.
5. Runs `kubectl apply --dry-run=server` against the current Kubernetes API.
6. Renders `values-prod.example.yaml` and asserts `cortex-echo` is disabled.

## Install

Prerequisites:

- The eight P10 images exist in the target registry or local cluster image
  store.
- Backing dependencies are reachable under the configured DNS names.
- For production, override all placeholder values in
  `values-prod.example.yaml`.

Local-style install:

```powershell
helm upgrade --install cortex infra/helm/cortex --namespace cortex --create-namespace
kubectl -n cortex rollout status deploy/cortex-gateway
kubectl -n cortex port-forward svc/cortex-gateway 8090:8090
```

Production-style render:

```powershell
helm template cortex-prod infra/helm/cortex `
  --namespace cortex-prod `
  -f infra/helm/cortex/values-prod.example.yaml
```

## Notes

- `cortex-gateway` is the only public entry point and defaults to
  `Service.type=LoadBalancer` in the umbrella values.
- App probes use Spring Boot actuator liveness/readiness endpoints.
- The chart keeps `readOnlyRootFilesystem=false` because the current
  Spring Boot images have not yet been audited for a read-only writable temp
  directory contract. That can be tightened after P14/P18 image hardening.
