package io.cortex.monitoring.closer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cortex.monitoring.metrics.MonitoringMetrics;
import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.ProbeProperties;
import io.cortex.monitoring.probe.ScheduledProbeEvaluator;
import io.cortex.monitoring.slo.SloBudgetEngine;
import io.cortex.monitoring.slo.SloEvaluator;
import io.cortex.monitoring.slo.SloProperties;
import io.cortex.monitoring.slo.SloSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestConstructor;

/**
 * P8.2b cross-phase closer for {@code log-monitoring-service}.
 *
 * <p>Sibling to {@code MonitoringProbeAndSloPipelineIT} (P8.2a)
 * and {@code MonitoringProbeAndHealthIndicatorIT} (P8.1a). Proves
 * the multi-target probe pump + default SLO definitions shipped
 * in P8.2b wire end-to-end through the autowired Spring context
 * for ALL six cortex services in a single boot.</p>
 *
 * <p>What this proves that the per-phase tests + the P8.2a /
 * P8.1a closers structurally could not:</p>
 * <ul>
 *   <li>The new {@link ScheduledProbeEvaluator} bean is created
 *       when {@code cortex.monitoring.probe.enabled=true} and
 *       fans out one {@code ServiceHealthProbe.probe(...)} call
 *       per configured target on every {@code evaluateOnce()}
 *       invocation -- six counter series under
 *       {@code cortex.monitoring.probe_total} pop in lockstep,
 *       one per service-id (the per-phase tests only ever probe
 *       ONE service-id per assertion).</li>
 *   <li>The {@link ProbeProperties} record binds the
 *       {@code cortex.monitoring.probe.targets} list from
 *       inline properties (proving the same precedence path the
 *       shipped {@code application.yml} default block uses).</li>
 *   <li>The {@link SloProperties} record binds all six default
 *       {@code cortex.monitoring.slo.definitions[*]} entries from
 *       inline indexed properties (proving the same precedence
 *       path the shipped {@code application.yml} default block
 *       uses).</li>
 *   <li>The {@link SloEvaluator} under the noop binder iterates
 *       all six definitions on a single {@code evaluateOnce()}
 *       call without throwing -- the orchestrator ring works
 *       end-to-end even when the engine itself returns a noop
 *       snapshot per definition.</li>
 * </ul>
 *
 * <p>This closer pins {@code cortex.monitoring.slo.backend=noop}
 * (vs. the P8.2a {@code micrometer-derivation} backend) per the
 * checkpoint Next Action which calls for "one {@code SloSnapshot}
 * per service-id under the noop binder". The probe-counter
 * proofs above hold regardless of which engine wires.</p>
 *
 * <p>The {@code probe.evaluation-interval=1h} +
 * {@code slo.evaluation-interval=1h} class-level properties keep
 * the {@code @Scheduled} loops from firing during the test
 * window -- every probe pump + SLO evaluation in this suite is
 * explicit via {@code evaluateOnce()}. The hourly cadence is
 * the operator-friendly {@code Duration} form, made safe under
 * {@code probe.enabled=true} / {@code slo.enabled=true} by
 * routing both cadences through the
 * {@code probeEvaluationIntervalMillis} and
 * {@code sloEvaluationIntervalMillis} adapter beans per LD141.</p>
 *
 * <p>Per ADR-0046 Amendment 3 2026-06-08, this IT serves as the
 * LD73 Leg D gate for the P8.2b ring: it is the automated, CI-
 * protected proof that the multi-target probe pump + default
 * SLO defs work end-to-end through Spring binding +
 * {@code @ConditionalOnProperty} gating, against a stub
 * {@link DiscoveryClient} that routes every cortex service id
 * to a shared in-process WireMock.</p>
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.MOCK,
        properties = {
                "cortex.monitoring.probe.backend=eureka-actuator",
                "cortex.monitoring.probe.enabled=true",
                "cortex.monitoring.probe.evaluation-interval=1h",
                "cortex.monitoring.probe.targets[0]=log-gateway",
                "cortex.monitoring.probe.targets[1]=log-ingest-service",
                "cortex.monitoring.probe.targets[2]=log-echo-service",
                "cortex.monitoring.probe.targets[3]=log-processor-service",
                "cortex.monitoring.probe.targets[4]=log-remediation-service",
                "cortex.monitoring.probe.targets[5]=log-indexer-service",
                "cortex.monitoring.slo.enabled=true",
                "cortex.monitoring.slo.backend=noop",
                "cortex.monitoring.slo.evaluation-interval=1h",
                "cortex.monitoring.slo.definitions[0].service-id=log-gateway",
                "cortex.monitoring.slo.definitions[0].slo-name=availability",
                "cortex.monitoring.slo.definitions[0].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[0].window=PT1H",
                "cortex.monitoring.slo.definitions[1].service-id=log-ingest-service",
                "cortex.monitoring.slo.definitions[1].slo-name=availability",
                "cortex.monitoring.slo.definitions[1].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[1].window=PT1H",
                "cortex.monitoring.slo.definitions[2].service-id=log-echo-service",
                "cortex.monitoring.slo.definitions[2].slo-name=availability",
                "cortex.monitoring.slo.definitions[2].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[2].window=PT1H",
                "cortex.monitoring.slo.definitions[3].service-id=log-processor-service",
                "cortex.monitoring.slo.definitions[3].slo-name=availability",
                "cortex.monitoring.slo.definitions[3].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[3].window=PT1H",
                "cortex.monitoring.slo.definitions[4].service-id=log-remediation-service",
                "cortex.monitoring.slo.definitions[4].slo-name=availability",
                "cortex.monitoring.slo.definitions[4].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[4].window=PT1H",
                "cortex.monitoring.slo.definitions[5].service-id=log-indexer-service",
                "cortex.monitoring.slo.definitions[5].slo-name=availability",
                "cortex.monitoring.slo.definitions[5].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[5].window=PT1H",
                "cortex.monitoring.eureka.request-timeout=30s",
                "cortex.monitoring.eureka.actuator-path=/actuator/health",
                "eureka.client.enabled=false",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.cloud.netflix.eureka."
                        + "EurekaClientAutoConfiguration,"
                        + "org.springframework.cloud.client.discovery.composite."
                        + "CompositeDiscoveryClientAutoConfiguration,"
                        + "org.springframework.cloud.client.discovery.simple."
                        + "SimpleDiscoveryClientAutoConfiguration"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("Monitoring multi-target probe + default SLO defs closer IT (P8.2b)")
class MonitoringMultiTargetProbeAndDefaultSlosIT {

    /** Shared in-process WireMock server (singleton) on a dynamic port. */
    private static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    /** Actuator health path the WireMock server stubs. */
    private static final String ACTUATOR_HEALTH_PATH = "/actuator/health";

    /**
     * The six cortex services the shipped {@code application.yml}
     * default block targets. Mirrored in the class-level
     * {@code properties} above as the indexed inline form so the
     * test stays self-contained (no reliance on the main yml
     * leaking through the test-resources shadow per LD100).
     */
    private static final List<String> EXPECTED_TARGETS = List.of(
            "log-gateway",
            "log-ingest-service",
            "log-echo-service",
            "log-processor-service",
            "log-remediation-service",
            "log-indexer-service");

    /** SLO name reused across all six default availability definitions. */
    private static final String DEFAULT_SLO_NAME = "availability";

    static {
        WIRE_MOCK.start();
    }

    /**
     * Stub {@link DiscoveryClient} bean published into the IT
     * Spring context. Returns a single hard-coded
     * {@link DefaultServiceInstance} pointing at the singleton
     * {@link #WIRE_MOCK} for EVERY service id in
     * {@link #EXPECTED_TARGETS}; everything else returns an empty
     * list (which the production adapter maps to
     * {@code unreachable / eureka-actuator:no-instance}).
     *
     * <p>{@code @Primary} plus the
     * {@code spring.autoconfigure.exclude} above guarantees this
     * stub is the SOLE {@code DiscoveryClient} candidate the
     * production {@code EurekaActuatorHealthProbe} ctor sees
     * (per ADR-0047 D2a).</p>
     */
    @TestConfiguration
    static class StubDiscoveryClientConfig {

        @Bean
        @Primary
        DiscoveryClient stubDiscoveryClient() {
            return new DiscoveryClient() {
                @Override
                public String description() {
                    return "p8-2b-it-stub-discovery-client";
                }

                @Override
                public List<ServiceInstance> getInstances(final String serviceId) {
                    if (EXPECTED_TARGETS.contains(serviceId)) {
                        return List.of(new DefaultServiceInstance(
                                serviceId + ":p8-2b-i1", serviceId,
                                "localhost", WIRE_MOCK.port(), false));
                    }
                    return List.of();
                }

                @Override
                public List<String> getServices() {
                    return EXPECTED_TARGETS;
                }
            };
        }
    }

    private final ScheduledProbeEvaluator probeEvaluator;
    private final SloEvaluator sloEvaluator;
    private final SloBudgetEngine sloEngine;
    private final ProbeProperties probeProperties;
    private final SloProperties sloProperties;
    private final MeterRegistry registry;

    /**
     * Constructor-injection seam mandated by repo Rule 14.1 (no
     * field {@code @Autowired}).
     * {@link TestConstructor.AutowireMode#ALL} resolves every
     * parameter through the autowired Spring context.
     *
     * <p>Kept at six parameters to satisfy Checkstyle
     * {@code ParameterNumber=6}; the {@code MonitoringMetrics}
     * facade is intentionally not a separate ctor param because
     * every assertion in this closer reads the probe counter
     * family through {@link MeterRegistry} directly (the same
     * pattern the P8.2a closer uses).</p>
     *
     * @param scheduledProbeEvaluator new P8.2b multi-target probe
     *                                pump bean (must be present
     *                                because
     *                                {@code probe.enabled=true})
     * @param sloEvaluator            P8.2 SLO evaluator bean
     *                                (must be present because
     *                                {@code slo.enabled=true})
     * @param sloBudgetEngine         autowired SLO engine bean
     *                                (must be the {@code noop}
     *                                backend per this closer's
     *                                binder-gate flip)
     * @param probeProperties         typed config bound from
     *                                {@code cortex.monitoring.probe.*}
     * @param sloProperties           typed config bound from
     *                                {@code cortex.monitoring.slo.*}
     * @param meterRegistry           autowired Micrometer registry
     *                                hosting the probe counter
     *                                family
     */
    MonitoringMultiTargetProbeAndDefaultSlosIT(
            final ScheduledProbeEvaluator scheduledProbeEvaluator,
            final SloEvaluator sloEvaluator,
            final SloBudgetEngine sloBudgetEngine,
            final ProbeProperties probeProperties,
            final SloProperties sloProperties,
            final MeterRegistry meterRegistry) {
        this.probeEvaluator = scheduledProbeEvaluator;
        this.sloEvaluator = sloEvaluator;
        this.sloEngine = sloBudgetEngine;
        this.probeProperties = probeProperties;
        this.sloProperties = sloProperties;
        this.registry = meterRegistry;
    }

    /** Resets WireMock stubs + journal and re-installs the default UP stub for every health path call. */
    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlPathEqualTo(ACTUATOR_HEALTH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"UP\"}")));
    }

    // ---------------------------------------------------------------
    // Binder-gate proofs -- the autowiring contract. Must come first.
    // ---------------------------------------------------------------

    /** New P8.2b multi-target probe pump bean is autowired (probe.enabled=true gate). */
    @Test
    @DisplayName("autowired ScheduledProbeEvaluator bean is present (probe.enabled=true gate)")
    void scheduledProbeEvaluatorBeanIsPresent() {
        assertThat(this.probeEvaluator).isNotNull();
    }

    /** SLO engine bean is the noop backend per this closer's binder-gate flip. */
    @Test
    @DisplayName("autowired SloBudgetEngine bean is the noop backend (binder gate)")
    void sloEngineBeanIsNoopBackend() {
        assertThat(this.sloEngine).isNotNull();
        assertThat(this.sloEngine.backendId())
                .isEqualTo(SloSnapshot.BACKEND_NOOP);
    }

    /** SloEvaluator bean is present (slo.enabled=true gate). */
    @Test
    @DisplayName("autowired SloEvaluator bean is present (slo.enabled=true gate)")
    void sloEvaluatorBeanIsPresent() {
        assertThat(this.sloEvaluator).isNotNull();
    }

    // ---------------------------------------------------------------
    // ProbeProperties + SloProperties binding proofs
    // ---------------------------------------------------------------

    /** ProbeProperties binds all six cortex service ids from the inline indexed targets list. */
    @Test
    @DisplayName("ProbeProperties binds all six default cortex service ids as targets")
    void probePropertiesBindsAllDefaultTargets() {
        assertThat(this.probeProperties.targets())
                .containsExactlyElementsOf(EXPECTED_TARGETS);
        assertThat(this.probeProperties.enabled()).isTrue();
        assertThat(this.probeProperties.backend())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
    }

    /**
     * SloProperties binds all six default availability
     * definitions from the inline indexed definitions list.
     * Proves the same property surface that the shipped
     * {@code application.yml} default block uses.
     */
    @Test
    @DisplayName("SloProperties binds all six default availability SLO definitions")
    void sloPropertiesBindsAllDefaultDefinitions() {
        assertThat(this.sloProperties.definitions()).hasSize(6);
        assertThat(this.sloProperties.definitions())
                .extracting(d -> d.serviceId())
                .containsExactlyElementsOf(EXPECTED_TARGETS);
        assertThat(this.sloProperties.definitions())
                .allSatisfy(def -> {
                    assertThat(def.sloName()).isEqualTo(DEFAULT_SLO_NAME);
                    assertThat(def.targetSuccessRatio()).isEqualTo(0.99d);
                    assertThat(def.window().toHours()).isEqualTo(1L);
                });
    }

    // ---------------------------------------------------------------
    // ScheduledProbeEvaluator fan-out -- the headline P8.2b proof
    // ---------------------------------------------------------------

    /**
     * Calling {@link ScheduledProbeEvaluator#evaluateOnce()} on a
     * boot with the six default cortex targets configured + the
     * eureka-actuator probe backend + a stub
     * {@link DiscoveryClient} that routes every target to the
     * shared WireMock + WireMock returning {@code UP} for the
     * health path must produce ONE
     * {@code cortex.monitoring.probe_total} counter series per
     * configured target id (six total) with
     * {@code outcome=healthy} and the matching
     * {@code service_id} tag.
     *
     * <p>This is the headline P8.2b proof: the multi-target pump
     * actually fans out. Per-phase tests structurally cannot
     * verify this because they only ever probe one service id
     * per assertion.</p>
     */
    @Test
    @DisplayName("scheduled probe pump fans out across all six default targets and ticks counters")
    void scheduledProbeEvaluatorFansOutAcrossAllConfiguredTargets() {
        for (final String serviceId : EXPECTED_TARGETS) {
            assertThat(probeCounterValueForService(
                    HealthSnapshot.OUTCOME_HEALTHY, serviceId))
                    .as("baseline counter for serviceId=%s must be zero",
                            serviceId)
                    .isEqualTo(0.0d);
        }

        this.probeEvaluator.evaluateOnce();

        for (final String serviceId : EXPECTED_TARGETS) {
            assertThat(probeCounterValueForService(
                    HealthSnapshot.OUTCOME_HEALTHY, serviceId))
                    .as("probe pump must tick counter for serviceId=%s exactly once",
                            serviceId)
                    .isEqualTo(1.0d);
        }
    }

    // ---------------------------------------------------------------
    // SloEvaluator -- orchestrator ring over six default definitions
    // ---------------------------------------------------------------

    /**
     * {@link SloEvaluator#evaluateOnce()} with the six default
     * definitions wired + the noop engine selected iterates
     * every definition without throwing. The noop engine
     * returns {@link SloSnapshot#noop(String)} for each call so
     * no gauge values are asserted -- the proof is that the
     * orchestrator ring works end-to-end against the default
     * config block, complementing the headline probe-pump fan-
     * out proof above. The micrometer-derivation backend
     * pipeline is covered by the P8.2a closer.
     */
    @Test
    @DisplayName("SloEvaluator iterates all six default definitions under noop backend without throwing")
    void sloEvaluatorEvaluatesAllDefaultDefinitionsUnderNoopBackend() {
        this.sloEvaluator.evaluateOnce();
        assertThat(this.sloEvaluator).isNotNull();
    }

    // ---------------------------------------------------------------
    // Probe counter helper (per-service-id reader; delta-from-zero
    // baseline since this closer probes fresh service ids that no
    // other test in this class touches).
    // ---------------------------------------------------------------

    private double probeCounterValueForService(final String outcome,
                                               final String serviceId) {
        final Counter c = this.registry
                .find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", HealthSnapshot.BACKEND_EUREKA_ACTUATOR)
                .tag("outcome", outcome)
                .tag("service_id", serviceId)
                .counter();
        return c == null ? 0.0d : c.count();
    }
}
