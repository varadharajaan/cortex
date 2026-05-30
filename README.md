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
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue)](LICENSE)

Currently in active scaffolding. See [docs/PHASES.md](docs/PHASES.md) (added in
P1) for the 19-phase roadmap (P0 - P18).

---

## Architecture (one-screen)

```
   Edge          Ingest                 Process                  Storage / Search
+--------+   +-------------+     +-------------------+      +-------------------+
|        |-->| log-gateway |---->| log-ingest        |----> | Postgres (GIN)    |
| agents |   | (JWT/APIKey)|     | (validate, dedupe,|      | Loki (hot+warm)   |
|        |   +-------------+     |  enrich, queue)   |      | MinIO/Azure Blob  |
+--------+         |             +-------------------+      | Quickwit (fulltxt)|
                   v                       |                +-------------------+
                +--------+                 v                          ^
                |  REST  |       +-------------------+                |
                | GraphQL|       | log-processor     |----------------+
                +--------+       | (parse, anomaly,  |
                                 |  NL->LogQL via AI)|
                                 +-------------------+
                                          |
                                          v
                                 +-------------------+
                                 | log-remediation   |---> Slack/PagerDuty/Jira
                                 |  + log-monitoring |
                                 |  + log-indexer    |
                                 +-------------------+
```

### Services

| Module                       | Purpose                                                |
| ---------------------------- | ------------------------------------------------------ |
| `log-agent-lib`              | Shared agent SDK + reusable contracts                  |
| `log-gateway`                | Spring Cloud Gateway, auth, rate limit, NL->LogQL      |
| `log-ingest-service`         | Validate, dedupe, enrich, write to queue + Postgres    |
| `log-processor-service`      | Parse, classify, AI anomaly detection                  |
| `log-remediation-service`    | Auto-healing playbooks, alert routing                  |
| `log-indexer-service`        | Quickwit indexing, retention, cold-tier moves          |
| `log-monitoring-service`     | Health, metrics, dashboards, SLO tracking              |

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
├── log-ingest-service/        # Ingest pipeline (P4)
├── log-processor-service/     # AI processing (P5)
├── log-remediation-service/   # Self-healing (P6)
├── log-indexer-service/       # Quickwit owner (P7)
├── log-monitoring-service/    # Observability (P8)
├── infra/
│   ├── docker/                # Local docker-compose (P10)
│   ├── helm/                  # Helm charts (P11)
│   ├── terraform/             # Azure infra (P12)
│   └── ansible/               # Runnable playbooks (P13)
├── docs/
│   ├── adr/                   # Architecture Decision Records (P1)
│   ├── ARCHITECTURE.md
│   └── PHASES.md
├── .github/
│   ├── workflows/             # CI/CD (P14)
│   ├── CODEOWNERS
│   └── PULL_REQUEST_TEMPLATE.md
├── pom.xml                    # Parent BOM + plugin mgmt
├── checkstyle.xml             # Universal Javadoc enforcement
├── mvnw / mvnw.cmd            # Project-shipped Maven wrapper
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
