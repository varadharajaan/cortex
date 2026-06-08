package io.cortex.gateway.service.impl;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cortex.gateway.config.SearchLogsProperties;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.request.LogSearchRequest;
import io.cortex.gateway.dto.response.LogSearchResult;
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
 * Unit test for {@link SearchLogsServiceImpl} (P9.1b / ADR-0049).
 *
 * <p>Drives the real path {@code SearchLogsServiceImpl -> RestClient ->
 * (WireMock) indexer} with a mocked {@link LoadBalancerClient} that
 * resolves the discovery id to the in-process WireMock port, so the
 * downstream-status-&gt;{@link ApplicationException} mapping and the
 * {@code X-Tenant-Id} header / body forwarding are exercised end to
 * end. A singleton WireMock with a generous read timeout (LD123)
 * avoids cold-start transport flakes.</p>
 */
class SearchLogsServiceImplTest {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    private static final String SERVICE_ID = "log-indexer-service";
    private static final String SEARCH_PATH = "/api/v1/search";
    private static final String TENANT = "tenant-1";
    private static final String INDEX = "cortex-tenant-1-logs";

    private LoadBalancerClient loadBalancerClient;
    private SearchLogsServiceImpl service;

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
        final SearchLogsProperties properties = new SearchLogsProperties(
                true, SERVICE_ID, Duration.ofSeconds(30), 50, 1000, 30L, Duration.ofMinutes(1),
                "cortex:rl:search:");
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        final RestClient restClient = RestClient.builder().requestFactory(factory).build();

        this.loadBalancerClient = mock(LoadBalancerClient.class);
        final ServiceInstance instance = new DefaultServiceInstance(
                "indexer-1", SERVICE_ID, "localhost", WIRE_MOCK.port(), false);
        when(this.loadBalancerClient.choose(SERVICE_ID)).thenReturn(instance);

        this.service = new SearchLogsServiceImpl(this.loadBalancerClient, restClient, properties);
    }

    @Test
    void happyPathReturnsResultAndForwardsTenantHeaderAndBody() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"numHits\":2,\"hits\":[{\"message\":\"boom\"},{\"message\":\"bang\"}]}")));

        final LogSearchResult result = this.service.search(
                new LogSearchRequest(INDEX, "level:ERROR", 10), TENANT);

        assertThat(result.numHits()).isEqualTo(2L);
        assertThat(result.hits()).hasSize(2);
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withHeader("X-Tenant-Id", equalTo(TENANT))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.indexId", equalTo(INDEX)))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.maxHits", equalTo("10"))));
    }

    @Test
    void omitsMaxHitsWhenNull() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"numHits\":0,\"hits\":[]}")));

        final LogSearchResult result = this.service.search(
                new LogSearchRequest(INDEX, "*", null), TENANT);

        assertThat(result.numHits()).isZero();
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$[?(!@.maxHits)]")));
    }

    @Test
    void forbiddenMapsToForbidden() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> this.service.search(new LogSearchRequest(INDEX, "*", null), TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.FORBIDDEN);
    }

    @Test
    void notFoundMapsToNotFound() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> this.service.search(new LogSearchRequest(INDEX, "*", null), TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.NOT_FOUND);
    }

    @Test
    void unprocessableMapsToSearchLogsInvalid() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(422)));

        assertThatThrownBy(() -> this.service.search(new LogSearchRequest(INDEX, "*", null), TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.SEARCH_LOGS_INVALID);
    }

    @Test
    void serverErrorMapsToUpstreamFailed() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> this.service.search(new LogSearchRequest(INDEX, "*", null), TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.SEARCH_LOGS_UPSTREAM_FAILED);
    }

    @Test
    void noInstanceMapsToUpstreamFailed() {
        when(this.loadBalancerClient.choose(SERVICE_ID)).thenReturn(null);

        assertThatThrownBy(() -> this.service.search(new LogSearchRequest(INDEX, "*", null), TENANT))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.SEARCH_LOGS_UPSTREAM_FAILED);
    }

    @Test
    void blankTenantIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> this.service.search(new LogSearchRequest(INDEX, "*", null), "  "))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(SEARCH_PATH)));
    }
}
