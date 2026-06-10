# CORTEX

**C**ognitive **O**bservability **R**untime **T**elemetry **EX**change.

AI-Powered Intelligent Log Management System.

A production-grade, multi-tenant log ingestion, storage, search, anomaly
detection, and self-healing platform built with Spring Boot 3.3 microservices,
Spring AI, GraphQL + REST, three-tier search (Postgres GIN + Loki labels +
Quickwit full-text), and Kubernetes-native deployment.

---

## Status

[![Java](https://img.shields.io/badge/Java-17_LTS-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.6-green)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2023.0.x-green)](https://spring.io/projects/spring-cloud)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE)

**Phase progress**: current workspace includes the P19/P20/P21 + P9.3/P9.3a
auto-remediation lane in `log-remediation-service`, P11 Helm under
`infra/helm/`, P12 Azure Terraform under `infra/terraform/`, P13 Ansible
under `infra/ansible/`, and the P17 Grafana/SLO operator surface under
`infra/grafana/`. Remediation adds Redis SETNX anomaly
dedupe, Resilience4j dispatcher guard, fix-first `RemediationPlaybook` SPI,
malformed anomaly DLQ, `cortex.remediation.outcomes.v1` audit topic, a
Postgres-backed anomalies read model, and direct
`GET /api/v1/anomalies` read API. The lane was verified on 2026-06-09 with
module `clean verify`, live JVM smoke, and Newman against the live
remediation process. P11 was verified on 2026-06-10 with Helm lint/template,
kubectl client dry-run, and Docker Desktop Kubernetes server dry-run via
`scripts/live-e2e/smoke-p11-helm.ps1`. P12 was verified on 2026-06-10 with
Terraform fmt/init/validate via `scripts/live-e2e/smoke-p12-terraform.ps1`;
no Azure apply was run. P13 was verified on 2026-06-10 with containerized
Ansible syntax checks via `scripts/live-e2e/smoke-p13-ansible.ps1`; no real
deploy/rollback was run. P14 promotes GitHub Actions from the P0 stub to full
CI/CD: root `mvn verify` with Testcontainers, eight P10 image builds, a Trivy
vulnerability scan of every built image, GHCR publish on trusted push/tag
events, and keyless cosign signing of pushed image digests. P17 was verified
on 2026-06-10 with live
Prometheus/Grafana boot via `scripts/live-e2e/smoke-p17-grafana.ps1`. P18
release-prep was dry-run verified with
`scripts/live-e2e/smoke-p18-release-prep.ps1`; the actual tag, SBOM build,
cosign signatures, and GitHub Release remain behind an explicit operator
approval gate. Earlier baseline: P0 .. P5 shipped through P5.2 on `main`; P6
dispatcher adapters and the P6.1a cross-phase closer are documented in the
remediation README and ADRs.

See [docs/PHASES.md](docs/PHASES.md) for the 19-phase roadmap (P0 - P18) and
[docs/adr/INDEX.md](docs/adr/INDEX.md) for the ADR directory.

### What's working today on the current branch

- **log-gateway** :8090 -- JWT + API-key auth, Bucket4j + Redis rate limit
  (global + anon + per-feature sub-buckets via `@RateLimitFeature`),
  RFC 7807 problem responses, NL->LogQL via Spring AI + Ollama (WireMock
  in smoke), correlation-id propagation, RouteLocator + `lb://` discovery.
- **log-ingest-service** :8092 -- `POST /ingest/batch` validates batches,
  masks PII before hashing (deterministic `event_id`), hot-dedupes via
  Redis SETNX (cold dedupe in Postgres backstop), persists to `raw_logs`,
  enriches from `X-Tenant-Id` + JWT `tid` + `X-Request-Id`, writes to
  `outbox_events` in the same transaction. Outbox poller publishes
  CloudEvents 1.0 JSON envelopes to Kafka via direct `KafkaTemplate`
  (ADR-0026) with exponential backoff + DLQ fan-out after retry
  exhaustion (ADR-0027). Counters exposed at `/actuator/prometheus` as
  `cortex.ingest.outbox.{published,failed,dlq}_total`.
- **log-processor-service** :8095 -- direct `@KafkaListener` consumer
  with manual offset commit (ADR-0028, LD79); CloudEvents 1.0 envelope
  parse + JSON-schema validation; Spring AI 1.0 GA anomaly classifier
  (`spring-ai-starter-model-ollama`) with WireMock-stubbed Ollama on
  `:8094` for deterministic smoke (ADR-0029); DLQ fan-out to
  `cortex.logs.events.v1.dlq`; counters
  `cortex.processor.events.{consumed,parsed,classified,dlq_replay}_total`
  (the `classified_total` is tagged `outcome=anomaly|normal|error|skipped`).
  Actuator-only HTTP surface; pipeline activation flows through the
  Kafka topic `cortex.logs.events.v1`.
- **log-remediation-service** :8096 -- consumes
  `cortex.anomalies.v1`, parses anomaly CloudEvents, sends malformed
  envelopes to `cortex.anomalies.v1.dlq`, dedupes valid events in Redis,
  runs policy-gated `RemediationPlaybook` dry-run/apply, publishes every
  valid decision to `cortex.remediation.outcomes.v1`, persists valid
  anomaly rows to its own Postgres read model, exposes
  `GET /api/v1/anomalies`, and dispatches Slack/PagerDuty/Jira only when
  the outcome is `skipped` or `failed` (ADR-0051/0052). Auto-fix success
  is silent except for the audit topic.
- **eureka-server** :8761 -- service registry; every Spring Boot service
  registers and discovers via `lb://`.
- **log-echo-service** :8093 -- thin echo backstop used by gateway smokes
  to assert end-to-end routing.
- **infra (docker compose smoke)** -- postgres:16-alpine, redis:7-alpine,
  `confluentinc/cp-kafka` (P4.4b+), `wiremock` :8094.
- **infra/helm** -- P11 umbrella chart `infra/helm/cortex` plus one service
  chart per runnable component. Canonical Kubernetes names match P10 Docker
  DNS (`cortex-gateway`, `cortex-ingest`, `cortex-remediation`, etc.).
  Verified by Helm lint/template and kubectl client/server dry-runs.
- **infra/terraform** -- P12 Azure scaffold for AKS, ACR, Key Vault, Blob,
  App Insights/Log Analytics, Service Bus, and optional Postgres/Redis. The
  stack validates locally but does not apply without operator approval.
- **infra/ansible** -- P13 playbooks for Terraform validation/provision,
  Helm deploy, Helm rollback, and rollout + gateway health smoke. Ansible is
  orchestration only; Terraform/Helm remain the source of truth.
- **.github/workflows/ci.yml** -- P14 CI/CD gate. PRs run commitlint, root
  Maven `verify`, release-tooling readiness checks, and the eight-image Docker
  build matrix with a Trivy scan per image, without publishing. Trusted
  `main`/`v*.*.*` events publish SHA/tagged GHCR images and sign pushed digests
  with keyless cosign.
- **infra/grafana** -- P17 provisioned Grafana datasource + dashboards +
  SLO catalog. Local/full Docker compose expose Grafana on `:3000` with
  local-only `admin` / `cortex` credentials.
- **docs/release + scripts/release** -- P18 release-prep runbook, draft
  release notes, and guarded SBOM/cosign/GitHub Release scripts. Publishing is
  not automatic.

---

## Architecture (one-screen)

```
   Tenants 1..M  (each runs N agents; agents bundle log-agent-lib's Logback appender)
        |                                                  ^
        | push: HTTPS + JWT/APIKey + X-Tenant-Id           | query: REST + GraphQL (users / ops)
        v                                                  |
     +-------------------------------------------------------+
     | log-gateway  (JWT/APIKey, rate-limit, NL->LogQL)[E][M]|
     +-------------------------------------------------------+
              |
              v
   +----------------------+   +----------------+   +----------------------+   +-------------------+
   | log-ingest    [E][M] |-->| Kafka          |-->| log-processor  [E][M]|-->| Postgres (GIN)    |
   | (validate, dedupe,   |   |  cortex.logs   |   | (parse, anomaly,     |   | Loki (hot+warm)   |
   |  enrich,             |   |   .events.v1   |   |  NL->LogQL via AI)   |   | MinIO/Azure Blob  |
   |  outbox tx)          |   |    + .dlq      |   +----------------------+   | Quickwit (fulltxt)|
   +----------------------+   | (CloudEvents   |              |               +-------------------+
            ^                 |    1.0)        |              v                         ^
            |                 +----------------+   +----------------------+   +-------------------+
            |                                      | log-remediation[E][M]|   | log-indexer [E][M]|
            |                                      | (dedupe, policy,    |   | (Quickwit writer, |
            |                                      |  playbook, fallback)|   |  retention, cold) |
            |                                      +----------------------+   +-------------------+
            |                                               |
            |                                               v
            |                       cortex.remediation.outcomes.v1 (audit)
            |                       cortex.anomalies.v1.dlq (malformed)
            |                       GET /api/v1/anomalies (read model)
            |                       Slack / PagerDuty / Jira (fallback only)
            |
            |  compile-time dep -- consumed by every Spring Boot service box above
            |
   +-------------------+
   | log-agent-lib     |
   | shared SDK:       |
   | + contracts (DTOs, IngestBatchRequest, CloudEvent envelope)
   | + Logback appender (X-Request-Id MDC, batched HTTPS push)
   | + PiiMasker (mask-before-hash, deterministic event_id)
   +-------------------+


   Control plane (wired into every Spring Boot service tagged [E] / [M] above)

   register / heartbeat / lb:// discovery               scrape /actuator/prometheus + /actuator/health
            ^   ^   ^   ^   ^                                       |   |   |   |   |
            |   |   |   |   |                                       v   v   v   v   v
            |   |   |   |   |                                       |   |   |   |   |
   +---------------------------+                          +---------------------------------+
   | Eureka :8761  [E]         |                          | log-monitoring  [M]             |
   | service registry; every   |                          | OTel + Micrometer scrape (15s); |
   | Spring Boot service hosts |                          | SLO eval (ingest p99, dedupe    |
   | a Spring Cloud Discovery  |                          | miss, outbox lag); Grafana      |
   | client and registers here |                          | dashboards; health rollups      |
   +---------------------------+                          +---------------------------------+
            ^   ^   ^   ^   ^                                       |   |   |   |   |
            |   |   |   |   |                                       v   v   v   v   v
           gw  ing prc rem idx                                     gw  ing prc rem idx
   (gw=log-gateway, ing=log-ingest, prc=log-processor, rem=log-remediation, idx=log-indexer)
```

### Services

| Module                       | Purpose                                                | Phase |
| ---------------------------- | ------------------------------------------------------ | ----- |
| `log-agent-lib`              | Shared agent SDK + reusable contracts (Logback appender, PII mask, CloudEvent envelope) | P2 |
| `eureka-server`              | Service registry (Spring Cloud Netflix Eureka, standalone Maven project) | P3.0a |
| `log-gateway`                | Spring Cloud Gateway MVC, JWT/API-key auth, Bucket4j rate limit, NL->LogQL | P3 |
| `log-echo-service`           | Thin echo backstop for end-to-end gateway smokes (standalone Maven project) | P3.0b |
| `log-ingest-service`         | Validate, dedupe, mask PII, enrich, persist + transactional outbox -> Kafka | P4.0..P4.4c |
| `log-processor-service`      | Direct `@KafkaListener` consumer with manual offset commit (ADR-0028), CloudEvents 1.0 envelope parse + JSON-schema validation, Spring AI 1.0 anomaly classifier (ADR-0029), DLQ publisher | P5.0..P5.2 SHIPPED |
| `log-remediation-service`    | Fix-first remediation, outcome audit, anomaly read model, guarded human fallback | P19..P21 + P9.3/P9.3a |
| `log-indexer-service`        | Quickwit indexing, retention, cold-tier moves          | P7 |
| `log-monitoring-service`     | Health, metrics, dashboards, SLO tracking              | P8 |
| `infra/local/`               | docker compose smoke stack (postgres, redis, kafka, wiremock) | P3+ |

Presentation-ready architecture diagram and detailed component map:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (P1).
ADRs: [docs/adr/](docs/adr/) (P1).

---

## Quick start (local)

Prereqs: Java 17, Docker Desktop, Git.

```bash
# clone
git clone https://github.com/varadharajaan/cortex.git
cd cortex

# bring up infra (Postgres, Kafka, Redis, Loki, MinIO, Quickwit, Ollama, Grafana)
docker compose -f infra/docker/docker-compose.yml up -d   # added in P10

# build all modules with the project-shipped wrapper
./mvnw -B clean verify

# verify the P11 Helm release contract
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\live-e2e\smoke-p11-helm.ps1

# verify the P12 Azure Terraform scaffold (no apply)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\live-e2e\smoke-p12-terraform.ps1

# verify the P13 Ansible playbook syntax
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\live-e2e\smoke-p13-ansible.ps1

# verify the P17 Grafana / Prometheus operator surface
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\live-e2e\smoke-p17-grafana.ps1

# dry-run the P18 v0.1.0 release-prep lane
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\live-e2e\smoke-p18-release-prep.ps1

# run a service locally (example: gateway)
./mvnw -pl log-gateway spring-boot:run
```

---

## Project layout

```
.
├── log-agent-lib/             # Shared SDK (P2)
├── log-gateway/               # Edge gateway (P3)
├── log-ingest-service/        # Ingest pipeline (P4.0..P4.4c SHIPPED)
├── log-processor-service/     # AI processing (P5.0..P5.2 SHIPPED; P5.3 next)
├── log-remediation-service/   # Fix-first remediation + anomaly reads (P6/P19..P21/P9.3a)
├── log-indexer-service/       # Quickwit owner (P7)
├── log-monitoring-service/    # Observability (P8)
├── eureka-server/             # Standalone discovery (P3.0a)
├── log-echo-service/          # Standalone echo backstop (P3.0b)
├── infra/
│   ├── local/                 # docker-compose.smoke.yml (P3+ smokes)
│   ├── docker/                # Local full compose (P10)
│   ├── helm/                  # Helm charts (P11)
│   ├── terraform/             # Azure infra (P12)
│   └── ansible/               # Runnable playbooks (P13)
├── docs/
│   ├── adr/                   # ADR-0000..ADR-0027 + INDEX.md (P1+)
│   ├── ARCHITECTURE.md
│   └── PHASES.md
├── postman/                   # Postman collections + envs (P15 / Part 25)
├── scripts/                   # PowerShell smoke + boot orchestrators
│   ├── smoke-all.ps1          # Cross-phase regression chain (LD69 union)
│   ├── smoke-p3-*.ps1         # Per-sub-phase gateway smokes
│   ├── smoke-p4-*.ps1         # Per-sub-phase ingest smokes
│   ├── p4-4b/, p4-4c/, p4-5/  # Sub-phase boot + triangle gate scripts
│   ├── p5-0/, p5-1/, p5-2/, p5-2a/  # Processor boot + smoke + Newman scripts
│   └── lib/RunLog.psm1        # Get-CortexLogPath helpers (LD50)
├── .github/
│   ├── workflows/             # CI/CD (P14)
│   ├── CODEOWNERS
│   └── PULL_REQUEST_TEMPLATE.md
├── pom.xml                    # Parent BOM + plugin mgmt
├── checkstyle.xml             # Universal Javadoc enforcement
├── mvnw / mvnw.cmd            # Project-shipped Maven wrapper
├── CHANGELOG.md               # Keep-a-Changelog format
└── README.md
```

---

## Quality gates (enforced by `./mvnw verify`)

- **Checkstyle** at error severity, including mandatory Javadoc on every class
  and every method (public AND private).
- **SpotBugs** + **FindSecBugs** at High threshold.
- **JaCoCo** line + branch coverage >= 80% on service / util packages.
- **OWASP Dependency-Check** fails on CVSS >= 8.
- **Maven Enforcer** locks Java 17 and rejects dependency divergence.
- **ArchUnit** enforces the layered architecture (added per service).

---

## Contributing

See [SECURITY.md](SECURITY.md) for vulnerability reporting and
[CHANGELOG.md](CHANGELOG.md) for release notes.

Commits follow [Conventional Commits](https://www.conventionalcommits.org/).
Enforced by `.commitlintrc.yml` in CI.

---

## License

Apache License 2.0. See [LICENSE](LICENSE).
