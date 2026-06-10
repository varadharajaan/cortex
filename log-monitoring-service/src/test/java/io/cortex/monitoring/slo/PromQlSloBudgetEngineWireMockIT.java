package io.cortex.monitoring.slo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * WireMock-driven integration tests for
 * {@link PromQlSloBudgetEngine} (P8.5).
 */
@DisplayName("PromQlSloBudgetEngine WireMock IT")
class PromQlSloBudgetEngineWireMockIT {

    private static final String SERVICE_ID = "log-indexer-service";
    private static final String QUERY_PATH = "/api/v1/query";
    private static final String SUCCESS_QUERY = "sum(success_total)";
    private static final String FAILURE_QUERY = "sum(failure_total)";

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
    @DisplayName("PromQL vector and scalar results derive SLO snapshot")
    void promQlResultsDeriveSnapshot() {
        stubQuery(SUCCESS_QUERY, vectorResponse("900", "40"));
        stubQuery(FAILURE_QUERY, scalarResponse("60"));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.backend()).isEqualTo(SloSnapshot.BACKEND_PROMQL);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.4d, within(1e-9d));
        assertThat(snap.burnRate()).isEqualTo(0.6d, within(1e-9d));
    }

    @Test
    @DisplayName("empty PromQL results return unknown no-data")
    void emptyPromQlResultsReturnUnknown() {
        stubQuery(SUCCESS_QUERY, emptyVectorResponse());
        stubQuery(FAILURE_QUERY, emptyVectorResponse());

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_UNKNOWN);
        assertThat(snap.reason()).isEqualTo("promql:no-data");
    }

    @Test
    @DisplayName("Prometheus API error maps to transient failure")
    void prometheusApiErrorMapsToTransientFailure() {
        stubQuery(SUCCESS_QUERY, "{\"status\":\"error\"}");

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(snap.reason()).isEqualTo("promql:api-error");
    }

    @Test
    @DisplayName("HTTP 500 maps to transient failure")
    void http500MapsToTransientFailure() {
        wireMock.stubFor(get(urlPathEqualTo(QUERY_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final SloSnapshot snap = adapter().evaluate(def());

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(snap.reason()).isEqualTo("promql:5xx:500");
    }

    private static void stubQuery(final String query, final String body) {
        wireMock.stubFor(get(urlPathEqualTo(QUERY_PATH))
                .withQueryParam("query", equalTo(query))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private PromQlSloBudgetEngine adapter() {
        final PromQlSloProperties props = new PromQlSloProperties(
                Duration.ofSeconds(30), URI.create(wireMock.baseUrl()));
        return new PromQlSloBudgetEngine(restClient(props), new ObjectMapper());
    }

    private static RestClient restClient(final PromQlSloProperties props) {
        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(Duration.ofSeconds(2))
                                .build());
        factory.setReadTimeout(props.requestTimeout());
        return RestClient.builder()
                .baseUrl(props.baseUrl().toString())
                .requestFactory(factory)
                .build();
    }

    private static SloDefinition def() {
        return new SloDefinition(SERVICE_ID, "quickwit-search-success",
                0.90d, Duration.ofMinutes(5),
                null, null,
                new SloDefinition.PromQlSource(
                        SUCCESS_QUERY, FAILURE_QUERY),
                null, null);
    }

    private static String vectorResponse(final String first,
                                         final String second) {
        return """
                {
                  "status": "success",
                  "data": {
                    "resultType": "vector",
                    "result": [
                      {"metric": {"job": "a"}, "value": [1, "%s"]},
                      {"metric": {"job": "b"}, "value": [1, "%s"]}
                    ]
                  }
                }
                """.formatted(first, second);
    }

    private static String scalarResponse(final String value) {
        return """
                {
                  "status": "success",
                  "data": {
                    "resultType": "scalar",
                    "result": [1, "%s"]
                  }
                }
                """.formatted(value);
    }

    private static String emptyVectorResponse() {
        return """
                {
                  "status": "success",
                  "data": {
                    "resultType": "vector",
                    "result": []
                  }
                }
                """;
    }
}
