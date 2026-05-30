# Security Policy

## Supported Versions

CORTEX is pre-1.0. Only the `main` branch receives security fixes.

| Version | Status     |
| ------- | ---------- |
| `main`  | Supported  |
| any tag | Not yet    |

## Reporting a Vulnerability

Please **DO NOT** open a public GitHub issue for security reports.

Email: `varathu09@gmail.com` with subject prefix `[CORTEX SECURITY]`.

Include:
- affected service / module / file path,
- minimal reproduction (steps, payload, environment),
- impact assessment (what an attacker can achieve),
- suggested remediation if you have one.

You will receive an acknowledgement within 72 hours. Expect a fix or
mitigation timeline within 7 days for high-severity issues (CVSS >= 8.0).

## Disclosure

We follow coordinated disclosure. Once a fix is released, the CVE (if assigned)
and details will appear in `CHANGELOG.md` and on the GitHub Security Advisory
for this repository.

## Automated Scanning

- **OWASP Dependency-Check** (CVSS >= 8.0 fails build) on every PR.
- **Renovate** for dependency updates.
- **SpotBugs + FindSecBugs** at High threshold.
- **CycloneDX SBOM** attached to every release.
- **cosign** signing for release artifacts.

## Secrets Handling

- No credentials in source. All secrets are sourced from environment
  variables and validated at startup via `@ConfigurationProperties`.
- Production secrets are stored in Azure Key Vault and surfaced via the
  External Secrets Operator in Kubernetes.
- Local development uses `.env` (gitignored) and never `application.yml`.
