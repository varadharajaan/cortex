# CORTEX Grafana Provisioning

P17 keeps Grafana as a provisioned operator surface, not a click-built
dashboard. The files in this directory are mounted read-only by the local
smoke stack and the full Docker stack.

## Layout

| Path | Purpose |
| --- | --- |
| `provisioning/datasources/cortex-datasources.yml` | Prometheus datasource with UID `cortex-prometheus`; URL comes from `PROMETHEUS_URL`. |
| `provisioning/dashboards/cortex-dashboards.yml` | Dashboard provider that loads every JSON file under `/var/lib/grafana/dashboards`. |
| `dashboards/cortex-overview.json` | System overview across SLOs, probes, ingest, processor, remediation, and indexer counters. |
| `dashboards/cortex-slo.json` | SLO-focused budget and burn-rate view for the `log-monitoring-service` gauges. |
| `slo/cortex-availability-slos.yml` | Operator SLO catalog matching the runtime defaults in `log-monitoring-service`. |

## Local Smoke

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts\live-e2e\smoke-p17-grafana.ps1
```

The smoke starts only `prometheus` and `grafana` from
`infra/local/docker-compose.smoke.yml`, checks the Prometheus rule file with
`promtool`, waits for Grafana `/api/health`, and verifies the datasource and
both dashboards through Grafana's HTTP API.

Default local credentials are intentionally boring and local-only:

```text
URL:      http://localhost:3000
Username: admin
Password: cortex
```

Production deployments must override credentials through Kubernetes secrets or
Grafana's own identity provider integration; do not commit real credentials.

