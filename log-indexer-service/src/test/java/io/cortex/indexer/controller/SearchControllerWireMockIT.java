package io.cortex.indexer.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;

/**
 * Failsafe end-to-end IT for the P9.1a search REST surface
 * (ADR-0042 Amendment 1).
 *
 * <p>Boots the full Spring context on a random port with BOTH
 * Quickwit binder gates flipped ON
 * ({@code cortex.indexer.admin.backend=quickwit} +
 * {@code cortex.indexer.search.backend=quickwit}; the search
 * adapter consumes the admin-gated {@code RestClient} bean) and
 * points the shared Quickwit base URL at a singleton in-process
 * WireMock via {@code @DynamicPropertySource}. It then drives the
 * HTTP controller with {@link TestRestTemplate} so what is
 * exercised is the real path
 * {@code SearchController -> QuickwitHttpSearch -> (WireMock)
 * Quickwit}, proving the outcome-&gt;HTTP mapping end-to-end that
 * the {@code @WebMvcTest} slice (mocked SPI) cannot.</p>
 *
 * <p>Mirrors {@code QuickwitCrossPhaseIT}: singleton WireMock
 * started in a static initialiser so its dynamic port is known when
 * the {@link DynamicPropertySource} lambda fires; stubs + journal
 * reset before each test; {@code request-timeout=30s} per LD123 so
 * the JIT-cold first request does not trip the transport
 * classifier.</p>
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "cortex.indexer.admin.backend=quickwit",
                "cortex.indexer.search.backend=quickwit",
                "cortex.indexer.quickwit.request-timeout=30s",
                "cortex.indexer.quickwit.doc-mapping-version=v1",
                "eureka.client.enabled=false",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("SearchController WireMock IT (P9.1a search REST end-to-end)")
class SearchControllerWireMockIT {

    private static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    private static final String TENANT_ID = "tenant-IT";
    private static final String INDEX_ID = "cortex-tenant-IT-v1";
    private static final String QUICKWIT_SEARCH_PATH =
            "/api/v1/" + INDEX_ID + "/search";
    private static final String SEARCH_URL = "/api/v1/search";

    static {
        WIRE_MOCK.start();
    }

    /**
     * Wires the shared WireMock base URL into the autowired Quickwit
     * {@code RestClient} and disables Eureka for the embedded context.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void baseProperties(final DynamicPropertyRegistry registry) {
        registry.add("cortex.indexer.quickwit.base-url", WIRE_MOCK::baseUrl);
    }

    private final TestRestTemplate rest;

    SearchControllerWireMockIT(final TestRestTemplate rest) {
        this.rest = rest;
    }

    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    @Test
    void happySearchReturns200WithHits() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(QUICKWIT_SEARCH_PATH))
                .willReturn(aResponse().withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("{\"num_hits\":2,\"hits\":["
                                + "{\"message\":\"boom\"},{\"message\":\"bang\"}]}")));

        final ResponseEntity<String> response = postSearch(TENANT_ID,
                body(INDEX_ID, "level:ERROR", 10));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"numHits\":2").contains("boom");
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(QUICKWIT_SEARCH_PATH)));
    }

    @Test
    void missingIndexReturns404() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(QUICKWIT_SEARCH_PATH))
                .willReturn(aResponse().withStatus(HttpStatus.NOT_FOUND.value())));

        final ResponseEntity<String> response = postSearch(TENANT_ID,
                body(INDEX_ID, "*", 5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void tenantMismatchReturns403WithoutContactingQuickwit() {
        final ResponseEntity<String> response = postSearch(TENANT_ID,
                body("cortex-other-v1", "*", 5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(
                "/api/v1/cortex-other-v1/search")));
    }

    @Test
    void downstream5xxReturns503() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(QUICKWIT_SEARCH_PATH))
                .willReturn(aResponse().withStatus(
                        HttpStatus.INTERNAL_SERVER_ERROR.value())));

        final ResponseEntity<String> response = postSearch(TENANT_ID,
                body(INDEX_ID, "*", 5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private ResponseEntity<String> postSearch(final String tenantId,
            final String bodyJson) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", tenantId);
        return this.rest.exchange(SEARCH_URL, HttpMethod.POST,
                new HttpEntity<>(bodyJson, headers), String.class);
    }

    private static String body(final String indexId, final String query,
            final int maxHits) {
        return "{\"indexId\":\"" + indexId + "\",\"query\":\"" + query
                + "\",\"maxHits\":" + maxHits + "}";
    }
}
