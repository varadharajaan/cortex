# ADR-0054: P12 Terraform Azure infrastructure scaffold

- **Status**: Accepted
- **Date**: 2026-06-10
- **Deciders**: @varadharajaan (operator)
- **Tags**: infra, terraform, azure, aks, service-bus, key-vault, app-insights, P12

## Context

P11 defines how CORTEX application workloads are deployed to Kubernetes.
P12 must define the Azure infrastructure those workloads consume without
duplicating Helm's responsibility for manifests or Ansible's responsibility
for operational orchestration.

P12 also has to be honest about the current broker state. The roadmap has
long intended Azure Service Bus for production, but the implemented and
verified app path is still Kafka for local/dev and current service code.
Terraform should provision the intended production Azure broker without
claiming the applications have already switched to it.

## Decision

**D1 - Root Terraform stack under `infra/terraform`.** P12 is a single root
stack for now. Modules can be extracted when the resource graph stabilizes.

**D2 - Terraform owns Azure infrastructure, not Kubernetes manifests.** The
stack provisions Azure primitives and outputs Helm handoff values. The P11
chart remains the only place that defines Deployments and Services.

**D3 - Provision the requested production primitives.** The stack declares a
resource group, AKS cluster, Azure Container Registry, Log Analytics,
Application Insights, Key Vault, Storage account with private Blob
containers, and Azure Service Bus namespace/topics/subscriptions.

**D4 - Enable AKS workload identity from day one.** AKS has OIDC issuer and
workload identity enabled so P13/P14 can wire External Secrets and app
identities without rebuilding the cluster.

**D5 - Keep paid stateful app dependencies optional.** Azure Database for
PostgreSQL Flexible Server and Azure Cache for Redis are declared behind
`enable_postgres` and `enable_redis`, both defaulting to `false`. This makes
the dependency shape explicit without creating paid services during early
validation.

**D6 - Service Bus is provisioned but not falsely wired into current app
runtime.** Service Bus topics mirror the current async contracts
(`cortex.logs.events.v1`, anomaly topics, remediation outcomes), but the
README calls out that current app code still consumes Kafka settings. A
future application/binder migration must flip runtime wiring.

## Consequences

- P13 can consume Terraform outputs to build environment-specific Helm values.
- Azure infrastructure is declared in one place and validated without
  requiring an apply.
- No paid Azure resources are created by the P12 smoke.
- A complete production runtime still needs the app broker migration decision
  and environment-specific Postgres/Redis/Quickwit choices.

## Verification

`scripts/live-e2e/smoke-p12-terraform.ps1` passed on 2026-06-10:

- Terraform CLI available (`C:\terraform\terraform.exe`).
- `terraform fmt -recursive -check`.
- `terraform init -backend=false -input=false`.
- `terraform validate`.

No `terraform plan` or `terraform apply` was run.

## Rejected alternatives

- **Deploy Helm from Terraform.** Rejected to avoid split ownership between
  Terraform and Helm/Ansible. Terraform outputs values; Helm deploys apps.
- **Hard-create Postgres and Redis by default.** Rejected because those are
  paid stateful services and environments may bring existing managed
  instances. The resources are available behind explicit toggles.
- **Pretend Service Bus is already the live runtime broker.** Rejected because
  the verified implementation still uses Kafka configuration. Terraform can
  provision the target before app code consumes it, but docs must stay honest.
- **Use ARM/Bicep instead of Terraform.** Rejected because the roadmap calls
  for Terraform and P13 Ansible can consume Terraform outputs cleanly.
