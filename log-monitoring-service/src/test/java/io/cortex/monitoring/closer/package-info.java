/**
 * Cross-phase Failsafe integration tests for the
 * {@code log-monitoring-service}.
 *
 * <p>This package houses the closer-phase regression suite that
 * exercises the public SPIs shipped across the P8.0 / P8.1 / P8.2
 * / P8.2b ring through the full Spring autowiring path. Three
 * cross-phase ITs share the package, each covering a distinct
 * production-shaped operator deployment shape per LD104 closer-
 * separation discipline:</p>
 *
 * <ul>
 *   <li>{@link io.cortex.monitoring.closer.MonitoringProbeAndSloPipelineIT}
 *       (P8.2a / ADR-0047) -- both binder gates flipped
 *       ({@code cortex.monitoring.probe.backend=eureka-actuator} +
 *       {@code cortex.monitoring.slo.enabled=true} +
 *       {@code cortex.monitoring.slo.backend=micrometer-derivation}),
 *       {@link org.springframework.boot.test.context.SpringBootTest.WebEnvironment#MOCK}
 *       env, exercises the FULL P8.0..P8.2 ring including SLO
 *       derivation + scheduled evaluator. No longer carries the
 *       historical LD137 numeric-millis workaround -- since the
 *       issue #120 prod fix shipped (ADR-0046 Amendment
 *       2026-06-08), the operator-friendly
 *       {@code cortex.monitoring.slo.evaluation-interval=1h}
 *       form is safe under {@code slo.enabled=true}.</li>
 *   <li>{@link io.cortex.monitoring.closer.MonitoringProbeAndHealthIndicatorIT}
 *       (P8.1a / ADR-0048) -- probe-only binder gate flipped
 *       ({@code cortex.monitoring.probe.backend=eureka-actuator}
 *       only; SLO defaulted off), {@link
 *       org.springframework.boot.test.context.SpringBootTest.WebEnvironment#RANDOM_PORT}
 *       env, exercises the probe surface + the
 *       {@code /actuator/health/monitoring} HTTP indicator
 *       end-to-end via {@link
 *       org.springframework.boot.test.web.client.TestRestTemplate}.
 *       Was never sensitive to LD137 because it leaves SLO at
 *       the noop default. Closes the production-shaped-config
 *       regression gap that the P8.2a IT leaves open by virtue
 *       of having both binder gates flipped +
 *       WebEnvironment.MOCK.</li>
 *   <li>{@link io.cortex.monitoring.closer.MonitoringMultiTargetProbeAndDefaultSlosIT}
 *       (P8.2b / ADR-0046 Amendment 3 2026-06-08) -- probe pump
 *       binder gate flipped
 *       ({@code cortex.monitoring.probe.backend=eureka-actuator}
 *       + {@code cortex.monitoring.probe.enabled=true}) plus the
 *       new six default targets +
 *       {@code cortex.monitoring.slo.enabled=true} +
 *       {@code cortex.monitoring.slo.backend=noop} +
 *       all six default availability SLO definitions wired
 *       inline; {@link
 *       org.springframework.boot.test.context.SpringBootTest.WebEnvironment#MOCK}
 *       env. Stub {@link
 *       org.springframework.cloud.client.discovery.DiscoveryClient}
 *       routes EVERY cortex service id to the shared WireMock so
 *       a single {@code ScheduledProbeEvaluator.evaluateOnce()}
 *       fans out to six probe calls + six counter series; a
 *       single {@code SloEvaluator.evaluateOnce()} iterates all
 *       six default definitions under the noop binder without
 *       throwing. Headline proof that the multi-target probe
 *       pump + default SLO defs work end-to-end through Spring
 *       binding + {@code @ConditionalOnProperty} gating -- the
 *       per-phase tests and the P8.1a / P8.2a closers cover at
 *       most one service id per assertion.</li>
 * </ul>
 *
 * <p>All three ITs use a singleton in-process
 * {@code WireMockServer} that stands in for the downstream
 * {@code /actuator/health} endpoint a real Eureka-discovered
 * cortex service would expose, plus a
 * {@code @TestConfiguration @Bean @Primary DiscoveryClient}
 * stub that re-uses the same WireMock port for the target
 * service id(s).</p>
 *
 * <p>Mirrors {@code io.cortex.indexer.closer} (P7.1a / ADR-0043)
 * and {@code io.cortex.remediation.closer} (P6.1a) per LD104 +
 * LD73 -- the cross-phase suite is the Leg D regression gate that
 * each per-phase WireMock IT (which constructs the adapter directly
 * via {@code new ...}) structurally cannot cover.</p>
 */
package io.cortex.monitoring.closer;
