# ADR-0058: P14 CI/CD pipeline

- **Status**: Accepted
- **Date**: 2026-06-10
- **Deciders**: @varadharajaan
- **Tags**: ci, cd, github-actions, testcontainers, docker, ghcr, cosign, P14

## Context

The repository still had the original P0 CI stub: Conventional Commit linting
on PRs and parent POM validation. That was useful while the platform was
forming, but it no longer matched the shipped system. The codebase now has
Testcontainers-backed integration tests, eight P10 runtime Dockerfiles, P11-P13
deployment scaffolding, P17 dashboards, and P18 release-prep tooling.

P14 must therefore promote CI from "syntax safety net" to the normal delivery
gate without turning every pull request into a publish or release event.

## Decision

**D1 -- Root `mvn verify` is the CI truth.** The `maven-verify` job runs
`./mvnw -B -ntp verify` from the repository root on GitHub-hosted Ubuntu with
Docker available for Testcontainers. This is the same command developers run
locally, so Checkstyle, SpotBugs, JaCoCo, OWASP Dependency-Check, CycloneDX, and
Failsafe all stay under Maven's single source of truth.

**D2 -- Verification reports are retained as artifacts.** Surefire, Failsafe,
JaCoCo, Checkstyle, SpotBugs, Dependency-Check, and CycloneDX outputs are
uploaded for 14 days even when the job fails. CI failures should be diagnosable
without rerunning the whole reactor.

**D3 -- Image builds use the existing P10 Dockerfiles.** The `image-build`
matrix builds the eight runnable images from `infra/docker/*.Dockerfile` after
`maven-verify` succeeds. The Dockerfiles still skip Maven quality gates inside
the image build; P14 owns those gates before image construction starts.

**D4 -- Pull requests build but do not publish.** PRs execute the same image
build matrix but `push=false`. This proves that every Dockerfile still builds
from the proposed code without granting an untrusted PR a package-publish or
release path.

**D5 -- Trusted push/tag events publish to GHCR.** Pushes to `main` and
semantic release tags publish images under
`ghcr.io/<owner>/cortex/<image>`. Every trusted event receives a stable
`sha-<full-git-sha>` tag; semantic tags also receive the Git tag name such as
`v0.1.0`.

**D6 -- Cosign is keyless and attached to pushed images only.** On trusted
push/tag events the image job installs cosign and signs the pushed digest with
GitHub OIDC (`id-token: write`). Release tag references are also signed. The
`release-tooling` job verifies the cosign toolchain installs plus the tracked
release artifacts (`docs/release/*`, ADR-0057) and the pom release hooks on
every PR; it does not sign or push anything. The operator release scripts
(`scripts/release/*`) and the `smoke-p18-release-prep.ps1` dry-run remain
local-only per the `/scripts/` gitignore policy and run in the local release
triangle, not CI.

**D7 -- P18 publish approval remains separate.** P14 makes the CI/CD machinery
real, but it does not mark P18 publish complete. Creating the `v0.1.0` tag,
GitHub Release, and final operator sign-off remain behind the P18 runbook and
explicit approval gate.

**D8 -- Trivy scans every built image (blocking, with a documented residual
allowlist).** The `image-build` job first builds each image into the local
Docker daemon (`load: true`, no push), then runs `aquasecurity/trivy-action`
against it and fails the job on a fixable `HIGH`/`CRITICAL` OS or library CVE
(`ignore-unfixed: true`, `severity: HIGH,CRITICAL`, `exit-code: 1`). The scan
runs on pull requests too, so a vulnerable image fails review before the
publish step is ever reached and GHCR only ever receives a scanned image.

The first scan of the eight images surfaced a pre-existing dependency +
base-image backlog that P14 did not introduce (issue #156). It was burned down
*inside the locked 3.3.x stack* in this same change: Spring Boot 3.3.6 ->
3.3.13, Spring AI 1.0.0 -> 1.0.8, plus `netty.version` 4.1.135.Final,
`tomcat.version` 10.1.55, `postgresql.version` 42.7.11, `jersey.version`
3.1.10, `xstream` 1.4.21, `bcprov-jdk18on` 1.84, `lz4-java` 1.8.1 property /
dependencyManagement overrides, and an `apt-get upgrade` in every runtime
Dockerfile stage to patch the OS layer. The standalone `infra/eureka` pom (its
own `spring-boot-starter-parent` outside the reactor) is bumped the same way
with explicit `dependencyManagement` pins for its transitive `xstream` /
`bcprov` / `jersey-client`. The residual CVEs whose only
fix requires a Spring Boot 3.4+/Framework 6.2+/Security 6.5+/Spring Kafka 3.3+
migration -- a locked-stack architectural decision that needs explicit approval
(it also forces Spring Cloud 2023.0.4 -> 2024.0.x) -- are listed in
`.trivyignore` with a per-CVE justification and the #156 cross-reference, so the
gate has teeth for every NEW CVE while the framework-migration decision stays
visible and tracked. The four allowlisted residuals are `CVE-2025-41249`
(spring-core), `CVE-2026-22732` (spring-security-web), `CVE-2026-40973`
(spring-boot), and `CVE-2026-35554` (kafka-clients -- the embedded
`spring-kafka-test` ZK broker hard-requires kafka-clients 3.7.x, so any bump
breaks every `@EmbeddedKafka` test). OWASP Dependency-Check (CVSS >= 8, rule
A15.3) remains the blocking dependency gate inside `maven-verify`. The
trivy-action is SHA-pinned per the Part 13 / B17 supply-chain rules.

## Consequences

- CI now catches compile, quality, coverage, integration, SBOM, Dockerfile,
  image-build, and image-vulnerability regressions before merge.
- The P10 Dockerfiles are exercised continuously, so deployment drift becomes
  visible at PR time instead of release time.
- Trivy scans every image and blocks a fixable HIGH/CRITICAL CVE at PR time
  (with a documented `.trivyignore` allowlist for framework-locked residuals),
  so image-vulnerability regressions cannot reach GHCR.
- GHCR and cosign are used only on trusted events, preserving a clean boundary
  between review validation and artifact publication.
- The full workflow is heavier than the old P0 stub. The cost is intentional:
  this repository is now validating a multi-service platform, not only a parent
  POM.

## Verification

Local verification for this ADR checks the workflow syntax, the release-tooling
readiness path (cosign install plus tracked `docs/release/*`, ADR-0057, and the
pom release hooks), root Maven verification, and the Docker image build plus
Trivy scan path. Actual GHCR push and keyless signing are only observable after
the workflow runs on a trusted GitHub push or tag because those steps require
GitHub token and OIDC context.

## Alternatives Considered

- **Keep CI as `mvn validate`.** Rejected. That repeats the P0 stub and leaves
  Testcontainers, Failsafe, coverage, Dockerfiles, SBOM generation, and image
  build failures invisible until a human runs them locally.
- **Push images from pull requests.** Rejected. PRs may contain untrusted code
  and should not publish packages or signatures.
- **Use P18 scripts as the CI publish implementation.** Rejected. The P18
  scripts are operator tools with explicit confirmation gates. CI needs an
  unattended, event-scoped path for SHA images while the release scripts remain
  the human release runbook.
- **Sign mutable branch tags such as `main`.** Rejected for now. P14 signs the
  immutable pushed digest and optional semantic tag; branch-moving tags can be
  added later if the deployment strategy needs them.
