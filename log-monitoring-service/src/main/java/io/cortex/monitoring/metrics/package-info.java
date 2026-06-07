/**
 * Micrometer counter / gauge family published by the CORTEX
 * log-monitoring-service (P8 epic).
 *
 * <p>Contains the {@link io.cortex.monitoring.metrics.MonitoringMetrics}
 * bootstrap component. The P8.0 scaffold publishes one counter family
 * ({@code cortex.monitoring.probe_total}); P8.2 will add the SLO
 * budget gauges ({@code cortex_monitoring_slo_budget_remaining} +
 * {@code cortex_monitoring_slo_burn_rate}).</p>
 *
 * <p>Tag-key allowlist enforced per Part 17 rule: only
 * {@code backend}, {@code outcome}, {@code service_id} are emitted
 * on the probe counter. Free-form values are bounded by the
 * {@link io.cortex.monitoring.probe.HealthSnapshot} constants.</p>
 */
package io.cortex.monitoring.metrics;
