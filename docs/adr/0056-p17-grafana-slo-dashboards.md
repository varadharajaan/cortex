# ADR-0056: P17 Grafana SLO dashboards

- Status: accepted
- Date: 2026-06-10
- Deciders: @varadharajaan
- Tags: grafana, prometheus, slo, dashboards, alerts, P17

## Context

P8 shipped the `log-monitoring-service` probe counter, SLO gauges, default
availability SLO definitions, and Prometheus multi-window burn-rate alert
rules. P10 added a full local Docker stack and P11/P12/P13 added the
deployment layers. The remaining P17 gap is the operator-facing Grafana layer:
dashboards must be versioned, reproducible, and bootable from a fresh clone.

## Decision

Adopt `infra/grafana` as the canonical Grafana provisioning package.

1. Grafana provisioning is file-based and read-only. The datasource provider
   creates a single Prometheus datasource with UID `cortex-prometheus`; the URL
   is supplied by the runtime environment through `PROMETHEUS_URL`.
2. Dashboards are committed as JSON under `infra/grafana/dashboards`:
   `CORTEX Overview` for the whole platform and `CORTEX SLO` for
   error-budget and burn-rate operations.
3. Prometheus alert rules remain the alerting source of truth. P17 reuses
   `infra/local/alerts/slo-burn-rate.rules.yml` instead of duplicating the
   same rules in Grafana-managed alerting.
4. The SLO catalog lives under `infra/grafana/slo/cortex-availability-slos.yml`
   and mirrors the runtime defaults already owned by
   `log-monitoring-service/src/main/resources/application.yml`.
5. Local and full Docker compose stacks mount the same provisioning package.
   Local smoke publishes Grafana on `localhost:3000` with local-only
   credentials (`admin` / `cortex`).

## Consequences

- Operators get a reproducible Grafana surface with no manual dashboard import.
- The datasource UID is stable, so dashboard JSON stays portable across local,
  Docker, Helm, and future managed Grafana deployments.
- Prometheus remains responsible for rule evaluation and Alertmanager routing;
  Grafana is the read-side visualization layer in P17.
- P17 is infrastructure/docs/scripts only; no Spring service code is changed.

## Verification

`scripts/live-e2e/smoke-p17-grafana.ps1` validates dashboard JSON, validates
the SLO catalog shape, runs `promtool check rules` on the alert rule file,
boots Prometheus + Grafana through the local smoke compose stack, and verifies
the datasource plus both dashboards through Grafana's HTTP API.

## Alternatives Considered

- Grafana-managed alerting as the source of truth. Rejected for P17 because
  Prometheus already owns the alert rules and P8.2a smoke validates them
  directly.
- Manually imported dashboards. Rejected because manual import is not
  repeatable in CI or local smoke.
- One dashboard per service. Deferred until the P8.3+ backend work exposes
  richer per-service SLIs beyond availability.

