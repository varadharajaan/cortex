# 0002. Single-repo monorepo with seven service modules

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: repo-layout, build, modules

## Context and problem statement

CORTEX consists of seven cooperating services (gateway, ingest,
processor, remediation, indexer, monitoring) plus one shared client
library (`log-agent-lib`). They evolve together, share types, and need
identical quality gates. Should they live in one repository or several?

## Decision drivers

- Single source of truth for the parent POM, quality gates, and
  dependency versions.
- Atomic cross-service refactors must be possible in one PR.
- One CI definition, one release process, one set of issues.
- The contributor onboarding cost must be low.
- A future split must remain feasible (no permanent lock-in).

## Considered options

- **Monorepo with Maven multi-module** (one parent + seven modules).
- **Polyrepo** (one repo per service plus a shared `cortex-bom`).
- **Hybrid** (one repo for libraries, one for services).

## Decision outcome

Chosen option: **Monorepo with Maven multi-module**, because shared
quality gates, atomic refactors, and a single CI definition outweigh
the modest cost of a slightly larger checkout. Maven's reactor build
plus `mvn -pl <module> -am` provides per-service builds when needed.

### Positive consequences

- Single `pom.xml` defines all dependency versions and plugin configs.
- Cross-cutting refactors (e.g., bump Spring AI) ship in one commit.
- One CI workflow; matrix is a single dimension (module name).
- Local dev is "clone once, build all".

### Negative consequences

- Repo grows over time; clones are larger.
- A bad merge can wedge the whole build until fixed.
- Per-service permissions / CODEOWNERS scoping requires extra care
  (covered by `.github/CODEOWNERS`).

## Module layout

```
cortex/                            (groupId: io.cortex, version 0.1.0-SNAPSHOT)
├── pom.xml                        (parent: cortex-parent, packaging: pom)
├── log-agent-lib/                 (jar: client SDK)
├── log-gateway/                   (jar: Spring Boot app)
├── log-ingest-service/            (jar: Spring Boot app)
├── log-processor-service/         (jar: Spring Boot app)
├── log-remediation-service/       (jar: Spring Boot app)
├── log-indexer-service/           (jar: Spring Boot app)
└── log-monitoring-service/        (jar: Spring Boot app)
```

The `<modules>` block in the parent POM is added one entry at a time as
each module phase ships (P2..P8), to keep CI green at every phase boundary.

## Pros and cons of the options

### Monorepo with Maven multi-module

- **Good**, single source of truth; atomic refactors; one CI.
- **Bad**, larger clones; one bad commit blocks everyone.

### Polyrepo

- **Good**, strong service ownership boundaries.
- **Bad**, version drift; N PRs for one refactor; N CI pipelines.

### Hybrid

- **Good**, isolates libraries from services.
- **Bad**, all the polyrepo costs plus a custom contributor workflow.

## Links

- Rule 4.x in the project contract requires the multi-module shape.
- [ADR-0012](./0012-build-and-quality-gates.md) (quality gates).
