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
import io.cortex.gateway.config.GetLogByIdProperties;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.LogEntry;
import io.cortex.gateway.exception.ApplicationException;
import java.net.http.HttpClient;
import java.time.Duration;
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
 * Unit test for {@link GetLogByIdServiceImpl} (P9.2b / ADR-0049).
 *
 * <p>Drives the real path {@code GetLogByIdServiceImpl -> RestClient ->
 * (WireMock) ingest} with a mocked {@link LoadBalancerClient} that
 * resolves the discovery id to the in-process WireMock port, so the
 * downstream-status mapping and the {@code X-Tenant-Id} header / path
 * forwarding are exercised end to end.</p>
 */
class GetLogByIdServiceImplTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    private static final String SERVICE_ID = "log-ingest-service";
    private static final String EVENT_ID = "evt-abc-123";
    private static final String PATH = "/api/v1/logs/" + EVENT_ID;
    private static final String TENANT = "tenant-1";

    private LoadBalancerClient loadBalancerClient;
    private GetLogByIdServiceImpl service;

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
        final GetLogByIdProperties properties = new GetLogByIdProperties(
                true, SERVICE_ID, Duration.ofSeconds(30), 60L, Duration.ofMinutes(1),
                "cortex:rl:getlog:");
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        final RestClient restClient = RestClient.builder().requestFactory(factory).build();

        this.loadBalancerClient = mock(LoadBalancerClient.class);
        final ServiceInstance instance = new DefaultServiceInstance(
                "ingest-1", SERVICE_ID, "localhost", WIRE_MOCK.port(), false);
        when(this.loadBalancerClient.choose(SERVICE_ID)).thenReturn(instance);

        this.service = new GetLogByIdServiceImpl(this.loadBalancerClient, restClient, properties);
    }

    @Test
    void happyPathReturnsEntryAndForwardsTenantHeader() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"eventId\":\"" + EVENT_ID + "\",\"tenantId\":\"" + TENANT
                                + "\",\"ts\":\"2026-06-09T10:00:00Z\",\"level\":\"ERROR\","
                                + "\"service\":\"payment-svc\",\"message\":\"boom\","
                                + "\"labels\":{\"env\":\"prod\"},\"receivedAt\":\"2026-06-09T10:00:01Z\"}")));

        final LogEntry entry = this.service.getLogById(EVENT_ID, TENANT);

        assertThat(entry.eventId()).isEqualTo(EVENT_ID);
        assertThat(entry.tenantId()).isEqualTo(TENANT);
        assertThat(entry.level()).isEqualTo("ERROR");
        assertThat(entry.labels()).containsEntry("env", "prod");
        WIRE_MOCK.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("X-Tenant-Id", equalTo(TENANT)));
    }

    @Test
    void notFoundMapsToNotFound() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> this.service.getLogById(EVENT_ID, TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.NOT_FOUND);
    }

    @Test
    void serverErrorMapsToUpstreamFailed() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> this.service.getLogById(EVENT_ID, TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.GET_LOG_BY_ID_UPSTREAM_FAILED);
    }

    @Test
    void noInstanceMapsToUpstreamFailed() {
        when(this.loadBalancerClient.choose(SERVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> this.service.getLogById(EVENT_ID, TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.GET_LOG_BY_ID_UPSTREAM_FAILED);
    }

    @Test
    void blankTenantIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> this.service.getLogById(EVENT_ID, "  "))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        WIRE_MOCK.verify(0, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void blankEventIdIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> this.service.getLogById("  ", TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
    }
}
