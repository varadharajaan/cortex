package io.cortex.indexer.admin.quickwit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.admin.IndexSpec;
import io.cortex.indexer.admin.RetentionPolicy;
import io.cortex.indexer.metrics.IndexerMetrics;
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
 * WireMock-driven integration test for {@link QuickwitHttpAdmin}
 * (P7.1 / ADR-0039 D3 outcome table; failsafe pass; LD120
 * deterministic transport fault).
 *
 * <p>Boots an in-process {@link WireMockServer} on a dynamic port,
 * stubs the Quickwit {@code /api/v1/indexes} admin surface with the
 * full set of outcome stubs called out in ADR-0039 D3 (200/201
 * happy paths, 429 transient, 500 transient, 401 / 400 / 403
 * permanent), plus one {@link Fault#CONNECTION_RESET_BY_PEER}
 * transport-fault injection per LD120, and asserts the adapter's
 * HTTP outcome -&gt; {@link IndexAdminResult} mapping end-to-end
 * against a real HTTP server (the Mockito-free unit class
 * {@link QuickwitHttpAdminTest} only verifies the guard rails +
 * body shape).</p>
 *
 * <p>The {@link RestClient} the adapter consumes is constructed
 * locally with the same HTTP/1.1 pin (LD42) +
 * {@link JdkClientHttpRequestFactory} the production
 * {@link QuickwitHttpConfig#quickwitAdminRestClient(QuickwitProperties)}
 * bean uses, so what the IT exercises is wire-format identical to
 * what production sends. LD123 cold-start read-timeout bump
 * applies.</p>
 */
@DisplayName("QuickwitHttpAdmin WireMock IT")
class QuickwitHttpAdminWireMockIT {

    /** Quickwit cluster-level admin path (POST create + GET list). */
    private static final String INDEXES_PATH = "/api/v1/indexes";

    /** Quickwit per-index admin path (GET get + DELETE drop). */
    private static final String INDEX_PATH_PREFIX = "/api/v1/indexes/";

    /** Quickwit Delete API path for {@link RetentionPolicy} application (P7.2). */
    private static final String DELETE_TASKS_PATH =
            "/api/v1/" + "cortex-tenantIT-v1" + "/delete-tasks";

    /** Tenant id reused across every test. */
    private static final String TENANT_ID = "tenant-IT";

    /** Index id reused across every test. */
    private static final String INDEX_ID = "cortex-tenantIT-v1";

    /** Doc-mapping schema version reused across every test. */
    private static final String DOC_MAPPING_VERSION = "v1";

    /**
     * Read timeout for the IT-local {@link RestClient}. Bumped from
     * production's 5 s default per LD123 so the first JIT-cold
     * request through the JDK {@link HttpClient} + WireMock stack
     * does not trip the transport-failure classifier when this IT
     * sorts first in Failsafe's alphabetical run order.
     */
    private static final int IT_READ_TIMEOUT_SECONDS = 30;

    private static WireMockServer wireMock;

    /** Boots WireMock on a dynamic port shared across all tests in this class. */
    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
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

    /** A representative non-null spec reused by every test. */
    private static IndexSpec sampleSpec() {
        return new IndexSpec(TENANT_ID, INDEX_ID, DOC_MAPPING_VERSION);
    }

    /**
     * Build the adapter wired with the documented Quickwit
     * {@link RestClient} shape pointing at the local WireMock
     * server. Mirror of the {@code SlackRemediationDispatcherWireMockIT}
     * pattern.
     */
    private QuickwitHttpAdmin adapter() {
        final QuickwitProperties props = new QuickwitProperties(
                wireMock.baseUrl(),
                Duration.ofSeconds(IT_READ_TIMEOUT_SECONDS),
                DOC_MAPPING_VERSION);
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(IT_READ_TIMEOUT_SECONDS));
        final RestClient client = RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .requestFactory(factory)
                .build();
        final IndexerMetrics metrics = new IndexerMetrics(
                new SimpleMeterRegistry(), List.of());
        return new QuickwitHttpAdmin(props, client, metrics, new ObjectMapper());
    }

    // ---------------------------------------------------------------
    // ensureIndex
    // ---------------------------------------------------------------

    /** GET 404 then POST 200 -> {@code created}; body shape verified. */
    @Test
    void ensureIndexCreatesWhenAbsent() {
        wireMock.stubFor(get(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(404).withBody("not found")));
        wireMock.stubFor(post(urlPathEqualTo(INDEXES_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"index_id\":\"" + INDEX_ID + "\"}")));

        final IndexAdminResult result = adapter().ensureIndex(sampleSpec());

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_CREATED);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);

        wireMock.verify(getRequestedFor(
                urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID)));
        wireMock.verify(postRequestedFor(urlPathEqualTo(INDEXES_PATH))
                .withRequestBody(matchingJsonPath("$.version",
                        equalTo(QuickwitHttpAdmin.QUICKWIT_INDEX_CONFIG_VERSION)))
                .withRequestBody(matchingJsonPath("$.index_id",
                        equalTo(INDEX_ID)))
                .withRequestBody(matchingJsonPath("$.doc_mapping.timestamp_field",
                        equalTo("ts")))
                .withRequestBody(matchingJsonPath(
                        "$.doc_mapping.field_mappings[?(@.name == 'message')]"))
                .withRequestBody(matchingJsonPath(
                        "$.search_settings.default_search_fields[0]",
                        equalTo("message"))));
    }

    /** GET 200 -> {@code exists}; no POST sent. */
    @Test
    void ensureIndexShortCircuitsWhenAlreadyExists() {
        wireMock.stubFor(get(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"index_id\":\"" + INDEX_ID + "\"}")));

        final IndexAdminResult result = adapter().ensureIndex(sampleSpec());

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_EXISTS);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(INDEXES_PATH)));
    }

    /** GET 429 -> {@code transient_failure / quickwit:429}; no POST. */
    @Test
    void ensureIndexGetRateLimitedReturnsTransient() {
        wireMock.stubFor(get(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(429).withBody("rate limited")));

        final IndexAdminResult result = adapter().ensureIndex(sampleSpec());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:429");
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(INDEXES_PATH)));
    }

    /** GET 500 -> {@code transient_failure / quickwit:5xx:500}; no POST. */
    @Test
    void ensureIndexGetServerErrorReturnsTransient() {
        wireMock.stubFor(get(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final IndexAdminResult result = adapter().ensureIndex(sampleSpec());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:5xx:500");
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(INDEXES_PATH)));
    }

    /** GET 401 -> {@code permanent_failure / quickwit:4xx:401}; no POST. */
    @Test
    void ensureIndexGetUnauthorizedReturnsPermanent() {
        wireMock.stubFor(get(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(401).withBody("unauth")));

        final IndexAdminResult result = adapter().ensureIndex(sampleSpec());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:401");
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(INDEXES_PATH)));
    }

    /** GET 404 -> POST 400 -> {@code permanent_failure / quickwit:4xx:400}. */
    @Test
    void ensureIndexCreateBadRequestReturnsPermanent() {
        wireMock.stubFor(get(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(post(urlPathEqualTo(INDEXES_PATH))
                .willReturn(aResponse().withStatus(400).withBody("bad mapping")));

        final IndexAdminResult result = adapter().ensureIndex(sampleSpec());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:400");
    }

    /** GET 404 -> POST 503 -> {@code transient_failure / quickwit:5xx:503}. */
    @Test
    void ensureIndexCreateServiceUnavailableReturnsTransient() {
        wireMock.stubFor(get(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(post(urlPathEqualTo(INDEXES_PATH))
                .willReturn(aResponse().withStatus(503).withBody("upstream")));

        final IndexAdminResult result = adapter().ensureIndex(sampleSpec());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:5xx:503");
    }

    /**
     * Transport-layer fault during GET -&gt; transient
     * {@code quickwit:timeout} or {@code quickwit:transport}.
     * Uses WireMock's {@link Fault#CONNECTION_RESET_BY_PEER}
     * (vs a timing-based stub) per LD120 forward rule so the
     * assertion is deterministic across runs.
     */
    @Test
    void ensureIndexTransportFaultReturnsTransientTransportBucket() {
        wireMock.stubFor(get(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        final IndexAdminResult result = adapter().ensureIndex(sampleSpec());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason())
                .isIn("quickwit:timeout", "quickwit:transport");
    }

    // ---------------------------------------------------------------
    // dropIndex
    // ---------------------------------------------------------------

    /** DELETE 200 -> {@code dropped}. */
    @Test
    void dropIndexHappyPathReturnsDropped() {
        wireMock.stubFor(delete(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(200)));

        final IndexAdminResult result = adapter().dropIndex(INDEX_ID);

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_DROPPED);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        wireMock.verify(deleteRequestedFor(
                urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID)));
    }

    /** DELETE 404 -> {@code dropped} per SPI idempotence contract. */
    @Test
    void dropIndexNotFoundReturnsDroppedIdempotent() {
        wireMock.stubFor(delete(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        final IndexAdminResult result = adapter().dropIndex(INDEX_ID);

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_DROPPED);
    }

    /** DELETE 429 -> {@code transient_failure / quickwit:429}. */
    @Test
    void dropIndexRateLimitedReturnsTransient() {
        wireMock.stubFor(delete(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(429).withBody("rate limited")));

        final IndexAdminResult result = adapter().dropIndex(INDEX_ID);

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:429");
    }

    /** DELETE 500 -> {@code transient_failure / quickwit:5xx:500}. */
    @Test
    void dropIndexServerErrorReturnsTransient() {
        wireMock.stubFor(delete(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final IndexAdminResult result = adapter().dropIndex(INDEX_ID);

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:5xx:500");
    }

    /** DELETE 403 -> {@code permanent_failure / quickwit:4xx:403}. */
    @Test
    void dropIndexForbiddenReturnsPermanent() {
        wireMock.stubFor(delete(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withStatus(403).withBody("forbidden")));

        final IndexAdminResult result = adapter().dropIndex(INDEX_ID);

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:403");
    }

    /** DELETE transport fault -> {@code transient_failure / timeout|transport}. */
    @Test
    void dropIndexTransportFaultReturnsTransientTransportBucket() {
        wireMock.stubFor(delete(urlPathEqualTo(INDEX_PATH_PREFIX + INDEX_ID))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        final IndexAdminResult result = adapter().dropIndex(INDEX_ID);

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason())
                .isIn("quickwit:timeout", "quickwit:transport");
    }

    // ---------------------------------------------------------------
    // applyRetention (P7.2 / ADR-0040 D4)
    // ---------------------------------------------------------------

    /** Sample retention policy reused across applyRetention tests. */
    private static RetentionPolicy samplePolicy() {
        return new RetentionPolicy(Duration.ofDays(7));
    }

    /** POST /delete-tasks 200 -> {@code retention_applied}; body shape verified. */
    @Test
    void applyRetentionHappyPathReturnsRetentionApplied() {
        wireMock.stubFor(post(urlPathEqualTo(DELETE_TASKS_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"opstamp\":1}")));

        final IndexAdminResult result =
                adapter().applyRetention(sampleSpec(), samplePolicy());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_RETENTION_APPLIED);
        assertThat(result.backend())
                .isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);

        wireMock.verify(postRequestedFor(urlPathEqualTo(DELETE_TASKS_PATH))
                .withRequestBody(matchingJsonPath("$.query", equalTo("*")))
                .withRequestBody(matchingJsonPath("$.end_timestamp")));
    }

    /** POST /delete-tasks 429 -> {@code transient_failure / quickwit:429}. */
    @Test
    void applyRetentionRateLimitedReturnsTransient() {
        wireMock.stubFor(post(urlPathEqualTo(DELETE_TASKS_PATH))
                .willReturn(aResponse().withStatus(429).withBody("rate limited")));

        final IndexAdminResult result =
                adapter().applyRetention(sampleSpec(), samplePolicy());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:429");
    }

    /** POST /delete-tasks 500 -> {@code transient_failure / quickwit:5xx:500}. */
    @Test
    void applyRetentionServerErrorReturnsTransient() {
        wireMock.stubFor(post(urlPathEqualTo(DELETE_TASKS_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final IndexAdminResult result =
                adapter().applyRetention(sampleSpec(), samplePolicy());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:5xx:500");
    }

    /**
     * POST /delete-tasks 404 (index missing) -&gt; {@code
     * permanent_failure / quickwit:4xx:404}. Per ADR-0040 D4 the
     * 404 is NOT idempotent here -- applying retention to a
     * non-existent index is a config error, distinct from
     * {@code dropIndex}'s 404-is-success semantic.
     */
    @Test
    void applyRetentionNotFoundReturnsPermanent() {
        wireMock.stubFor(post(urlPathEqualTo(DELETE_TASKS_PATH))
                .willReturn(aResponse().withStatus(404).withBody("no such index")));

        final IndexAdminResult result =
                adapter().applyRetention(sampleSpec(), samplePolicy());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:404");
    }

    /** POST /delete-tasks 400 -> {@code permanent_failure / quickwit:4xx:400}. */
    @Test
    void applyRetentionBadRequestReturnsPermanent() {
        wireMock.stubFor(post(urlPathEqualTo(DELETE_TASKS_PATH))
                .willReturn(aResponse().withStatus(400).withBody("bad query")));

        final IndexAdminResult result =
                adapter().applyRetention(sampleSpec(), samplePolicy());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:400");
    }

    /**
     * Transport fault on POST /delete-tasks -&gt; transient
     * {@code timeout} or {@code transport}. Uses {@link
     * Fault#CONNECTION_RESET_BY_PEER} per LD120 for determinism.
     */
    @Test
    void applyRetentionTransportFaultReturnsTransientTransportBucket() {
        wireMock.stubFor(post(urlPathEqualTo(DELETE_TASKS_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        final IndexAdminResult result =
                adapter().applyRetention(sampleSpec(), samplePolicy());

        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason())
                .isIn("quickwit:timeout", "quickwit:transport");
    }
}
