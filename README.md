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

**Phase progress**: P0 .. P4 SHIPPED. Latest P4 sub-phase: P4.4c DLQ +
Service Bus binder profile + outbox counters (PR #63, `9891f3c`, 2026-06-02).
P4 epic closed by P4.5 (PR #65, `21b25b9`, 2026-06-03). **Next: P5
(`log-processor-service`).**

See [docs/PHASES.md](docs/PHASES.md) for the 19-phase roadmap (P0 - P18) and
[docs/adr/INDEX.md](docs/adr/INDEX.md) for the ADR directory.

### What's working today on `main`

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
- **eureka-server** :8761 -- service registry; every Spring Boot service
  registers and discovers via `lb://`.
- **log-echo-service** :8093 -- thin echo backstop used by gateway smokes
  to assert end-to-end routing.
- **infra (docker compose smoke)** -- postgres:16-alpine, redis:7-alpine,
  `confluentinc/cp-kafka` (P4.4b+), `wiremock` :8094.

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
            |                                      | (playbooks, alert    |   | (Quickwit writer, |
            |                                      |  routing)            |   |  retention, cold) |
            |                                      +----------------------+   +-------------------+
            |                                               |
            |                                               v
            |                                     Slack / PagerDuty / Jira
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
| `log-processor-service`      | Parse, classify, AI anomaly detection                  | P5 (next) |
| `log-remediation-service`    | Auto-healing playbooks, alert routing                  | P6 |
| `log-indexer-service`        | Quickwit indexing, retention, cold-tier moves          | P7 |
| `log-monitoring-service`     | Health, metrics, dashboards, SLO tracking              | P8 |
| `infra/local/`               | docker compose smoke stack (postgres, redis, kafka, wiremock) | P3+ |

Detailed component map: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (P1).
ADRs: [docs/adr/](docs/adr/) (P1).

---

## Quick start (local)

Prereqs: Java 17, Docker Desktop, Git.

```bash
# clone
git clone https://github.com/varadharajaan/cortex.git
cd cortex

# bring up infra (Postgres, Loki, RabbitMQ, MinIO, Quickwit, Ollama, Grafana)
docker compose -f infra/docker/docker-compose.yml up -d   # added in P10

# build all modules with the project-shipped wrapper
./mvnw -B clean verify

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
├── log-processor-service/     # AI processing (P5, next)
├── log-remediation-service/   # Self-healing (P6)
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
