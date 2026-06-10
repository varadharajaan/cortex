package io.cortex.monitoring.slo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cortex.monitoring.slo.SloDefinition.CounterFamilySource;
import io.cortex.monitoring.slo.SloDefinition.TagPredicate;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * WireMock-driven integration tests for
 * {@link CounterFamilySloBudgetEngine} (P8.3).
 */
@DisplayName("CounterFamilySloBudgetEngine WireMock IT")
class CounterFamilySloBudgetEngineWireMockIT {

    private static final String SERVICE_ID = "log-remediation-service";
    private static final String INSTANCE_ID = "log-remediation-service:p8-3";
    private static final String ACTUATOR_PROMETHEUS = "/actuator/prometheus";
    private static final String METRIC = "cortex.remediation.dispatched_total";

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
    void resetWireMock() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("scraped counter family derives at-risk SLO snapshot")
    void scrapedCounterFamilyDerivesSnapshot() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(remediationDispatchMetrics())));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.backend())
                .isEqualTo(SloSnapshot.BACKEND_COUNTER_FAMILY);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.5d, within(1e-9d));
        assertThat(snap.burnRate()).isEqualTo(0.5d, within(1e-9d));
        wireMock.verify(getRequestedFor(urlPathEqualTo(ACTUATOR_PROMETHEUS)));
    }

    @Test
    @DisplayName("no matching counter samples returns unknown no-data")
    void noMatchingSamplesReturnsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("cortex_other_total{outcome=\"ok\"} 1\n")));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_UNKNOWN);
        assertThat(snap.reason()).isEqualTo("counter-family:no-data");
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(SloSnapshot.UNKNOWN_BUDGET_REMAINING);
    }

    @Test
    @DisplayName("missing counter-family source is permanent misconfiguration")
    void missingCounterFamilySourceIsPermanentFailure() {
        final SloDefinition availabilityDef = new SloDefinition(
                SERVICE_ID, "availability", 0.99d, Duration.ofHours(1), null);

        final SloSnapshot snap = adapter().evaluate(availabilityDef);

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(snap.reason())
                .isEqualTo("counter-family:missing-counter-family-source");
    }

    @Test
    @DisplayName("missing discovery instance is transient no-instance")
    void missingDiscoveryInstanceIsTransientFailure() {
        final CounterFamilySloBudgetEngine engine = new CounterFamilySloBudgetEngine(
                new StubDiscoveryClient(List.of()), restClient(), properties());

        final SloSnapshot snap = engine.evaluate(def());

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(snap.reason()).isEqualTo("counter-family:no-instance");
    }

    @Test
    @DisplayName("HTTP 500 maps to transient failure")
    void http500MapsToTransientFailure() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(snap.reason()).isEqualTo("counter-family:5xx:500");
    }

    @Test
    @DisplayName("HTTP 404 maps to permanent failure")
    void http404MapsToPermanentFailure() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(404).withBody("missing")));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(snap.reason()).isEqualTo("counter-family:4xx:404");
    }

    private CounterFamilySloBudgetEngine adapter() {
        return new CounterFamilySloBudgetEngine(
                new StubDiscoveryClient(List.of(instance())),
                restClient(), properties());
    }

    private static CounterFamilySloProperties properties() {
        return new CounterFamilySloProperties(Duration.ofSeconds(30),
                ACTUATOR_PROMETHEUS);
    }

    private static RestClient restClient() {
        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(Duration.ofSeconds(2))
                                .build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    private static ServiceInstance instance() {
        final URI uri = URI.create(wireMock.baseUrl());
        return new DefaultServiceInstance(INSTANCE_ID, SERVICE_ID,
                uri.getHost(), uri.getPort(), false, Map.of());
    }

    private static SloDefinition def() {
        return new SloDefinition(SERVICE_ID, "slack-dispatch-success",
                0.99d, Duration.ofHours(1), source());
    }

    private static CounterFamilySource source() {
        return new CounterFamilySource(
                METRIC,
                new TagPredicate("outcome", List.of("dispatched")),
                new TagPredicate("outcome",
                        List.of("transient_failure", "permanent_failure")),
                Map.of("channel", "slack"));
    }

    private static String remediationDispatchMetrics() {
        return """
                # HELP cortex_remediation_dispatched_total dispatches
                # TYPE cortex_remediation_dispatched_total counter
                cortex_remediation_dispatched_total{channel="slack",outcome="dispatched",tenant_id="unknown"} 995
                cortex_remediation_dispatched_total{channel="slack",outcome="transient_failure",tenant_id="unknown"} 3
                cortex_remediation_dispatched_total{channel="slack",outcome="permanent_failure",tenant_id="unknown"} 2
                cortex_remediation_dispatched_total{channel="jira",outcome="permanent_failure",tenant_id="unknown"} 1000
                """;
    }

    private static final class StubDiscoveryClient implements DiscoveryClient {

        private final List<ServiceInstance> instances;

        StubDiscoveryClient(final List<ServiceInstance> instances) {
            this.instances = instances;
        }

        @Override
        public String description() {
            return "p8-3-counter-family-stub-discovery-client";
        }

        @Override
        public List<ServiceInstance> getInstances(final String serviceId) {
            if (SERVICE_ID.equals(serviceId)) {
                return this.instances;
            }
            return List.of();
        }

        @Override
        public List<String> getServices() {
            return Collections.emptyList();
        }
    }
}
