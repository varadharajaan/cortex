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
