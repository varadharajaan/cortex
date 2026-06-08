/**
 * Cross-phase Failsafe integration tests for the
 * {@code log-monitoring-service} (P8.2a closer / ADR-0047).
 *
 * <p>This package houses the closer-phase regression suite that
 * exercises every public SPI shipped across the P8.0 / P8.1 / P8.2
 * ring through the full Spring autowiring path with BOTH binder
 * gates flipped on (`cortex.monitoring.probe.backend=eureka-actuator`
 * + `cortex.monitoring.slo.enabled=true` +
 * `cortex.monitoring.slo.backend=micrometer-derivation`), against a
 * singleton in-process WireMock that stands in for the downstream
 * `/actuator/health` endpoint a real Eureka-discovered cortex
 * service would expose.</p>
 *
 * <p>Mirrors {@code io.cortex.indexer.closer} (P7.1a / ADR-0043)
 * and {@code io.cortex.remediation.closer} (P6.1a) per LD104 +
 * LD73 -- the cross-phase suite is the Leg D regression gate that
 * each per-phase WireMock IT (which constructs the adapter directly
 * via {@code new ...}) structurally cannot cover.</p>
 */
package io.cortex.monitoring.closer;
