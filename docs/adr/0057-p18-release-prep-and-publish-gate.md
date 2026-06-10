# ADR-0057: P18 v0.1.0 release prep and publish gate

- Status: accepted
- Date: 2026-06-10
- Deciders: @varadharajaan
- Tags: release, sbom, cosign, github-release, runbook, P18

## Context

The parent Maven build already contains a release profile, CycloneDX SBOM
generation, OWASP Dependency-Check, source JARs, and Javadocs. The project also
has Docker images and deployment scaffolding, but P14 still owns full CI/CD.
P18 therefore needs a release lane that is executable and documented without
pretending that CI/CD, registry credentials, or an actual GitHub Release have
already been performed.

## Decision

Split P18 into release-prep assets plus an explicit publish gate.

1. Release-prep scripts live under `scripts/release`:
   `prepare-v0-1-0.ps1`, `generate-sbom.ps1`, `sign-images.ps1`, and
   `create-github-release.ps1`.
2. Every publishing script defaults to dry-run posture or requires an explicit
   confirmation token (`SIGN` for cosign signing and `RELEASE` for GitHub
   Release creation).
3. The release runbook and draft notes live under `docs/release`. They are the
   operator handoff for the actual v0.1.0 cut.
4. The local P18 smoke validates the release assets and dry-runs the scripts,
   but it does not tag, sign, publish, or call GitHub.
5. P18 is not marked fully complete until the operator approves and performs
   the real tag, SBOM build, image signing, and GitHub Release publication.

## Consequences

- The repository now has a concrete release path without stealing P14's full
  CI/CD scope.
- Accidental release publication is structurally guarded.
- The phase tracker can honestly show P18 as release-prep complete but publish
  pending until `v0.1.0` is actually created.

## Verification

`scripts/live-e2e/smoke-p18-release-prep.ps1` checks the release docs, confirms
the parent POM release/SBOM hooks, and runs every release script in dry-run mode.

## Alternatives Considered

- Create the v0.1.0 tag immediately. Rejected because the user has not given
  the final release approval and the workspace currently contains unrelated
  in-flight changes.
- Add a full release workflow now. Rejected because P14 owns the broader CI/CD
  pipeline and registry/OIDC hardening.
- Leave release as prose only. Rejected because P18 needs executable operator
  tooling, not just a checklist.

