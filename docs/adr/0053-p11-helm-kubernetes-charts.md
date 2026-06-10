# ADR-0053: P11 Helm charts for Kubernetes deployment

- **Status**: Accepted
- **Date**: 2026-06-10
- **Deciders**: @varadharajaan (operator)
- **Tags**: infra, helm, kubernetes, P11, deployment, ADR-0050

## Context

P10 shipped the container layer: eight runnable images and a full Docker
Compose stack using canonical `cortex-<component>` names. P11 needs the
Kubernetes deployment contract that P12 Terraform and P13 Ansible can build
on. The chart must stay infra-scoped and must not touch the service poms or
application code.

The important constraints are:

1. Preserve the P10 naming contract. Docker container names, in-network DNS,
   and Kubernetes Deployment/Service names must all be the same
   `cortex-<component>` values.
2. Keep app workloads separate from managed infrastructure. Stateful stores
   are local Docker in P10 and Azure-managed resources in P12; P11 should not
   embed a second Postgres/Kafka/Redis/Quickwit lifecycle.
3. Keep production secrets out of Git. Local/dev placeholders are allowed,
   but production credentials must come from Key Vault / External Secrets in
   the P12 lane.

## Decision

**D1 - One umbrella chart plus one chart per runnable service.**
`infra/helm/cortex` is the umbrella chart. It vendors eight service charts:
`cortex-eureka`, `cortex-gateway`, `cortex-ingest`, `cortex-processor`,
`cortex-remediation`, `cortex-indexer`, `cortex-monitoring`, and
`cortex-echo`.

**D2 - Shared library templates, service-owned values.** A
`cortex-common` Helm library chart defines the common Kubernetes resources:
`ServiceAccount`, optional `ConfigMap`, optional `Secret`, `Deployment`,
`Service`, optional `Ingress`, and optional `HorizontalPodAutoscaler`. Each
service chart owns its image, port, env, resource, probe, and autoscaling
values.

**D3 - Chart resources use canonical names by default.** Resource names
default to the chart name, e.g. `cortex-gateway`, instead of adding the Helm
release prefix. This intentionally matches ADR-0050 Amendment 1 so service
discovery, DNS, logs, runbooks, and future Ansible tasks use one component
name everywhere.

**D4 - P11 deploys application workloads only.** Backing dependencies are
expected to exist under configured DNS names:
`cortex-postgres`, `cortex-redis`, `cortex-kafka`, `cortex-quickwit`, and
`cortex-wiremock` for local/dev. P12 will replace those endpoints with Azure
resource outputs and Key Vault/External Secrets wiring.

**D5 - Default values mirror P10 local/dev; production values are explicit
overlays.** `values.yaml` is local-dev aligned with P10. The example
production overlay, `values-prod.example.yaml`, disables `cortex-echo` and
shows the secret/endpoint knobs that P12 must fill.

## Consequences

- P12 can consume a stable Helm interface instead of inventing deployment
  manifests inside Terraform.
- P13 can deploy and roll back with one Helm release and canonical workload
  names.
- P11 does not manage stateful stores; a cluster install needs the configured
  backing services to exist before pods reach readiness.
- Resource names are intentionally fixed. Running two CORTEX releases in the
  same namespace is not supported; use separate namespaces.

## Verification

`scripts/live-e2e/smoke-p11-helm.ps1` passed on 2026-06-10:

- `helm lint infra/helm/cortex` via `alpine/helm:3.15.4`.
- Default render produced 8 Deployments, 8 Services, 8 ServiceAccounts,
  7 ConfigMaps, and 2 Secrets.
- Default render parsed through `kubectl apply --dry-run=client`.
- Default render was accepted by the Docker Desktop Kubernetes API via
  `kubectl apply --dry-run=server`.
- Production example render produced 7 Deployments/Services with
  `cortex-echo` disabled and one additional gateway JWT Secret.

## Rejected alternatives

- **Put all services in one large chart template.** Simpler file count, but
  it hides service ownership and makes per-service overrides awkward.
- **Install datastores from P11.** This would duplicate P10 local state and
  conflict with P12's Azure-managed infrastructure ownership.
- **Use external public chart dependencies for Postgres/Redis/Kafka now.**
  That creates version and values drift before P12 decides the production
  resource shape.
- **Prefix every resource with the Helm release name.** Normal Helm style,
  but it breaks the P10/P11 canonical-name contract and makes runbooks less
  predictable.
