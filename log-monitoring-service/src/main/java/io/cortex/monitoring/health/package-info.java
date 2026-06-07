/**
 * Spring Boot health indicator family for the CORTEX
 * log-monitoring-service (P8 epic).
 *
 * <p>Contains the
 * {@link io.cortex.monitoring.health.MonitoringHealthIndicator}
 * component. The P8.0 scaffold returns {@code UP} for the noop
 * backend; P8.1+ will surface aggregated downstream health (a
 * single instance reporting {@code DOWN} via the SPI will flip
 * this indicator to {@code DOWN} and lift the monitoring pod out
 * of K8s readiness).</p>
 */
package io.cortex.monitoring.health;
