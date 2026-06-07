package io.cortex.indexer.search.quickwit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import io.cortex.indexer.admin.quickwit.QuickwitProperties;
import io.cortex.indexer.metrics.IndexerMetrics;
import io.cortex.indexer.search.SearchRequest;
import io.cortex.indexer.search.SearchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * WireMock-driven integration test for {@link QuickwitHttpSearch}
 * (P7.4 / ADR-0042; failsafe pass; LD120 deterministic
 * transport fault).
 *
 * <p>Boots an in-process {@link WireMockServer} on a dynamic port,
 * stubs the Quickwit
 * {@code POST /api/v1/{indexId}/search} surface with the full set
 * of outcome stubs (200 happy with hit list, 404 missing index,
 * 500 transient, 429 rate-limited) plus one
 * {@link Fault#CONNECTION_RESET_BY_PEER} transport-fault injection
 * per LD120, and asserts the adapter's HTTP outcome -&gt;
 * {@link SearchResult} mapping end-to-end against a real HTTP
 * server (the Mockito-free unit class
 * {@link QuickwitHttpSearchTest} only verifies the guard rails +
 * body shape).</p>
 *
 * <p>The {@link RestClient} the adapter consumes is constructed
 * locally with the same HTTP/1.1 pin (LD42) +
 * {@link JdkClientHttpRequestFactory} the production
 * {@code QuickwitHttpConfig#quickwitAdminRestClient} bean uses,
 * so what the IT exercises is wire-format identical to what
 * production sends. LD123 cold-start read-timeout bump applies.</p>
 */
@DisplayName("QuickwitHttpSearch WireMock IT")
class QuickwitHttpSearchWireMockIT {

    /** Tenant id reused across every test. */
    private static final String TENANT_ID = "tenant-IT";

    /** Index id reused across every test (carries the canonical prefix). */
    private static final String INDEX_ID = "cortex-tenant-IT-v1";

    /** Quickwit search path for the canonical IT index. */
    private static final String SEARCH_PATH =
            "/api/v1/" + INDEX_ID + "/search";

    /** Doc-mapping schema version (carried by QuickwitProperties only). */
    private static final String DOC_MAPPING_VERSION = "v1";

    /**
     * Read timeout for the IT-local {@link RestClient}. Bumped from
     * production's 5 s default per LD123 so the first JIT-cold
     * request through the JDK {@link HttpClient} + WireMock stack
     * does not trip the transport-failure classifier.
     */
    private static final int IT_READ_TIMEOUT_SECONDS = 30;

    private static WireMockServer wireMock;

    /** Boots WireMock on a dynamic port shared across all tests in this class. */
    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(
                WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    /** Shuts WireMock down cleanly after the class finishes. */
    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    /** Resets WireMock stubs + request journal between tests. */
    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    /** A representative non-null request reused by every test. */
    private static SearchRequest sampleRequest() {
        return new SearchRequest(TENANT_ID, INDEX_ID, "level:ERROR", 10);
    }

    /**
     * Build the adapter wired with the documented Quickwit
     * {@link RestClient} shape pointing at the local WireMock
     * server. Mirror of the {@link
     * io.cortex.indexer.admin.quickwit.QuickwitHttpAdminWireMockIT}
     * setup helper.
     */
    private QuickwitHttpSearch adapter() {
        final QuickwitProperties props = new QuickwitProperties(
                wireMock.baseUrl(),
                Duration.ofSeconds(IT_READ_TIMEOUT_SECONDS),
                DOC_MAPPING_VERSION);
        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(IT_READ_TIMEOUT_SECONDS));
        final RestClient client = RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .requestFactory(factory)
                .build();
        final IndexerMetrics metrics = new IndexerMetrics(
                new SimpleMeterRegistry(), List.of(), List.of());
        return new QuickwitHttpSearch(props, client, metrics, new ObjectMapper());
    }

    @Test
    void searchReturnsHitsOn200() {
        wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"num_hits\":2,\"hits\":["
                                + "{\"message\":\"first hit\",\"level\":\"ERROR\"},"
                                + "{\"message\":\"second hit\",\"level\":\"ERROR\"}"
                                + "]}")));

        final SearchResult result = adapter().search(sampleRequest());

        assertThat(result.outcome())
                .isEqualTo(SearchResult.OUTCOME_SEARCH_OK);
        assertThat(result.backend()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
        assertThat(result.numHits()).isEqualTo(2L);
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0))
                .containsEntry("message", "first hit")
                .containsEntry("level", "ERROR");

        wireMock.verify(postRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withRequestBody(matchingJsonPath("$.query",
                        equalTo("level:ERROR")))
                .withRequestBody(matchingJsonPath("$.max_hits",
                        equalTo("10"))));
    }

    @Test
    void searchReturnsPermanentFailureOn404() {
        wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(404)
                        .withBody("index not found")));

        final SearchResult result = adapter().search(sampleRequest());

        assertThat(result.outcome())
                .isEqualTo(SearchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:404");
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void searchReturnsTransientFailureOn500() {
        wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(500)
                        .withBody("boom")));

        final SearchResult result = adapter().search(sampleRequest());

        assertThat(result.outcome())
                .isEqualTo(SearchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:5xx:500");
    }

    @Test
    void searchReturnsTransientFailureOn429() {
        wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(429)
                        .withBody("rate limited")));

        final SearchResult result = adapter().search(sampleRequest());

        assertThat(result.outcome())
                .isEqualTo(SearchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:429");
    }

    @Test
    void searchClassifiesConnectionResetAsTransient() {
        // Per LD120: WireMock Fault.CONNECTION_RESET_BY_PEER
        // surfaces as a JDK transport error -- the adapter's
        // classifier maps it to transient {timeout, transport}.
        wireMock.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withFault(
                        Fault.CONNECTION_RESET_BY_PEER)));

        final SearchResult result = adapter().search(sampleRequest());

        assertThat(result.outcome())
                .isEqualTo(SearchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason())
                .isIn("quickwit:timeout", "quickwit:transport");
    }
}
