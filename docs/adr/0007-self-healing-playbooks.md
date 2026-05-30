# 0007. Self-healing via runnable Ansible playbooks

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: remediation, ansible, infra

## Context and problem statement

When `log-processor-service` produces a high-confidence anomaly (e.g.,
"PostgreSQL connection pool exhausted on `log-ingest-service` pod"),
CORTEX should be able to take corrective action automatically. The user
has upgraded Ansible from "scaffolding-only" (per the original contract)
to "real, runnable" (plan v2 approval). What is the execution model?

## Decision drivers

- Remediation must be auditable - every action logs intent, command,
  result, and operator.
- A bad playbook must not be able to take the production cluster down.
- Operators need a kill-switch per tenant and per playbook.
- The playbooks must be runnable by humans too (operations runbook).
- No new orchestration system beyond what Ansible already provides.

## Considered options

- **Ansible playbooks** invoked via `ansible-runner` from
  `log-remediation-service`.
- **Kubernetes Jobs** that exec `kubectl` / `helm` commands.
- **Argo Workflows** for orchestration.
- **Shell scripts** in a sidecar container.

## Decision outcome

Chosen option: **Real Ansible playbooks** in `infra/ansible/playbooks/`,
invoked by `log-remediation-service` via the `ansible-runner` Python
library, called from Java via `ProcessBuilder` with a sandboxed UID.

### Execution gate (every action)

1. Lookup playbook in `infra/ansible/playbooks/<anomaly-tag>.yml`.
2. Validate playbook has `auto_apply: true` (file-level metadata).
3. Run `ansible-playbook --check` (dry-run). Abort on failure.
4. Run the playbook for real.
5. Emit an audit event to `remediation.audit` topic with full output.

### Per-tenant kill-switch

```yaml
remediation:
  tenants:
    "<tenant-uuid>":
      enabled: false           # global kill
      playbooks:
        restart-ingest:
          enabled: false       # per-playbook kill
```

A `false` at any level vetoes execution; the audit event still fires
with `decision: vetoed`.

### Positive consequences

- Playbooks are also documentation; an operator can run the same file
  manually with `ansible-playbook -i inventory file.yml`.
- Two-step gate (dry-run + apply) makes hostile or buggy playbooks safe.
- Audit trail is structured (JSON in the bus), greppable, and queryable
  via the same `searchLogs` API.
- No new orchestration runtime.

### Negative consequences

- `ProcessBuilder` introduces a Python runtime dependency on the
  remediation pod image. Mitigated by a multi-stage Dockerfile that
  installs `ansible-runner` only in that one image.
- Ansible's YAML can be brittle. Mitigated by linting (`ansible-lint`)
  in CI on every change to `infra/ansible/**`.

## Pros and cons of the options

### Real Ansible via ansible-runner

- **Good**, human-runnable; declarative; well-known; idempotent.
- **Bad**, Python runtime in the remediation image.

### Kubernetes Jobs (kubectl/helm)

- **Good**, no extra runtime.
- **Bad**, harder to express multi-step workflows; less idempotent;
  no built-in dry-run for `helm upgrade`.

### Argo Workflows

- **Good**, powerful DAGs, history UI.
- **Bad**, another orchestrator; significant ops burden.

### Shell scripts in sidecar

- **Good**, simplest.
- **Bad**, no dry-run; no idempotence; no inventory; no audit by default.

## Links

- Locked decision LD7.
- [ARCHITECTURE.md §6](../ARCHITECTURE.md).
