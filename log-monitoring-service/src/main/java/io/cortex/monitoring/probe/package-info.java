/**
 * Service health probe SPI for the CORTEX log-monitoring-service
 * (P8 epic).
 *
 * <p>Contains the
 * {@link io.cortex.monitoring.probe.ServiceHealthProbe} SPI, the
 * immutable {@link io.cortex.monitoring.probe.HealthSnapshot}
 * verdict record, the {@link io.cortex.monitoring.probe.ProbeRequest}
 * input record, and the
 * {@link io.cortex.monitoring.probe.NoopServiceHealthProbe}
 * default implementation that ships with the P8.0 scaffold.</p>
 *
 * <p>Per ADR-0044: this is the SPI seam P8.1+ probe adapters bind
 * to. The Eureka-discovery REST adapter that scrapes
 * {@code /actuator/health} on every registered cortex service
 * lands in P8.1 behind
 * {@code cortex.monitoring.probe.backend=eureka-actuator}.</p>
 */
package io.cortex.monitoring.probe;
