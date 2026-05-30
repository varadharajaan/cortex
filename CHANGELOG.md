# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- P0: Repository bootstrap.
  - Parent Maven POM (Java 17, Spring Boot 3.3.5, Spring Cloud 2023.0.4,
    Spring AI 1.0.0).
  - Maven wrapper (script-only) pinned to Maven 3.9.9.
  - Universal Javadoc enforcement via Checkstyle (Rule 0.1.6).
  - SpotBugs + FindSecBugs at High threshold.
  - JaCoCo with 80% line + branch gates.
  - OWASP Dependency-Check (CVSS >= 8 fails build).
  - CycloneDX SBOM generation.
  - Maven Enforcer: Java 17, dependency convergence, ban duplicate versions.
  - Renovate config for weekly dependency updates.
  - Conventional Commits enforcement via commitlint.
  - LF line endings via `.gitattributes` and `.editorconfig`.
  - `.github/CODEOWNERS` and PR template.
  - Apache License 2.0.

- P1: Documentation and Architecture Decision Records.
  - `docs/ARCHITECTURE.md` - canonical architecture reference with module
    map, three-tier search routing, API surfaces, and tenant isolation.
  - `docs/PHASES.md` - public phase plan mirroring GitHub milestones.
  - `docs/adr/0000-template.md` - MADR template for future ADRs.
  - ADR-0001: Java 17 LTS runtime (no virtual threads).
  - ADR-0002: Single repo with seven service modules.
  - ADR-0003: Three-tier search (Postgres GIN + Loki + Quickwit) Day 1.
  - ADR-0004: REST + GraphQL parity on four query operations; no
    GraphQL mutations.
  - ADR-0005: RabbitMQ locally, Azure Service Bus in production via
    Spring Cloud Stream binders.
  - ADR-0006: AI provider abstraction (Ollama local, Azure OpenAI prod).
  - ADR-0007: Self-healing via runnable Ansible playbooks with two-step
    dry-run gate and per-tenant kill-switch.
  - ADR-0008: Resilience4j on every egress call (circuit breaker,
    retry, time limiter, rate limiter, fallback).
  - ADR-0009: Tenant isolation (`tenant_id` column + B-tree composite
    index + propagation through MDC, OTel baggage, bus headers).
  - ADR-0010: Storage tiering (hot Loki -> warm Blob -> archive Blob)
    with explicit `X-Allow-Cold-Read` opt-in for archive reads.
  - ADR-0011: Observability stack (OpenTelemetry traces + Micrometer
    metrics + loki4j self-logs).
  - ADR-0012: Build and quality gates (universal Javadoc + JaCoCo 80%
    + OWASP DC + SBOM, all enforced by `./mvnw verify`).
