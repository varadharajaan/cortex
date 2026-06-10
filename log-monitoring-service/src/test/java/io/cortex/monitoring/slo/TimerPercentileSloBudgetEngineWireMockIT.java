package io.cortex.monitoring.slo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
 * {@link TimerPercentileSloBudgetEngine} (P8.4).
 */
@DisplayName("TimerPercentileSloBudgetEngine WireMock IT")
class TimerPercentileSloBudgetEngineWireMockIT {

    private static final String SERVICE_ID = "log-gateway";
    private static final String INSTANCE_ID = "log-gateway:p8-4";
    private static final String ACTUATOR_PROMETHEUS = "/actuator/prometheus";
    private static final String METRIC = "http.server.requests.seconds";

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
    @DisplayName("scraped timer histogram derives latency SLO snapshot")
    void scrapedTimerHistogramDerivesSnapshot() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(timerMetrics())));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.backend())
                .isEqualTo(SloSnapshot.BACKEND_TIMER_PERCENTILE);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.4d, within(1e-9d));
        assertThat(snap.burnRate()).isEqualTo(0.6d, within(1e-9d));
        wireMock.verify(getRequestedFor(urlPathEqualTo(ACTUATOR_PROMETHEUS)));
    }

    @Test
    @DisplayName("no matching histogram buckets returns unknown no-data")
    void noMatchingBucketsReturnsUnknown() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("other_bucket{le=\"+Inf\"} 1\n")));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_UNKNOWN);
        assertThat(snap.reason()).isEqualTo("timer-percentile:no-data");
    }

    @Test
    @DisplayName("missing timer source is permanent misconfiguration")
    void missingTimerSourceIsPermanentFailure() {
        final SloSnapshot snap = adapter().evaluate(new SloDefinition(
                SERVICE_ID, "availability", 0.99d, Duration.ofHours(1)));

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(snap.reason())
                .isEqualTo("timer-percentile:missing-timer-source");
    }

    @Test
    @DisplayName("HTTP 500 maps to transient failure")
    void http500MapsToTransientFailure() {
        wireMock.stubFor(get(urlPathEqualTo(ACTUATOR_PROMETHEUS))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(snap.reason()).isEqualTo("timer-percentile:5xx:500");
    }

    private TimerPercentileSloBudgetEngine adapter() {
        return new TimerPercentileSloBudgetEngine(
                new StubDiscoveryClient(List.of(instance())),
                restClient(),
                new TimerPercentileSloProperties(Duration.ofSeconds(30),
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
        return new SloDefinition(SERVICE_ID, "gateway-route-latency-p95",
                0.90d, Duration.ofMinutes(5),
                null,
                new SloDefinition.TimerSource(METRIC,
                        Duration.ofMillis(300),
                        Map.of("uri", "/api/v1/logs")),
                null, null, null);
    }

    private static String timerMetrics() {
        return """
                # TYPE http_server_requests_seconds histogram
                http_server_requests_seconds_bucket{uri="/api/v1/logs",method="POST",le="0.1"} 600
                http_server_requests_seconds_bucket{uri="/api/v1/logs",method="POST",le="0.3"} 940
                http_server_requests_seconds_bucket{uri="/api/v1/logs",method="POST",le="1.0"} 980
                http_server_requests_seconds_bucket{uri="/api/v1/logs",method="POST",le="+Inf"} 1000
                http_server_requests_seconds_bucket{uri="/ignore",method="POST",le="0.3"} 0
                http_server_requests_seconds_bucket{uri="/ignore",method="POST",le="+Inf"} 1000
                """;
    }

    private static final class StubDiscoveryClient implements DiscoveryClient {

        private final List<ServiceInstance> instances;

        StubDiscoveryClient(final List<ServiceInstance> instances) {
            this.instances = instances;
        }

        @Override
        public String description() {
            return "p8-4-timer-stub-discovery-client";
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
