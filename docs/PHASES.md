# CORTEX - Delivery Phases

> Public-facing phase plan. Each phase has a matching GitHub milestone
> (`P<n>: <title>`) and a tracking issue. Internal sequencing notes,
> rule overrides, and resumption pointers live in non-public files.

---

## Status legend

- `[x]` - shipped and merged to `main`
- `[-]` - in flight
- `[ ]` - not started

---

## Phases

- `[x]` **P0 - Repo bootstrap** - parent POM, Maven wrapper, Checkstyle,
  SpotBugs, JaCoCo, OWASP, Enforcer, CI stub, READMEs, LICENSE, labels,
  milestones, issues. Build validates green.
- `[-]` **P1 - Docs and ADRs** - this document, architecture write-up,
  and ADRs 0001..0012 capturing every locked decision.
- `[ ]` **P2 - log-agent-lib** - Java client SDK module.
- `[ ]` **P3 - log-gateway** - edge service (auth, rate limit, route).
- `[ ]` **P4 - log-ingest-service** - REST ingest + bus publish.
- `[ ]` **P5 - log-processor-service** - AI enrichment via Spring AI.
- `[ ]` **P6 - log-remediation-service** - Ansible playbook runner.
- `[ ]` **P7 - log-indexer-service** - Postgres + Loki + Quickwit writers.
- `[ ]` **P8 - log-monitoring-service** - OTel, Micrometer, SLO.
- `[ ]` **P9 - GraphQL parity** - four query operations, schema-first.
- `[ ]` **P10 - Docker images** - multi-stage, distroless, reproducible.
- `[ ]` **P11 - Helm charts** - one umbrella + one chart per service.
- `[ ]` **P12 - Terraform (Azure)** - AKS, Postgres flexible server, Blob.
- `[ ]` **P13 - Ansible (real)** - provision, deploy, rollback,
  smoke-test, remediation playbooks.
- `[ ]` **P14 - CI/CD hardening** - matrix, caching, SBOM upload,
  Trivy, Cosign signatures.
- `[ ]` **P15 - Postman + Newman** - collection + environment + CI run.
- `[ ]` **P16 - E2E and load** - k6 + Gatling, SLO-driven.
- `[ ]` **P17 - Grafana and SLO** - provisioned dashboards, burn alerts.
- `[ ]` **P18 - v0.1.0 release** - tag, GitHub release, changelog freeze,
  signed artifacts.

---

## How a phase is "done"

Each phase issue has a Definition of Done checklist (per Part 21 in the
project contract). At minimum, the deliverables are:

1. Code (or infra) merged to `main` via small Conventional Commits.
2. Quality gates green: `./mvnw verify` plus CI passes on the merge commit.
3. Coverage at or above the JaCoCo bundle thresholds (80% line, 80% branch).
4. Documentation updated (README, ADR if a decision was made, this file).
5. The GitHub issue closed with a link to the commit range.

---

## Where to read more

- High-level architecture: [ARCHITECTURE.md](./ARCHITECTURE.md)
- Decision rationale: [docs/adr/](./adr/)
- Quality gate details: [ADR-0012](./adr/0012-build-and-quality-gates.md)
