# `infra/ansible/` - deploy, rollback, and smoke orchestration (P13)

P13 is the operator automation layer. It does not define infrastructure and
it does not define Kubernetes manifests:

- P12 Terraform owns Azure infrastructure.
- P11 Helm owns Kubernetes manifests.
- P13 Ansible runs the operational workflow over those two layers.

## Layout

```text
infra/ansible/
├── ansible.cfg
├── inventory/local.ini
├── group_vars/all.yml
└── playbooks/
    ├── provision.yml   # Terraform fmt/init/validate/optional plan/apply
    ├── deploy.yml      # helm upgrade --install
    ├── rollback.yml    # helm rollback
    ├── smoke.yml       # rollout status + in-cluster gateway health probe
    └── site.yml        # provision -> deploy -> smoke
```

## Verify

Run the P13 syntax gate:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\live-e2e\smoke-p13-ansible.ps1
```

The script uses local `ansible-playbook` if available. On this Windows host,
Ansible is not installed, so it falls back to a Python container and installs
`ansible-core` there for syntax checks.

## Deploy

From a machine with `ansible-playbook`, `terraform`, `helm`, and `kubectl`:

```bash
cd infra/ansible
ansible-playbook playbooks/deploy.yml
ansible-playbook playbooks/smoke.yml
```

Pass environment-specific Helm values:

```bash
ansible-playbook playbooks/deploy.yml \
  -e 'cortex_helm_values_files=["../helm/cortex/values-prod.example.yaml"]'
```

## Provision

`provision.yml` defaults to safe validation only: Terraform fmt, init, and
validate. It will not plan or apply unless explicitly requested.

```bash
ansible-playbook playbooks/provision.yml
ansible-playbook playbooks/provision.yml -e cortex_terraform_plan_enabled=true
```

Apply requires both a plan and an explicit confirmation:

```bash
ansible-playbook playbooks/provision.yml \
  -e cortex_terraform_plan_enabled=true \
  -e cortex_terraform_apply=true \
  -e cortex_confirm_apply=APPLY
```

## Rollback

Rollback to the previous Helm revision:

```bash
ansible-playbook playbooks/rollback.yml
```

Rollback to a specific revision:

```bash
ansible-playbook playbooks/rollback.yml -e cortex_rollback_revision=3
```
