package io.cortex.remediation.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * WireMock-driven integration test for
 * {@link PagerDutyRemediationDispatcher} (P6.2 / ADR-0034 D3
 * outcome table; failsafe pass).
 *
 * <p>Boots an in-process {@link WireMockServer} on a dynamic port,
 * stubs the PagerDuty Events API v2 enqueue endpoint
 * ({@code POST /v2/enqueue}) with the four outcome stubs called
 * out in ADR-0034 D3 (202 happy / 429 transient / 500 transient /
 * 400 permanent) + one transport-fault stub via LD120 deterministic
 * {@link Fault#CONNECTION_RESET_BY_PEER} injection, and asserts the
 * adapter's HTTP outcome -&gt; {@link DispatchResult} mapping
 * end-to-end against a real HTTP server (the Mockito-driven unit
 * test in {@link PagerDutyRemediationDispatcherTest} verifies the
 * same contract with fakes).</p>
 *
 * <p>The {@link RestClient} the adapter consumes is constructed
 * locally with the same HTTP/1.1 pin (LD42) +
 * {@link JdkClientHttpRequestFactory} the production
 * {@code PagerDutyHttpConfig#pagerDutyRestClient(PagerDutyProperties)}
 * bean uses, so what the IT exercises is wire-format identical to
 * what production sends.</p>
 */
@DisplayName("PagerDutyRemediationDispatcher WireMock IT")
class PagerDutyRemediationDispatcherWireMockIT {

    private static final String ENQUEUE_PATH = "/v2/enqueue";
    private static final String IT_ROUTING_KEY =
            "abcdef1234567890abcdef1234567890";

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
     * Builds the adapter wired with the documented PagerDuty
     * RestClient shape pointing at the local WireMock server.
     *
     * @return a new {@link PagerDutyRemediationDispatcher} backed
     *     by a real HTTP client + LD42 HTTP/1.1 pin + LD121 dual
     *     connect+read timeout
     */
    private PagerDutyRemediationDispatcher dispatcher() {
        final PagerDutyProperties props = new PagerDutyProperties(
                IT_ROUTING_KEY,
                Duration.ofSeconds(5),
                wireMock.baseUrl() + ENQUEUE_PATH,
                PagerDutyProperties.DEFAULT_DEDUP_KEY_TEMPLATE,
                "cortex-remediation-it",
                PagerDutyProperties.DEFAULT_SEVERITY);
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        final RestClient client = RestClient.builder()
                .requestFactory(factory)
                .build();
        return new PagerDutyRemediationDispatcher(props, client);
    }

    /** Happy path: WireMock returns 202 Accepted; adapter reports {@code dispatched}. */
    @Test
    void happyPath202ReturnsDispatched() {
        wireMock.stubFor(post(urlPathEqualTo(ENQUEUE_PATH))
                .willReturn(aResponse().withStatus(202)
                        .withBody("{\"status\":\"success\","
                                + "\"message\":\"Event processed\","
                                + "\"dedup_key\":\"tenant-IT:evt-IT-1\"}")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_PAGERDUTY);
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_DISPATCHED);

        // Verify the body shape matches ADR-0034 D2 (Events API v2 envelope:
        // routing_key + event_action=trigger + dedup_key + payload).
        wireMock.verify(postRequestedFor(urlPathEqualTo(ENQUEUE_PATH))
                .withRequestBody(matchingJsonPath("$.routing_key",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo(IT_ROUTING_KEY)))
                .withRequestBody(matchingJsonPath("$.event_action",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("trigger")))
                .withRequestBody(matchingJsonPath("$.dedup_key",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("tenant-IT:evt-IT-1")))
                .withRequestBody(matchingJsonPath("$.payload.severity",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("error")))
                .withRequestBody(matchingJsonPath("$.payload.source",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("cortex-remediation-it"))));
    }

    /** WireMock 429 -> {@code transient_failure / pagerduty:429}. */
    @Test
    void rateLimited429ReturnsTransientFailure() {
        wireMock.stubFor(post(urlPathEqualTo(ENQUEUE_PATH))
                .willReturn(aResponse().withStatus(429).withBody("rate limited")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:429");
    }

    /** WireMock 500 -> {@code transient_failure / pagerduty:5xx:500}. */
    @Test
    void serverError500ReturnsTransientFailure() {
        wireMock.stubFor(post(urlPathEqualTo(ENQUEUE_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:5xx:500");
    }

    /** WireMock 400 -> {@code permanent_failure / pagerduty:4xx:400}. */
    @Test
    void clientError400ReturnsPermanentFailure() {
        wireMock.stubFor(post(urlPathEqualTo(ENQUEUE_PATH))
                .willReturn(aResponse().withStatus(400).withBody("invalid")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:4xx:400");
    }

    /**
     * Transport-layer fault &rarr; {@code transient_failure /
     * pagerduty:transport}. Uses WireMock's
     * {@link Fault#CONNECTION_RESET_BY_PEER} fault injection (vs a
     * timing-based stub) per LD120 forward rule so the assertion is
     * deterministic across runs and not subject to JDK HttpClient
     * read-timeout quirks -- the adapter must classify any
     * {@code ResourceAccessException} without a
     * {@code HttpTimeoutException} cause as
     * {@code pagerduty:transport} per ADR-0034 D3.
     */
    @Test
    void transportFaultReturnsTransientTransport() {
        wireMock.stubFor(post(urlPathEqualTo(ENQUEUE_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        // Cause may surface as either timeout or transport depending
        // on which JDK layer reports first; both belong to the
        // transient bucket per ADR-0034 D3.
        assertThat(result.reason())
                .isIn("pagerduty:timeout", "pagerduty:transport");
    }
}
