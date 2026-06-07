package io.cortex.monitoring.probe.eureka;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import io.cortex.monitoring.metrics.MonitoringMetrics;
import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.ProbeRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * WireMock-driven integration test for
 * {@link EurekaActuatorHealthProbe} (P8.1 / ADR-0045 D3 outcome
 * table; failsafe pass; LD120 deterministic transport fault;
 * LD123 cold-start read-timeout bump).
 *
 * <p>Boots an in-process {@link WireMockServer} on a dynamic port,
 * stubs the {@code /actuator/health} surface with the full set of
 * status payloads + HTTP outcome stubs called out in ADR-0045 D3
 * (UP -&gt; healthy, OUT_OF_SERVICE -&gt; degraded, DOWN -&gt;
 * unhealthy, missing/unknown status -&gt; degraded, 404 -&gt;
 * permanent, 429 -&gt; transient, 500 -&gt; transient,
 * {@link Fault#CONNECTION_RESET_BY_PEER} -&gt; transient
 * transport), and asserts the adapter's HTTP outcome -&gt;
 * {@link HealthSnapshot} mapping end-to-end against a real HTTP
 * server.</p>
 *
 * <p>The {@link RestClient} the adapter consumes is constructed
 * locally with the same HTTP/1.1 pin (LD42) +
 * {@link JdkClientHttpRequestFactory} the production
 * {@link EurekaActuatorHttpConfig#eurekaActuatorRestClient(EurekaActuatorProperties)}
 * bean uses, so what the IT exercises is wire-format identical to
 * what production sends.</p>
 */
@DisplayName("EurekaActuatorHealthProbe WireMock IT")
class EurekaActuatorHealthProbeWireMockIT {

    private static final String SERVICE_ID = "log-indexer-service";
    private static final String INSTANCE_ID = "log-indexer-service:abc-123";
    private static final String ACTUATOR_PATH = "/actuator/health";

    /**
     * Read timeout for the IT-local {@link RestClient}. Bumped from
     * production's 5 s default per LD123 so the first JIT-cold
     * request through the JDK {@link HttpClient} + WireMock stack
     * does not trip the transport-failure classifier when this IT
     * sorts first in Failsafe's alphabetical run order.
     */
    private static final int IT_READ_TIMEOUT_SECONDS = 30;

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(
                WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("UP status -> healthy outcome")
    void upStatusReturnsHealthy() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"UP\"}")));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome()).isEqualTo(HealthSnapshot.OUTCOME_HEALTHY);
        assertThat(result.detail()).isEqualTo("UP");
        assertThat(result.backend())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
        wireMock.verify(getRequestedFor(urlPathEqualTo(ACTUATOR_PATH)));
    }

    @Test
    @DisplayName("OUT_OF_SERVICE status -> degraded outcome")
    void outOfServiceStatusReturnsDegraded() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"OUT_OF_SERVICE\"}")));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome()).isEqualTo(HealthSnapshot.OUTCOME_DEGRADED);
        assertThat(result.detail()).isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    @DisplayName("DOWN status -> unhealthy outcome")
    void downStatusReturnsUnhealthy() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"DOWN\"}")));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome()).isEqualTo(HealthSnapshot.OUTCOME_UNHEALTHY);
        assertThat(result.detail()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("unknown status -> degraded with unknown:<raw>")
    void unknownStatusReturnsDegradedUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"MAYBE\"}")));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome()).isEqualTo(HealthSnapshot.OUTCOME_DEGRADED);
        assertThat(result.detail()).isEqualTo("unknown:MAYBE");
    }

    @Test
    @DisplayName("missing status field -> degraded unknown:no-status-field")
    void missingStatusFieldReturnsDegradedUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"foo\":\"bar\"}")));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome()).isEqualTo(HealthSnapshot.OUTCOME_DEGRADED);
        assertThat(result.detail()).isEqualTo("unknown:no-status-field");
    }

    @Test
    @DisplayName("404 -> permanent eureka-actuator:4xx:404")
    void notFoundReturnsPermanent4xx() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("eureka-actuator:4xx:404");
    }

    @Test
    @DisplayName("429 -> transient eureka-actuator:429")
    void rateLimitedReturnsTransient429() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withStatus(429).withBody("slow down")));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("eureka-actuator:429");
    }

    @Test
    @DisplayName("500 -> transient eureka-actuator:5xx:500")
    void serverErrorReturnsTransient5xx() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("eureka-actuator:5xx:500");
    }

    @Test
    @DisplayName("CONNECTION_RESET_BY_PEER -> transient eureka-actuator:transport (LD120)")
    void connectionResetReturnsTransientTransport() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PATH))
                .willReturn(aResponse().withFault(
                        Fault.CONNECTION_RESET_BY_PEER)));

        final HealthSnapshot result = adapter().probe(
                new ProbeRequest(SERVICE_ID, INSTANCE_ID));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason())
                .isIn("eureka-actuator:transport",
                        "eureka-actuator:timeout");
    }

    // -- helpers --------------------------------------------------------

    /**
     * Build the adapter wired with the documented
     * {@link RestClient} shape pointing at the local WireMock
     * server. Mirror of {@code QuickwitHttpAdminWireMockIT.adapter()}.
     */
    private EurekaActuatorHealthProbe adapter() {
        final EurekaActuatorProperties props = new EurekaActuatorProperties(
                Duration.ofSeconds(IT_READ_TIMEOUT_SECONDS),
                ACTUATOR_PATH);
        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(Duration.ofSeconds(2))
                                .build());
        factory.setReadTimeout(Duration.ofSeconds(IT_READ_TIMEOUT_SECONDS));
        final RestClient client = RestClient.builder()
                .requestFactory(factory)
                .build();
        final MonitoringMetrics metrics = new MonitoringMetrics(
                new SimpleMeterRegistry(), List.of());
        final DiscoveryClient discovery = new StubDiscoveryClient(
                List.of(instance(INSTANCE_ID, URI.create(wireMock.baseUrl()))));
        return new EurekaActuatorHealthProbe(
                discovery, client, props, metrics, new ObjectMapper());
    }

    private static ServiceInstance instance(final String instanceId,
                                            final URI uri) {
        return new DefaultServiceInstance(instanceId, SERVICE_ID,
                uri.getHost(), uri.getPort(), false, Map.of());
    }

    /** Stub DiscoveryClient that returns a single, fixed instance list. */
    private static final class StubDiscoveryClient implements DiscoveryClient {

        private final List<ServiceInstance> instances;

        StubDiscoveryClient(final List<ServiceInstance> instances) {
            this.instances = instances;
        }

        @Override public String description() {
            return "wiremock-stub";
        }

        @Override public List<ServiceInstance> getInstances(
                final String serviceId) {
            return new ArrayList<>(this.instances);
        }

        @Override public List<String> getServices() {
            return Collections.emptyList();
        }
    }
}
