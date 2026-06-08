/**
 * Cross-phase Failsafe integration tests for the
 * {@code log-monitoring-service}.
 *
 * <p>This package houses the closer-phase regression suite that
 * exercises the public SPIs shipped across the P8.0 / P8.1 / P8.2
 * ring through the full Spring autowiring path. Two cross-phase ITs
 * share the package, each covering a distinct production-shaped
 * operator deployment shape per LD104 closer-separation
 * discipline:</p>
 *
 * <ul>
 *   <li>{@link io.cortex.monitoring.closer.MonitoringProbeAndSloPipelineIT}
 *       (P8.2a / ADR-0047) -- both binder gates flipped
 *       ({@code cortex.monitoring.probe.backend=eureka-actuator} +
 *       {@code cortex.monitoring.slo.enabled=true} +
 *       {@code cortex.monitoring.slo.backend=micrometer-derivation}),
 *       {@link org.springframework.boot.test.context.SpringBootTest.WebEnvironment#MOCK}
 *       env, exercises the FULL P8.0..P8.2 ring including SLO
 *       derivation + scheduled evaluator. Carries the LD137
 *       numeric-millis IT-only workaround.</li>
 *   <li>{@link io.cortex.monitoring.closer.MonitoringProbeAndHealthIndicatorIT}
 *       (P8.1a / ADR-0048) -- probe-only binder gate flipped
 *       ({@code cortex.monitoring.probe.backend=eureka-actuator}
 *       only; SLO defaulted off), {@link
 *       org.springframework.boot.test.context.SpringBootTest.WebEnvironment#RANDOM_PORT}
 *       env, exercises the probe surface + the
 *       {@code /actuator/health/monitoring} HTTP indicator
 *       end-to-end via {@link
 *       org.springframework.boot.test.web.client.TestRestTemplate}.
 *       Does NOT carry the LD137 workaround (the SLO scheduler is
 *       gated off, so the broken {@code fixedRateString} prod
 *       annotation is never exercised). Closes the
 *       production-shaped-config regression gap that the P8.2a IT
 *       leaves open by virtue of having both binder gates flipped
 *       + WebEnvironment.MOCK.</li>
 * </ul>
 *
 * <p>Both ITs use a singleton in-process {@code WireMockServer}
 * that stands in for the downstream {@code /actuator/health}
 * endpoint a real Eureka-discovered cortex service would expose,
 * plus a {@code @TestConfiguration @Bean @Primary DiscoveryClient}
 * stub that re-uses the same WireMock port for the target service
 * id(s).</p>
 *
 * <p>Mirrors {@code io.cortex.indexer.closer} (P7.1a / ADR-0043)
 * and {@code io.cortex.remediation.closer} (P6.1a) per LD104 +
 * LD73 -- the cross-phase suite is the Leg D regression gate that
 * each per-phase WireMock IT (which constructs the adapter directly
 * via {@code new ...}) structurally cannot cover.</p>
 */
package io.cortex.monitoring.closer;
