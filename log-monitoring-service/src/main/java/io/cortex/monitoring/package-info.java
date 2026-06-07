/**
 * CORTEX log-monitoring-service root package (P8 epic).
 *
 * <p>Aggregates cross-service health (via the
 * {@link io.cortex.monitoring.probe.ServiceHealthProbe} SPI in
 * {@code io.cortex.monitoring.probe}) and computes SLO budgets
 * (P8.2). The P8.0 scaffold ships the SPI + a noop default + a
 * Micrometer counter + a Spring Boot health indicator; real
 * adapters land in later P8.x phases gated by
 * {@code cortex.monitoring.probe.backend}.</p>
 *
 * <p>Per ADR-0044: this module does NOT own Grafana dashboards or
 * Prometheus alert rules. That layer lives in P17. P8 ships only
 * the metric surface ({@code cortex.monitoring.probe_total} +
 * later {@code cortex_monitoring_slo_budget_remaining} +
 * {@code cortex_monitoring_slo_burn_rate}) + the SLO computation
 * engine.</p>
 */
package io.cortex.monitoring;
