# `infra/terraform/` - Azure infrastructure (P12)

P12 provisions the Azure infrastructure that the P11 Helm release consumes.
Terraform owns infrastructure. Helm owns Kubernetes application workloads.
Ansible (P13) will orchestrate deploy, rollback, and smoke-test flows on top.

## Resources

The root stack declares:

- Resource group
- AKS cluster with OIDC issuer + workload identity enabled
- Azure Container Registry with AKS `AcrPull`
- Log Analytics workspace
- Application Insights
- Key Vault with RBAC authorization
- Storage account + private Blob containers for cold/archive data
- Azure Service Bus namespace, topics, and default subscriptions
- Optional Azure Database for PostgreSQL Flexible Server
- Optional Azure Cache for Redis

Postgres and Redis are optional because they are paid stateful services and
because some environments may bring existing managed instances. Quickwit has
no first-party Azure managed service in this stack; provide
`quickwit_base_url` to Helm once its hosting choice is finalized.

## Service Bus note

P12 provisions Azure Service Bus as the intended production broker. Current
implemented local/dev application flows still use Kafka (`CORTEX_KAFKA_BROKERS`)
because the Kafka path is what P4/P5/P6/P19..P21 have verified. Wiring the
apps directly to Service Bus remains a later application/binder migration.
This stack deliberately creates the Service Bus namespace and topics now so
the production target is explicit without pretending the code has already
switched brokers.

## Verify

```powershell
cd infra/terraform
terraform fmt -recursive -check
terraform init -backend=false
terraform validate
```

Do **not** run `terraform apply` without an explicit operator approval. The
stack creates paid Azure resources.

## Plan

```powershell
cd infra/terraform
copy terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars or pass TF_VAR_* env vars
terraform plan -out cortex.tfplan
```

Optional secrets should come from environment variables or a secure CI secret
store, for example:

```powershell
$env:TF_VAR_postgres_admin_password = "<strong-secret>"
$env:TF_VAR_gateway_jwt_secret = "<base64-hmac-secret>"
```

## Helm handoff

After apply, use outputs to build a Helm values overlay:

```powershell
terraform output acr_login_server
terraform output key_vault_uri
terraform output service_bus_namespace_name
terraform output storage_account_name
```

The `helm_image_repository_overrides` output maps each P11 chart image field
to the provisioned ACR login server. P13 should turn these outputs into the
environment-specific Helm values file used by:

```powershell
helm upgrade --install cortex ../helm/cortex --namespace cortex --create-namespace -f values.azure.yaml
```
