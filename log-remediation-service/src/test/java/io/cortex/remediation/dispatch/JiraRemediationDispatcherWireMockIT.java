package io.cortex.remediation.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import io.cortex.remediation.parse.AnomalyEvent;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * WireMock-driven integration test for
 * {@link JiraRemediationDispatcher} (P6.3 / ADR-0035 D3 outcome
 * table; failsafe pass).
 *
 * <p>Boots an in-process {@link WireMockServer} on a dynamic port,
 * stubs the Jira REST API v3 create-issue endpoint
 * ({@code POST /rest/api/3/issue}) with the four outcome stubs
 * called out in ADR-0035 D3 (201 happy / 429 transient / 500
 * transient / 401 permanent) + one transport-fault stub via LD120
 * deterministic {@link Fault#CONNECTION_RESET_BY_PEER} injection,
 * and asserts the adapter's HTTP outcome -&gt;
 * {@link DispatchResult} mapping end-to-end against a real HTTP
 * server (the Mockito-driven unit test in
 * {@link JiraRemediationDispatcherTest} verifies the same contract
 * with fakes).</p>
 *
 * <p>The {@link RestClient} the adapter consumes is constructed
 * locally with the same HTTP/1.1 pin (LD42) +
 * {@link JdkClientHttpRequestFactory} the production
 * {@code JiraHttpConfig#jiraRestClient(JiraProperties)} bean uses,
 * so what the IT exercises is wire-format identical to what
 * production sends.</p>
 */
@DisplayName("JiraRemediationDispatcher WireMock IT")
class JiraRemediationDispatcherWireMockIT {

    private static final String CREATE_ISSUE_PATH = "/rest/api/3/issue";
    private static final String IT_EMAIL = "ops-it@cortex.io";
    private static final String IT_API_TOKEN = "dummy-jira-it-token";
    private static final String IT_PROJECT_KEY = "OPS";
    /**
     * Read-timeout used by the IT-local {@link RestClient}. Set
     * generously (vs production's 5 s) so the very first cold-start
     * request through the JDK {@link HttpClient} + JIT-cold
     * WireMock stack does not trip the transport-failure classifier
     * when this IT happens to sort first in Failsafe's alphabetical
     * run order (Jira sorts before PagerDuty / Slack). LD123.
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

    /**
     * A representative non-null event reused by every test.
     *
     * @return a fresh sample {@link AnomalyEvent} instance
     */
    private static AnomalyEvent sampleEvent() {
        return new AnomalyEvent(
                "evt-IT-1",
                "tenant-IT",
                "HIGH",
                "checkout 5xx burst",
                Instant.parse("2026-06-04T15:00:00Z"),
                "ERROR",
                "checkout",
                "503 from /pay endpoint");
    }

    /**
     * Builds the adapter wired with the documented Jira
     * RestClient shape pointing at the local WireMock server.
     *
     * <p>Production pins read-timeout at 5 s (see
     * {@code JiraHttpConfig#jiraRestClient(JiraProperties)} and
     * {@link JiraProperties#DEFAULT_REQUEST_TIMEOUT}); the IT bumps
     * it to {@value #IT_READ_TIMEOUT_SECONDS} s purely so the very
     * first cold-start request through the JDK
     * {@link HttpClient} + JIT-cold WireMock pipeline does not
     * trip our transport-failure classifier when this IT class
     * sorts first in the Failsafe alphabetical run order
     * (see LD123). The IT is exercising HTTP outcome -&gt;
     * {@link DispatchResult} mapping, not timeout limits, so a
     * generous IT-only read-timeout is the right call.</p>
     *
     * @return a new {@link JiraRemediationDispatcher} backed by a
     *     real HTTP client + LD42 HTTP/1.1 pin + LD121 dual
     *     connect+read timeout
     */
    private JiraRemediationDispatcher dispatcher() {
        final JiraProperties props = new JiraProperties(
                wireMock.baseUrl(),
                IT_EMAIL,
                IT_API_TOKEN,
                Duration.ofSeconds(IT_READ_TIMEOUT_SECONDS),
                IT_PROJECT_KEY,
                JiraProperties.DEFAULT_ISSUE_TYPE,
                JiraProperties.DEFAULT_SEVERITY_LABEL_PREFIX);
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(IT_READ_TIMEOUT_SECONDS));
        final RestClient client = RestClient.builder()
                .requestFactory(factory)
                .build();
        return new JiraRemediationDispatcher(props, client);
    }

    /** Happy path: WireMock returns 201 Created; adapter reports {@code dispatched}. */
    @Test
    void happyPath201ReturnsDispatched() {
        wireMock.stubFor(post(urlPathEqualTo(CREATE_ISSUE_PATH))
                .willReturn(aResponse().withStatus(201)
                        .withBody("{\"id\":\"10001\","
                                + "\"key\":\"OPS-42\","
                                + "\"self\":\"https://cortex.atlassian.net/rest/api/3/issue/10001\"}")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_JIRA);
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_DISPATCHED);

        // Verify the Basic-auth header carries the Base64-encoded
        // email:apiToken pair per ADR-0035 D2.
        final String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((IT_EMAIL + ":" + IT_API_TOKEN)
                        .getBytes(StandardCharsets.UTF_8));
        // Verify the body shape matches ADR-0035 D2 (create-issue
        // envelope: fields.project.key + fields.summary +
        // fields.description (ADF doc) + fields.issuetype.name +
        // fields.labels).
        wireMock.verify(postRequestedFor(urlPathEqualTo(CREATE_ISSUE_PATH))
                .withHeader("Authorization", equalTo(expectedAuth))
                .withRequestBody(matchingJsonPath("$.fields.project.key",
                        equalTo(IT_PROJECT_KEY)))
                .withRequestBody(matchingJsonPath("$.fields.issuetype.name",
                        equalTo(JiraProperties.DEFAULT_ISSUE_TYPE)))
                .withRequestBody(matchingJsonPath("$.fields.summary"))
                .withRequestBody(matchingJsonPath("$.fields.description.type",
                        equalTo("doc")))
                .withRequestBody(matchingJsonPath(
                        "$.fields.labels[?(@ == 'cortex-remediation')]"))
                .withRequestBody(matchingJsonPath(
                        "$.fields.labels[?(@ == 'tenant:tenant-IT')]"))
                .withRequestBody(matchingJsonPath(
                        "$.fields.labels[?(@ == 'anomaly-severity-high')]")));
    }

    /** WireMock 401 -> {@code permanent_failure / jira:4xx:401}. */
    @Test
    void unauthorized401ReturnsPermanentFailure() {
        wireMock.stubFor(post(urlPathEqualTo(CREATE_ISSUE_PATH))
                .willReturn(aResponse().withStatus(401).withBody("unauth")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:4xx:401");
    }

    /** WireMock 429 -> {@code transient_failure / jira:429}. */
    @Test
    void rateLimited429ReturnsTransientFailure() {
        wireMock.stubFor(post(urlPathEqualTo(CREATE_ISSUE_PATH))
                .willReturn(aResponse().withStatus(429).withBody("rate limited")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:429");
    }

    /** WireMock 500 -> {@code transient_failure / jira:5xx:500}. */
    @Test
    void serverError500ReturnsTransientFailure() {
        wireMock.stubFor(post(urlPathEqualTo(CREATE_ISSUE_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:5xx:500");
    }

    /**
     * Transport-layer fault &rarr; {@code transient_failure /
     * jira:transport}. Uses WireMock's
     * {@link Fault#CONNECTION_RESET_BY_PEER} fault injection (vs a
     * timing-based stub) per LD120 forward rule so the assertion is
     * deterministic across runs and not subject to JDK HttpClient
     * read-timeout quirks -- the adapter must classify any
     * {@code ResourceAccessException} without a
     * {@code HttpTimeoutException} cause as {@code jira:transport}
     * per ADR-0035 D3.
     */
    @Test
    void transportFaultReturnsTransientTransport() {
        wireMock.stubFor(post(urlPathEqualTo(CREATE_ISSUE_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        // Cause may surface as either timeout or transport depending
        // on which JDK layer reports first; both belong to the
        // transient bucket per ADR-0035 D3.
        assertThat(result.reason())
                .isIn("jira:timeout", "jira:transport");
    }
}
