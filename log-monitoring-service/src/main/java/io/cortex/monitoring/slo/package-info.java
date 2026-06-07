/**
 * SLO budget engine (P8.2 / ADR-0046).
 *
 * <p>Converts the {@code cortex.monitoring.probe_total} counter
 * surface (emitted by every {@link io.cortex.monitoring.probe.ServiceHealthProbe}
 * adapter since P8.0) into a pair of operator-friendly gauges per
 * {@code (service_id, slo_name)} tuple: the {@code budget_remaining}
 * fraction and the {@code burn_rate} multiplier.</p>
 *
 * <p>The {@link io.cortex.monitoring.slo.SloBudgetEngine} SPI is
 * pluggable: the {@code noop} default returns
 * {@link io.cortex.monitoring.slo.SloSnapshot#noop(SloDefinition)}
 * for every call; the {@code micrometer-derivation} backend reads
 * counter snapshots from the {@code MeterRegistry} and computes
 * {@code (successes / total)} ratios. Future backends can layer
 * Prometheus query / native histogram / OpenTelemetry inputs
 * behind the same SPI without touching the
 * {@link io.cortex.monitoring.slo.SloEvaluator} scheduler.</p>
 */
package io.cortex.monitoring.slo;
