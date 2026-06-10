package io.cortex.gateway.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cortex.gateway.config.GetAnomaliesProperties;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.Anomaly;
import io.cortex.gateway.exception.ApplicationException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Unit test for {@link GetAnomaliesServiceImpl} (P9.3b / ADR-0049).
 *
 * <p>Drives the real path {@code GetAnomaliesServiceImpl -> RestClient ->
 * (WireMock) remediation} with a mocked {@link LoadBalancerClient} that
 * resolves the discovery id to the in-process WireMock port, so the
 * downstream-status mapping and the {@code tenantId} / filter query
 * forwarding are exercised end to end.</p>
 */
class GetAnomaliesServiceImplTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    private static final String SERVICE_ID = "log-remediation-service";
    private static final String PATH = "/api/v1/anomalies";
    private static final String TENANT = "tenant-1";

    private LoadBalancerClient loadBalancerClient;
    private GetAnomaliesServiceImpl service;

    @BeforeAll
    static void startWireMock() {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        final GetAnomaliesProperties properties = new GetAnomaliesProperties(
                true, SERVICE_ID, Duration.ofSeconds(30), 60L, Duration.ofMinutes(1),
                "cortex:rl:anom:");
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        final RestClient restClient = RestClient.builder().requestFactory(factory).build();

        this.loadBalancerClient = mock(LoadBalancerClient.class);
        final ServiceInstance instance = new DefaultServiceInstance(
                "remediation-1", SERVICE_ID, "localhost", WIRE_MOCK.port(), false);
        when(this.loadBalancerClient.choose(SERVICE_ID)).thenReturn(instance);

        this.service = new GetAnomaliesServiceImpl(this.loadBalancerClient, restClient, properties);
    }

    @Test
    void happyPathReturnsListAndForwardsTenantAndFilters() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"tenantId\":\"" + TENANT + "\",\"eventId\":\"evt-1\","
                                + "\"severity\":\"HIGH\",\"reason\":\"spike\","
                                + "\"ts\":\"2026-06-10T10:00:00Z\",\"level\":\"ERROR\","
                                + "\"service\":\"payment-svc\",\"message\":\"boom\","
                                + "\"confidence\":0.95,\"anomalyType\":\"latency\","
                                + "\"remediationKey\":\"pay-latency\","
                                + "\"receivedAt\":\"2026-06-10T10:00:01Z\"}]")));

        final List<Anomaly> result = this.service.getAnomalies(
                TENANT, "2026-06-10T00:00:00Z", "2026-06-10T23:59:59Z", 10);

        assertThat(result).hasSize(1);
        final Anomaly anomaly = result.get(0);
        assertThat(anomaly.eventId()).isEqualTo("evt-1");
        assertThat(anomaly.tenantId()).isEqualTo(TENANT);
        assertThat(anomaly.severity()).isEqualTo("HIGH");
        assertThat(anomaly.confidence()).isEqualTo(0.95);
        assertThat(anomaly.remediationKey()).isEqualTo("pay-latency");
        WIRE_MOCK.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("tenantId", equalTo(TENANT))
                .withQueryParam("since", equalTo("2026-06-10T00:00:00Z"))
                .withQueryParam("until", equalTo("2026-06-10T23:59:59Z"))
                .withQueryParam("limit", equalTo("10")));
    }

    @Test
    void emptyListIsAValidResultNotAMiss() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));

        final List<Anomaly> result = this.service.getAnomalies(TENANT, null, null, null);

        assertThat(result).isEmpty();
        WIRE_MOCK.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("tenantId", equalTo(TENANT)));
    }

    @Test
    void clientErrorMapsToInvalid() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> this.service.getAnomalies(TENANT, "bad-since", null, null))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.GET_ANOMALIES_INVALID);
    }

    @Test
    void serverErrorMapsToUpstreamFailed() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> this.service.getAnomalies(TENANT, null, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.GET_ANOMALIES_UPSTREAM_FAILED);
    }

    @Test
    void noInstanceMapsToUpstreamFailed() {
        when(this.loadBalancerClient.choose(SERVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> this.service.getAnomalies(TENANT, null, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.GET_ANOMALIES_UPSTREAM_FAILED);
    }

    @Test
    void blankTenantIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> this.service.getAnomalies("  ", null, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void nonPositiveLimitIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> this.service.getAnomalies(TENANT, null, null, 0))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }
}
