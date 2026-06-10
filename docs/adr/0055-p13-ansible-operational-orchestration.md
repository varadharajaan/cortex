# ADR-0055: P13 Ansible operational orchestration

- **Status**: Accepted
- **Date**: 2026-06-10
- **Deciders**: @varadharajaan (operator)
- **Tags**: infra, ansible, deployment, rollback, smoke, P13

## Context

P11 defines the Helm release and P12 defines Azure infrastructure. P13 needs
the operator workflow that ties those pieces together: validate/provision,
deploy, rollback, and smoke-test. It must not duplicate Terraform resources
or Kubernetes manifests.

The local Windows host does not have Ansible installed, so validation needs a
portable path that does not mutate the developer machine.

## Decision

**D1 - Ansible is an orchestration layer only.** P13 playbooks call
Terraform, Helm, and kubectl. They do not define Azure resources or
Kubernetes manifests themselves.

**D2 - Plain `ansible.builtin` modules only.** The first P13 playbooks use
`ansible.builtin.command`, `assert`, and imports. No Galaxy collections are
required for syntax validation or basic operation.

**D3 - Four direct playbooks plus one composition playbook.**

- `provision.yml` runs Terraform fmt/init/validate, optional plan, and
  guarded apply.
- `deploy.yml` runs `helm upgrade --install`.
- `rollback.yml` runs `helm rollback`.
- `smoke.yml` checks rollout status and runs an in-cluster gateway health
  probe.
- `site.yml` composes provision -> deploy -> smoke.

**D4 - Destructive actions require explicit confirmation.** Terraform apply
does not run unless `cortex_terraform_apply=true` and
`cortex_confirm_apply=APPLY`.

**D5 - Containerized syntax validation.** `smoke-p13-ansible.ps1` uses local
`ansible-playbook` when available; otherwise it runs `python:3.12-slim`,
installs pinned `ansible-core`, and syntax-checks every playbook.

## Consequences

- P13 is safe to validate on machines that do not have Ansible installed.
- The deploy/rollback/smoke workflow is now documented and versioned.
- A real deploy or rollback still requires an approved Kubernetes/Azure
  target and valid environment-specific Helm values.

## Verification

`scripts/live-e2e/smoke-p13-ansible.ps1` passed on 2026-06-10:

- Docker fallback runner `python:3.12-slim` with `ansible-core 2.17.7`.
- `ansible-playbook --syntax-check` passed for `provision.yml`,
  `deploy.yml`, `rollback.yml`, `smoke.yml`, and `site.yml`.

No real Terraform apply, Helm deploy, Helm rollback, or Kubernetes smoke run
was executed.

## Rejected alternatives

- **Put deploy logic in Terraform.** Rejected because Terraform should own
  infrastructure state, not Helm release operations.
- **Use shell scripts only.** Rejected because Ansible gives inventory,
  variable layering, task composition, and a clearer path to CI/CD runbooks.
- **Require local Ansible on Windows.** Rejected because Ansible is primarily
  a Linux control-node tool and the containerized syntax-check is portable.
