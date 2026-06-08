package io.cortex.monitoring.closer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cortex.monitoring.metrics.MonitoringMetrics;
import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.ProbeRequest;
import io.cortex.monitoring.probe.ServiceHealthProbe;
import io.cortex.monitoring.slo.SloBudgetEngine;
import io.cortex.monitoring.slo.SloDefinition;
import io.cortex.monitoring.slo.SloEvaluator;
import io.cortex.monitoring.slo.SloSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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
 * P8.2a cross-phase closer for {@code log-monitoring-service}.
 *
 * <p>Closes the P8 epic by booting the full Spring context with
 * BOTH the {@code eureka-actuator} probe backend AND the
 * {@code micrometer-derivation} SLO engine backend flipped ON
 * (default in {@code application.yml} is {@code noop} for both)
 * and exercising every SPI method shipped across P8.0..P8.2
 * through autowired beans against a singleton in-process WireMock
 * that stands in for a downstream cortex service's
 * {@code /actuator/health} endpoint and a stub
 * {@link DiscoveryClient} bean that resolves
 * {@code ProbeRequest.serviceId() == log-echo-service} to the
 * WireMock's port.</p>
 *
 * <p>What this proves that the per-phase
 * {@code EurekaActuatorHealthProbeWireMockIT} (P8.1) and
 * {@code MicrometerSloBudgetEngineTest} (P8.2 unit) structurally
 * could not (they construct the adapter directly with {@code new},
 * bypassing Spring binding entirely):</p>
 * <ul>
 *   <li>The {@code @ConditionalOnProperty} binder gates on
 *       {@link io.cortex.monitoring.probe.eureka.EurekaActuatorHealthProbe}
 *       ({@code cortex.monitoring.probe.backend=eureka-actuator})
 *       AND
 *       {@link io.cortex.monitoring.slo.MicrometerSloBudgetEngine}
 *       ({@code cortex.monitoring.slo.backend=micrometer-derivation})
 *       fire when their properties are set, and the beans are
 *       published into the Spring context (autowiring works
 *       end-to-end).</li>
 *   <li>The {@link SloEvaluator} bean is created when
 *       {@code cortex.monitoring.slo.enabled=true} -- gating that
 *       no per-phase test exercises because the per-phase tests
 *       evaluate engines directly without the {@code @Scheduled}
 *       orchestrator.</li>
 *   <li>The {@code @Lazy MonitoringMetrics} ctor parameter on
 *       {@link io.cortex.monitoring.probe.eureka.EurekaActuatorHealthProbe}
 *       (LD131) holds the bean cycle open when the bootstrap loop
 *       in {@link MonitoringMetrics} iterates
 *       {@code List<ServiceHealthProbe>} -- proves the cycle break
 *       survives the SLO engine being added to the context.</li>
 *   <li>The shared {@code RestClient} bean (HTTP/1.1 pin per
 *       LD42 + dual timeout per LD121) published by
 *       {@code EurekaActuatorHttpConfig} is consumed by the
 *       {@code eureka-actuator} probe adapter (no separate
 *       {@code RestClient} for the SLO engine -- it reads counter
 *       series via {@link MeterRegistry}, never makes HTTP
 *       calls).</li>
 *   <li>The full probe-to-SLO pipeline: a healthy probe call
 *       ticks {@code cortex.monitoring.probe_total{backend=eureka-
 *       actuator,outcome=healthy,service_id=log-echo-service}},
 *       the {@link io.cortex.monitoring.slo.MicrometerSloBudgetEngine}
 *       reads that counter family for the matching {@code
 *       service_id} and produces a non-default
 *       {@link SloSnapshot}, and
 *       {@link MonitoringMetrics#recordSlo(SloSnapshot)} registers
 *       the {@code cortex.monitoring.slo_budget_remaining} +
 *       {@code cortex.monitoring.slo_burn_rate} gauges per
 *       {@code (service_id, slo_name)} key.</li>
 * </ul>
 *
 * <p>The singleton {@link WireMockServer} is started in a static
 * initialiser so its dynamic port is known by the time the stub
 * {@link DiscoveryClient} bean is constructed. WireMock stubs +
 * journal are reset in {@link #resetWireMock()} so each test sets
 * its own stub for the {@code /actuator/health} surface (UP /
 * DOWN / 5xx / etc). Counter assertions use a delta-from-baseline
 * pattern so test order is irrelevant.</p>
 *
 * <p>{@link SloEvaluator}'s {@code @Scheduled} cadence is bumped
 * to one hour for the IT (
 * {@code cortex.monitoring.slo.evaluation-interval=1h}) so the
 * scheduler never fires during the test window -- every SLO
 * evaluation in this suite is explicit. The hourly cadence is
 * the operator-friendly {@code Duration} form, made safe under
 * {@code slo.enabled=true} by routing the value through the
 * {@code sloEvaluationIntervalMillis} adapter bean published by
 * {@code SloEngineConfig} (issue #120 / LD137 / ADR-0046
 * Amendment 2026-06-08); the historical numeric-millis
 * workaround captured in LD137 was removed as part of the issue
 * #120 fix.</p>
 *
 * <p>Per ADR-0047 D1, this IT serves as the LD73 Leg D gate for
 * the P8.0 + P8.1 + P8.2 ring: it is the automated, CI-protected
 * proof that the full SPI surface works against a wire-format-
 * identical Eureka + actuator stand-in.</p>
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.MOCK,
        properties = {
                "cortex.monitoring.probe.backend=eureka-actuator",
                "cortex.monitoring.slo.enabled=true",
                "cortex.monitoring.slo.backend=micrometer-derivation",
                "cortex.monitoring.eureka.request-timeout=30s",
                "cortex.monitoring.eureka.actuator-path=/actuator/health",
                // Operator-friendly `1h` form -- now safe under
                // `slo.enabled=true` because the prod
                // `SloEvaluator.@Scheduled` declaration reads
                // its cadence through the SpEL bean reference
                // `#{@sloEvaluationIntervalMillis}` (resolved by
                // `SloEngineConfig.sloEvaluationIntervalMillis`)
                // rather than the legacy
                // `${cortex.monitoring.slo.evaluation-interval}`
                // placeholder. The hourly cadence keeps the
                // scheduler from firing during the test window
                // so every SLO evaluation in this suite stays
                // explicit. The historical numeric-millis
                // workaround captured in LD137 / issue #120 was
                // removed as part of the issue #120 fix
                // (ADR-0046 Amendment 2026-06-08).
                "cortex.monitoring.slo.evaluation-interval=1h",
                "eureka.client.enabled=false",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false",
                // Excluding the Eureka + Composite + Simple DiscoveryClient
                // autoconfigs forces our stub @Bean (which is itself
                // @Primary) to be the SOLE candidate the
                // EurekaActuatorHealthProbe ctor sees. Without this,
                // Spring Cloud Commons publishes compositeDiscoveryClient
                // + simpleDiscoveryClient -- both themselves @Primary --
                // alongside our stub, yielding
                // NoUniqueBeanDefinitionException ("more than one 'primary'
                // bean found among candidates"). The property-level
                // `eureka.client.enabled=false` only gates registration /
                // fetch traffic; the autoconfig still publishes the
                // DiscoveryClient bean unless explicitly excluded.
                "spring.autoconfigure.exclude="
                        + "org.springframework.cloud.netflix.eureka."
                        + "EurekaClientAutoConfiguration,"
                        + "org.springframework.cloud.client.discovery.composite."
                        + "CompositeDiscoveryClientAutoConfiguration,"
                        + "org.springframework.cloud.client.discovery.simple."
                        + "SimpleDiscoveryClientAutoConfiguration"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("Monitoring probe + SLO pipeline cross-phase closer IT (P8.0..P8.2 end-to-end)")
class MonitoringProbeAndSloPipelineIT {

    /** Shared in-process WireMock server (singleton) on a dynamic port. */
    private static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    /** Eureka service id stubbed by the test DiscoveryClient bean. */
    private static final String TARGET_SERVICE_ID = "log-echo-service";

    /** Instance id stubbed by the test DiscoveryClient bean. */
    private static final String TARGET_INSTANCE_ID = "log-echo-it";

    /**
     * A SECOND stubbed service id used ONLY by the SLO budget
     * derivation test ({@link #sloEngineDerivesBandedSnapshotAndTicksGauges()}).
     * Splitting it from {@link #TARGET_SERVICE_ID} keeps the
     * derivation test independent of any failure ticks the
     * probe tests pile onto {@code TARGET_SERVICE_ID}'s
     * {@code cortex.monitoring.probe_total} series -- JUnit 5
     * test order is non-deterministic, so the derivation test
     * needs its OWN clean counter family for its budget=1.0 /
     * burnRate=0.0 assertions to hold regardless of execution
     * order. The stub {@link DiscoveryClient} routes BOTH ids
     * to the same {@link #WIRE_MOCK} instance, which mirrors a
     * realistic operator deployment where multiple Eureka
     * service ids point at the same actuator surface.
     */
    private static final String DERIVE_SERVICE_ID = "log-echo-slo-derive";

    /** Instance id stubbed by the test DiscoveryClient bean for {@link #DERIVE_SERVICE_ID}. */
    private static final String DERIVE_INSTANCE_ID = "log-echo-slo-derive-i1";

    /** SLO name reused across SLO evaluation tests. */
    private static final String SLO_NAME = "availability-it";

    /** Actuator health path the WireMock server stubs. */
    private static final String ACTUATOR_HEALTH_PATH = "/actuator/health";

    static {
        WIRE_MOCK.start();
    }

    /**
     * Stub {@link DiscoveryClient} bean published into the IT
     * Spring context. Returns a single hard-coded
     * {@link DefaultServiceInstance} pointing at the singleton
     * {@link #WIRE_MOCK} for the target service id; everything
     * else returns an empty list (which the production adapter
     * maps to {@code unreachable / eureka-actuator:no-instance}).
     *
     * <p>{@code @Primary} plus the
     * {@code spring.autoconfigure.exclude} above guarantees this
     * stub is the SOLE {@code DiscoveryClient} candidate the
     * production {@code EurekaActuatorHealthProbe} ctor sees.</p>
     */
    @TestConfiguration
    static class StubDiscoveryClientConfig {

        @Bean
        @Primary
        DiscoveryClient stubDiscoveryClient() {
            return new DiscoveryClient() {
                @Override
                public String description() {
                    return "p8-2a-it-stub-discovery-client";
                }

                @Override
                public List<ServiceInstance> getInstances(final String serviceId) {
                    if (TARGET_SERVICE_ID.equals(serviceId)) {
                        return List.of(new DefaultServiceInstance(
                                TARGET_INSTANCE_ID, serviceId,
                                "localhost", WIRE_MOCK.port(), false));
                    }
                    if (DERIVE_SERVICE_ID.equals(serviceId)) {
                        return List.of(new DefaultServiceInstance(
                                DERIVE_INSTANCE_ID, serviceId,
                                "localhost", WIRE_MOCK.port(), false));
                    }
                    return List.of();
                }

                @Override
                public List<String> getServices() {
                    return List.of(TARGET_SERVICE_ID, DERIVE_SERVICE_ID);
                }
            };
        }
    }

    private final ServiceHealthProbe probe;
    private final SloBudgetEngine engine;
    private final SloEvaluator evaluator;
    private final MonitoringMetrics metrics;
    private final MeterRegistry registry;

    /**
     * Constructor-injection seam mandated by repo Rule 14.1 (no
     * field {@code @Autowired}). {@link TestConstructor.AutowireMode#ALL}
     * resolves every parameter through the autowired Spring
     * context. Mirrors {@code QuickwitCrossPhaseIT} in
     * {@code log-indexer-service}.
     *
     * @param probe     autowired probe bean (must be the
     *                  {@code eureka-actuator} backend after the
     *                  binder gate flip)
     * @param engine    autowired SLO engine bean (must be the
     *                  {@code micrometer-derivation} backend)
     * @param evaluator autowired SLO evaluator bean (must exist
     *                  because {@code slo.enabled=true})
     * @param metrics   autowired metrics surface
     * @param registry  autowired Micrometer registry hosting the
     *                  probe counter + SLO gauge families
     */
    MonitoringProbeAndSloPipelineIT(final ServiceHealthProbe probe,
                                    final SloBudgetEngine engine,
                                    final SloEvaluator evaluator,
                                    final MonitoringMetrics metrics,
                                    final MeterRegistry registry) {
        this.probe = probe;
        this.engine = engine;
        this.evaluator = evaluator;
        this.metrics = metrics;
        this.registry = registry;
    }

    /** Resets WireMock stubs + request journal and re-installs the default UP stub. */
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

    /** Autowired probe bean is the production {@code eureka-actuator} backend, not the noop. */
    @Test
    @DisplayName("autowired ServiceHealthProbe bean is the eureka-actuator backend (binder gate)")
    void probeBeanIsEurekaActuatorBackend() {
        assertThat(this.probe).isNotNull();
        assertThat(this.probe.backendId())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
    }

    /** Autowired SLO engine bean is the production {@code micrometer-derivation} backend, not the noop. */
    @Test
    @DisplayName("autowired SloBudgetEngine bean is the micrometer-derivation backend (binder gate)")
    void sloEngineBeanIsMicrometerDerivationBackend() {
        assertThat(this.engine).isNotNull();
        assertThat(this.engine.backendId())
                .isEqualTo(SloSnapshot.BACKEND_MICROMETER_DERIVATION);
    }

    /** SloEvaluator bean is present when {@code slo.enabled=true}. */
    @Test
    @DisplayName("autowired SloEvaluator bean is present (slo.enabled=true gate)")
    void sloEvaluatorBeanIsPresent() {
        assertThat(this.evaluator).isNotNull();
    }

    // ---------------------------------------------------------------
    // P8.1 probe SPI end-to-end through autowired beans
    // ---------------------------------------------------------------

    /** Healthy probe -> {@code healthy} verdict + probe counter ticks +1. */
    @Test
    @DisplayName("P8.1 probe happy path -> healthy verdict + probe counter ticks")
    void probeHealthyPathIncrementsCounter() {
        final double before = probeCounterValue(HealthSnapshot.OUTCOME_HEALTHY);

        final HealthSnapshot snap = this.probe.probe(
                new ProbeRequest(TARGET_SERVICE_ID, TARGET_INSTANCE_ID));

        assertThat(snap.backend())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
        assertThat(snap.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_HEALTHY);
        assertThat(snap.detail()).isEqualTo("UP");

        WIRE_MOCK.verify(getRequestedFor(urlPathEqualTo(ACTUATOR_HEALTH_PATH)));
        assertThat(probeCounterValue(HealthSnapshot.OUTCOME_HEALTHY))
                .isEqualTo(before + 1.0d);
    }

    /** DOWN -> {@code unhealthy} verdict + probe counter ticks +1. */
    @Test
    @DisplayName("P8.1 probe DOWN status -> unhealthy verdict + probe counter ticks")
    void probeDownStatusIncrementsCounter() {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlPathEqualTo(ACTUATOR_HEALTH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"DOWN\"}")));

        final double before = probeCounterValue(HealthSnapshot.OUTCOME_UNHEALTHY);

        final HealthSnapshot snap = this.probe.probe(
                new ProbeRequest(TARGET_SERVICE_ID, TARGET_INSTANCE_ID));

        assertThat(snap.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_UNHEALTHY);
        assertThat(probeCounterValue(HealthSnapshot.OUTCOME_UNHEALTHY))
                .isEqualTo(before + 1.0d);
    }

    /** Unknown service id -> {@code unreachable} via the stub {@link DiscoveryClient}. */
    @Test
    @DisplayName("P8.1 probe missing serviceId -> unreachable verdict (no-instance)")
    void probeUnknownServiceIdIsUnreachable() {
        final double before = probeCounterValueForService(
                HealthSnapshot.OUTCOME_UNREACHABLE, "log-no-such-service");

        final HealthSnapshot snap = this.probe.probe(
                new ProbeRequest("log-no-such-service", "missing-i1"));

        assertThat(snap.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_UNREACHABLE);
        assertThat(snap.reason())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR + ":no-instance");
        assertThat(probeCounterValueForService(
                HealthSnapshot.OUTCOME_UNREACHABLE, "log-no-such-service"))
                .isEqualTo(before + 1.0d);
    }

    // ---------------------------------------------------------------
    // P8.2 SLO engine end-to-end through autowired beans
    // ---------------------------------------------------------------

    /**
     * After a healthy probe tick, the SLO engine produces a
     * {@code banded} snapshot with {@code budgetRemaining=1.0} +
     * {@code burnRate=0.0} (the only sample is a success, so the
     * error budget is fully intact). Recording it through
     * {@link MonitoringMetrics#recordSlo(SloSnapshot)} registers
     * the gauge pair on the registry.
     */
    @Test
    @DisplayName("P8.2 SLO engine derives banded snapshot from probe counter and ticks gauges")
    void sloEngineDerivesBandedSnapshotAndTicksGauges() {
        // Probe a DEDICATED service id (DERIVE_SERVICE_ID) so this
        // test is independent of any failure ticks the probe tests
        // piled onto TARGET_SERVICE_ID's counter family. The stub
        // DiscoveryClient routes BOTH ids to the same WireMock.
        this.probe.probe(new ProbeRequest(DERIVE_SERVICE_ID, DERIVE_INSTANCE_ID));

        final SloDefinition def = new SloDefinition(
                DERIVE_SERVICE_ID, SLO_NAME, 0.99d, Duration.ofHours(1));
        final SloSnapshot snap = this.engine.evaluate(def);

        assertThat(snap).isNotNull();
        assertThat(snap.backend())
                .isEqualTo(SloSnapshot.BACKEND_MICROMETER_DERIVATION);
        assertThat(snap.serviceId()).isEqualTo(DERIVE_SERVICE_ID);
        assertThat(snap.sloName()).isEqualTo(SLO_NAME);
        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_HEALTHY);
        assertThat(snap.budgetRemainingRatio()).isEqualTo(1.0d);
        assertThat(snap.burnRate()).isEqualTo(0.0d);

        this.metrics.recordSlo(snap);

        final Gauge budget = this.registry
                .find(MonitoringMetrics.METRIC_SLO_BUDGET_REMAINING)
                .tag("service_id", DERIVE_SERVICE_ID)
                .tag("slo_name", SLO_NAME)
                .gauge();
        assertThat(budget).isNotNull();
        assertThat(Double.isNaN(budget.value())).isFalse();
        assertThat(budget.value()).isEqualTo(1.0d);

        final Gauge burn = this.registry
                .find(MonitoringMetrics.METRIC_SLO_BURN_RATE)
                .tag("service_id", DERIVE_SERVICE_ID)
                .tag("slo_name", SLO_NAME)
                .gauge();
        assertThat(burn).isNotNull();
        assertThat(Double.isNaN(burn.value())).isFalse();
        assertThat(burn.value()).isEqualTo(0.0d);
    }

    /**
     * When no probe ticks exist for the target service id, the
     * SLO engine returns {@code unknown / micrometer-derivation:no-data}
     * per ADR-0046 D5 (no observation, no inference).
     */
    @Test
    @DisplayName("P8.2 SLO engine returns unknown with no-data reason for cold service")
    void sloEngineNoDataIsUnknown() {
        final SloDefinition def = new SloDefinition(
                "log-cold-service", "availability-cold",
                0.99d, Duration.ofHours(1));

        final SloSnapshot snap = this.engine.evaluate(def);

        assertThat(snap).isNotNull();
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_UNKNOWN);
        assertThat(snap.reason())
                .isEqualTo(SloSnapshot.BACKEND_MICROMETER_DERIVATION + ":no-data");
    }

    // ---------------------------------------------------------------
    // SloEvaluator -- the orchestration ring (P8.2 / @Scheduled)
    // ---------------------------------------------------------------

    /**
     * {@link SloEvaluator#evaluateOnce()} with an empty
     * {@code definitions} list is a no-op (does not throw, does
     * not register any gauges). Proves the orchestrator's default
     * configuration survives the cross-phase boot.
     */
    @Test
    @DisplayName("SloEvaluator.evaluateOnce() with empty definitions is a safe no-op")
    void sloEvaluatorEvaluateOnceWithEmptyDefinitionsIsNoop() {
        // properties.definitions() defaults to empty list per
        // SloProperties compact ctor; calling evaluateOnce must
        // not throw + must not register any new gauges.
        this.evaluator.evaluateOnce();
    }

    // ---------------------------------------------------------------
    // Probe counter helpers (delta-from-baseline pattern per LD73)
    // ---------------------------------------------------------------

    private double probeCounterValue(final String outcome) {
        return probeCounterValueForService(outcome, TARGET_SERVICE_ID);
    }

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
