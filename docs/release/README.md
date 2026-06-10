# CORTEX Release Docs

This directory contains the v0.1.0 release-prep artifacts for P18.

| File | Purpose |
| --- | --- |
| `v0.1.0-runbook.md` | Operator procedure for cutting the release. |
| `v0.1.0-release-notes.md` | Draft GitHub Release notes. |

The release scripts live under `scripts/release`. The local verification gate
is:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts\live-e2e\smoke-p18-release-prep.ps1
```

The smoke is intentionally dry-run only. It does not create tags, sign images,
publish artifacts, or create a GitHub Release.

The P14 CI/CD workflow now builds and signs pushed images for trusted
`main`/`v*.*.*` events; these scripts remain the manual operator release lane.
