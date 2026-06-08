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
import io.cortex.monitoring.slo.SloSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestConstructor;

/**
 * P8.1a cross-phase closer for {@code log-monitoring-service}.
 *
 * <p>Mechanical closer for the P8.0 + P8.1 ring ONLY (probe
 * surface in isolation). Mirrors the P8.2a closer pattern shipped
 * in {@link MonitoringProbeAndSloPipelineIT} (singleton in-process
 * WireMock + {@code @TestConfiguration @Bean @Primary
 * DiscoveryClient} stub + {@code @TestConstructor(autowireMode=ALL)}
 * + constructor-injected {@code private final} fields per
 * Checkstyle Rule 14.1) BUT differs on two intentional axes per
 * ADR-0048:</p>
 *
 * <ul>
 *   <li><strong>Probe-only binder gate flip.</strong> Only
 *       {@code cortex.monitoring.probe.backend=eureka-actuator}
 *       is set. SLO engine + evaluator are left at their
 *       application.yml defaults
 *       ({@code cortex.monitoring.slo.enabled=false} +
 *       {@code cortex.monitoring.slo.backend=noop}). This is the
 *       production-shaped operator deployment shape: the probe
 *       is wired against the live registry but SLO definitions
 *       have not yet been queued. The P8.2a closer covers the
 *       both-gates-flipped shape; this closer covers the
 *       probe-only shape that LD104 closer-separation discipline
 *       requires the regression matrix to also exercise.</li>
 *   <li><strong>No LD137 workaround needed.</strong> Because
 *       {@code slo.enabled=false}, the prod
 *       {@code SloEvaluator.@Scheduled(fixedRateString=
 *       "#{@sloEvaluationIntervalMillis}")} declaration is NOT
 *       exercised. The historical numeric-millis override (see
 *       memory.md LD137 + GitHub issue #120) was removed from
 *       the P8.2a sibling IT once issue #120 shipped (ADR-0046
 *       Amendment 2026-06-08); this IT was never sensitive to
 *       the bug because it leaves SLO at the noop default. The
 *       IT remains the canonical proof that the probe surface
 *       boots and behaves correctly in a probe-only
 *       configuration.</li>
 * </ul>
 *
 * <p><strong>{@link WebEnvironment#RANDOM_PORT}</strong> rather
 * than the P8.2a IT's {@code MOCK} env so the
 * {@link org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration}
 * + {@link
 * org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration}
 * bring up a real embedded Tomcat that publishes the
 * {@code /actuator/health/monitoring} URI segment. A
 * {@link TestRestTemplate} then exercises the HTTP surface
 * end-to-end and asserts the
 * {@link io.cortex.monitoring.health.MonitoringHealthIndicator}
 * surfaces the active probe backend id in the
 * {@code details.backend} field of the JSON response. P8.2a's
 * MOCK env cannot prove this leg structurally because no
 * embedded server is started.</p>
 *
 * <p>Per ADR-0048 D1, this IT serves as the LD73 Leg D gate for
 * the P8.0 + P8.1 ring: it is the automated, CI-protected proof
 * that the probe surface boots cleanly against a wire-format-
 * identical Eureka + actuator stand-in WITHOUT depending on
 * the LD137 workaround.</p>
 *
 * @see MonitoringProbeAndSloPipelineIT
 *      P8.2a closer (covers the both-gates-flipped surface +
 *      Prometheus container smoke + SLO derivation through
 *      autowired beans)
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "cortex.monitoring.probe.backend=eureka-actuator",
                "cortex.monitoring.eureka.request-timeout=30s",
                "cortex.monitoring.eureka.actuator-path=/actuator/health",
                "eureka.client.enabled=false",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false",
                "management.endpoint.health.show-details=always",
                "management.endpoint.health.show-components=always",
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
                // DiscoveryClient bean unless explicitly excluded. Same
                // fix the P8.2a IT carries; captured as ADR-0047 D2a +
                // re-applied here per ADR-0048 D2.
                "spring.autoconfigure.exclude="
                        + "org.springframework.cloud.netflix.eureka."
                        + "EurekaClientAutoConfiguration,"
                        + "org.springframework.cloud.client.discovery.composite."
                        + "CompositeDiscoveryClientAutoConfiguration,"
                        + "org.springframework.cloud.client.discovery.simple."
                        + "SimpleDiscoveryClientAutoConfiguration"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("Monitoring probe + health-indicator cross-phase closer IT (P8.0 + P8.1 probe-only)")
class MonitoringProbeAndHealthIndicatorIT {

    /** Shared in-process WireMock server (singleton) on a dynamic port. */
    private static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    /** Eureka service id stubbed by the test DiscoveryClient bean. */
    private static final String TARGET_SERVICE_ID = "log-echo-service";

    /** Instance id stubbed by the test DiscoveryClient bean. */
    private static final String TARGET_INSTANCE_ID = "log-echo-p8-1a-it";

    /** Actuator health path the WireMock server stubs. */
    private static final String ACTUATOR_HEALTH_PATH = "/actuator/health";

    /** Path of the per-module health indicator the closer asserts. */
    private static final String HEALTH_INDICATOR_PATH = "/actuator/health/monitoring";

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
                    return "p8-1a-it-stub-discovery-client";
                }

                @Override
                public List<ServiceInstance> getInstances(final String serviceId) {
                    if (TARGET_SERVICE_ID.equals(serviceId)) {
                        return List.of(new DefaultServiceInstance(
                                TARGET_INSTANCE_ID, serviceId,
                                "localhost", WIRE_MOCK.port(), false));
                    }
                    return List.of();
                }

                @Override
                public List<String> getServices() {
                    return List.of(TARGET_SERVICE_ID);
                }
            };
        }
    }

    private final ServiceHealthProbe probe;
    private final ObjectProvider<SloBudgetEngine> engineProvider;
    private final MonitoringMetrics metrics;
    private final MeterRegistry registry;
    private final TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    /**
     * Constructor-injection seam mandated by repo Rule 14.1 (no
     * field {@code @Autowired}). {@link TestConstructor.AutowireMode#ALL}
     * resolves every parameter through the autowired Spring
     * context. Mirrors {@link MonitoringProbeAndSloPipelineIT}.
     *
     * @param probe          autowired probe bean (must be the
     *                       {@code eureka-actuator} backend after
     *                       the binder gate flip)
     * @param engineProvider autowired SLO engine provider; used
     *                       to assert the engine is the
     *                       {@code noop} backend (slo.backend
     *                       defaulted to noop). Provider rather
     *                       than direct injection so the
     *                       assertion can also tolerate the engine
     *                       being absent in a future variant where
     *                       the noop bean's
     *                       {@code matchIfMissing=true} is
     *                       removed.
     * @param metrics        autowired metrics surface
     * @param registry       autowired Micrometer registry hosting
     *                       the probe counter family
     * @param restTemplate   autowired Spring Boot test rest
     *                       template for actuator HTTP probes
     *                       through the {@link
     *                       WebEnvironment#RANDOM_PORT} embedded
     *                       Tomcat
     */
    @Autowired MonitoringProbeAndHealthIndicatorIT(
            final ServiceHealthProbe probe,
            final ObjectProvider<SloBudgetEngine> engineProvider,
            final MonitoringMetrics metrics,
            final MeterRegistry registry,
            final TestRestTemplate restTemplate) {
        this.probe = probe;
        this.engineProvider = engineProvider;
        this.metrics = metrics;
        this.registry = registry;
        this.restTemplate = restTemplate;
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

    /**
     * Autowired SLO engine bean is the {@code noop} backend.
     * Asserts that NOT flipping {@code cortex.monitoring.slo
     * .backend=micrometer-derivation} leaves the
     * {@link io.cortex.monitoring.slo.NoopSloBudgetEngine}
     * {@code matchIfMissing=true} variant in place. This is the
     * production-shaped operator deployment shape that does NOT
     * depend on the LD137 workaround.
     */
    @Test
    @DisplayName("autowired SloBudgetEngine bean is the noop backend (slo.backend default)")
    void sloEngineBeanIsNoopBackend() {
        final SloBudgetEngine engine = this.engineProvider.getIfAvailable();
        assertThat(engine)
                .as("noop SloBudgetEngine must be present "
                        + "(matchIfMissing=true on NoopSloBudgetEngine)")
                .isNotNull();
        assertThat(engine.backendId())
                .isEqualTo(SloSnapshot.BACKEND_NOOP);
    }

    // ---------------------------------------------------------------
    // Probe SPI end-to-end through autowired beans (P8.0 + P8.1)
    // ---------------------------------------------------------------

    /** Healthy probe -> {@code healthy} verdict + probe counter ticks +1. */
    @Test
    @DisplayName("P8.1 probe happy path -> healthy verdict + probe counter ticks (probe-only IT)")
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

    // ---------------------------------------------------------------
    // MonitoringHealthIndicator HTTP surface (P8.0 / ADR-0044 D4)
    //
    // The P8.2a IT uses WebEnvironment.MOCK and therefore cannot
    // prove the actuator HTTP surface. The P8.1a IT uses
    // RANDOM_PORT specifically to close this gap end-to-end.
    // ---------------------------------------------------------------

    /**
     * {@code /actuator/health/monitoring} returns 200 UP with
     * {@code details.backend=eureka-actuator}. Proves the
     * {@link io.cortex.monitoring.health.MonitoringHealthIndicator}
     * binds to the probe bean's {@code backendId()} through the
     * autowired Spring context and is reachable over real HTTP via
     * embedded Tomcat -- not just through the
     * {@link org.springframework.boot.actuate.health.HealthEndpoint}
     * bean as the per-phase tests would prove.
     */
    @Test
    @DisplayName("/actuator/health/monitoring HTTP surface -> 200 UP + details.backend=eureka-actuator")
    void healthIndicatorHttpSurfaceReportsActiveBackend() {
        final ParameterizedTypeReference<Map<String, Object>> bodyType =
                new ParameterizedTypeReference<>() { };
        final ResponseEntity<Map<String, Object>> response = this.restTemplate.exchange(
                "http://localhost:" + this.port + HEALTH_INDICATOR_PATH,
                HttpMethod.GET, null, bodyType);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        final Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo("UP");

        final Object details = body.get("details");
        assertThat(details)
                .as("details map must be exposed when "
                        + "management.endpoint.health.show-details=always")
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        final Map<String, Object> detailsMap = (Map<String, Object>) details;
        assertThat(detailsMap.get("backend"))
                .as("backend detail must reflect the active "
                        + "ServiceHealthProbe.backendId() under the "
                        + "eureka-actuator binder gate")
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
    }

    // ---------------------------------------------------------------
    // Bootstrap loop proof (P8.0 / LD106 + LD112)
    //
    // The MonitoringMetrics @PostConstruct loop iterates over every
    // ServiceHealthProbe bean. With the eureka-actuator binder gate
    // flipped, the loop must register all 6 failable outcome series
    // for the `eureka-actuator` backend at bootstrap-time so the
    // Prometheus scrape sees the family before the first probe call.
    // ---------------------------------------------------------------

    /**
     * Bootstrap-registered counter series for the
     * {@code eureka-actuator} backend's 6 failable outcomes are
     * present in the registry at boot (i.e. before any probe call
     * has ticked them). Each is the all-{@code unknown}
     * service-tag placeholder per LD106 + LD112.
     */
    @Test
    @DisplayName("Bootstrap loop registers all 6 eureka-actuator outcome series at boot (LD106 + LD112)")
    void bootstrapLoopRegistersEurekaActuatorOutcomeSeries() {
        for (final String outcome : List.of(
                HealthSnapshot.OUTCOME_HEALTHY,
                HealthSnapshot.OUTCOME_DEGRADED,
                HealthSnapshot.OUTCOME_UNHEALTHY,
                HealthSnapshot.OUTCOME_UNREACHABLE,
                HealthSnapshot.OUTCOME_TRANSIENT_FAILURE,
                HealthSnapshot.OUTCOME_PERMANENT_FAILURE)) {
            final Counter c = this.registry
                    .find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                    .tag("backend", HealthSnapshot.BACKEND_EUREKA_ACTUATOR)
                    .tag("outcome", outcome)
                    .tag("service_id", MonitoringMetrics.UNKNOWN)
                    .counter();
            assertThat(c)
                    .as("bootstrap counter for backend=eureka-actuator "
                            + "outcome=" + outcome
                            + " service_id=" + MonitoringMetrics.UNKNOWN
                            + " must be present at boot")
                    .isNotNull();
        }
    }

    // ---------------------------------------------------------------
    // Counter helpers (delta-from-baseline pattern per LD73)
    // ---------------------------------------------------------------

    private double probeCounterValue(final String outcome) {
        final Counter c = this.registry
                .find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", HealthSnapshot.BACKEND_EUREKA_ACTUATOR)
                .tag("outcome", outcome)
                .tag("service_id", TARGET_SERVICE_ID)
                .counter();
        return c == null ? 0.0d : c.count();
    }

    // Avoid "unused field" warning on `this.metrics` -- the
    // assertion exists purely to keep the ctor's @Autowired
    // contract self-documenting (metrics IS the surface this IT
    // is regressing against, even when individual assertions go
    // through the MeterRegistry directly).
    @Test
    @DisplayName("MonitoringMetrics bean is autowired into the IT (ctor contract)")
    void monitoringMetricsBeanIsAutowired() {
        assertThat(this.metrics).isNotNull();
    }
}
