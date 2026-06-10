package io.cortex.monitoring.slo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
 * {@link OtelSloBudgetEngine} (P8.7+).
 */
@DisplayName("OtelSloBudgetEngine WireMock IT")
class OtelSloBudgetEngineWireMockIT {

    private static final String SERVICE_ID = "log-gateway";
    private static final String INSTANCE_ID = "log-gateway:p8-7";
    private static final String ACTUATOR_PROMETHEUS = "/actuator/prometheus";
    private static final String METRIC = "otel.span.server.requests_total";

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
    @DisplayName("scraped OTel span metric derives SLO snapshot")
    void scrapedOtelSpanMetricDerivesSnapshot() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(otelMetrics())));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.backend()).isEqualTo(SloSnapshot.BACKEND_OTEL);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.5d, within(1e-9d));
        assertThat(snap.burnRate()).isEqualTo(0.5d, within(1e-9d));
    }

    @Test
    @DisplayName("no matching OTel samples returns unknown no-data")
    void noMatchingOtelSamplesReturnsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("other_total{status_code=\"OK\"} 1\n")));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_UNKNOWN);
        assertThat(snap.reason()).isEqualTo("otel:no-data");
    }

    @Test
    @DisplayName("missing OTel source is permanent misconfiguration")
    void missingOtelSourceIsPermanentFailure() {
        final SloSnapshot snap = adapter().evaluate(new SloDefinition(
                SERVICE_ID, "availability", 0.99d, Duration.ofHours(1)));

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(snap.reason()).isEqualTo("otel:missing-otel-source");
    }

    private OtelSloBudgetEngine adapter() {
        return new OtelSloBudgetEngine(
                new StubDiscoveryClient(List.of(instance())),
                restClient(),
                new OtelSloProperties(Duration.ofSeconds(30),
                        ACTUATOR_PROMETHEUS));
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
        return new SloDefinition(SERVICE_ID, "server-span-success",
                0.98d, Duration.ofMinutes(5),
                null, null, null, null,
                new SloDefinition.OtelSource(
                        METRIC,
                        new SloDefinition.TagPredicate("status_code",
                                List.of("OK")),
                        new SloDefinition.TagPredicate("status_code",
                                List.of("ERROR")),
                        Map.of("span_kind", "server")));
    }

    private static String otelMetrics() {
        return """
                # TYPE otel_span_server_requests_total counter
                otel_span_server_requests_total{span_kind="server",status_code="OK"} 990
                otel_span_server_requests_total{span_kind="server",status_code="ERROR"} 10
                otel_span_server_requests_total{span_kind="client",status_code="ERROR"} 100
                """;
    }

    private static final class StubDiscoveryClient implements DiscoveryClient {

        private final List<ServiceInstance> instances;

        StubDiscoveryClient(final List<ServiceInstance> instances) {
            this.instances = instances;
        }

        @Override
        public String description() {
            return "p8-7-otel-stub-discovery-client";
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
