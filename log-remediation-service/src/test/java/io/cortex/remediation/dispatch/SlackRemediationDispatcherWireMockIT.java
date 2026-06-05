package io.cortex.remediation.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
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
 * {@link SlackRemediationDispatcher} (P6.1 / ADR-0033 D3 outcome
 * table; failsafe pass).
 *
 * <p>Boots an in-process {@link WireMockServer} on a dynamic port,
 * stubs Slack's Incoming-Webhook endpoint
 * ({@code POST /services/{T}/{B}/{X}}) with the four outcome stubs
 * called out in ADR-0033 D3 (200 happy / 429 transient / 500
 * transient / 400 permanent), and asserts the adapter's HTTP
 * outcome -&gt; {@link DispatchResult} mapping end-to-end against a
 * real HTTP server (the Mockito-driven unit test in
 * {@link SlackRemediationDispatcherTest} verifies the same contract
 * with fakes).</p>
 *
 * <p>The {@link RestClient} the adapter consumes is constructed
 * locally with the same HTTP/1.1 pin (LD42) +
 * {@link JdkClientHttpRequestFactory} the production {@code
 * SlackHttpConfig#slackRestClient(SlackProperties)} bean uses, so
 * what the IT exercises is wire-format identical to what production
 * sends.</p>
 */
@DisplayName("SlackRemediationDispatcher WireMock IT")
class SlackRemediationDispatcherWireMockIT {

    private static final String WEBHOOK_PATH = "/services/TIT00000/BIT00000/XIT00000";

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
     * Builds the adapter wired with the documented Slack RestClient shape.
     *
     * @return a new {@link SlackRemediationDispatcher} pointing at the
     *     local WireMock server
     */
    private SlackRemediationDispatcher dispatcher() {
        final SlackProperties props = new SlackProperties(
                wireMock.baseUrl() + WEBHOOK_PATH,
                Duration.ofSeconds(5),
                "cortex-remediation-it",
                "");
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        final RestClient client = RestClient.builder()
                .requestFactory(factory)
                .build();
        return new SlackRemediationDispatcher(props, client);
    }

    /** Happy path: WireMock returns 200 OK; adapter reports {@code dispatched}. */
    @Test
    void happyPath200ReturnsDispatched() {
        wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_SLACK);
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_DISPATCHED);

        // Verify the body shape matches ADR-0033 D2 (plain-text "text"
        // + optional "username" override).
        wireMock.verify(postRequestedFor(urlPathEqualTo(WEBHOOK_PATH))
                .withRequestBody(equalToJson("{"
                        + "\"text\":\":rotating_light: HIGH anomaly on checkout"
                        + " (tenant=tenant-IT): checkout 5xx burst\","
                        + "\"username\":\"cortex-remediation-it\""
                        + "}")));
    }

    /** WireMock 429 -> {@code transient_failure / slack:429}. */
    @Test
    void rateLimited429ReturnsTransientFailure() {
        wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(429).withBody("rate limited")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:429");
    }

    /** WireMock 500 -> {@code transient_failure / slack:5xx:500}. */
    @Test
    void serverError500ReturnsTransientFailure() {
        wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:5xx:500");
    }

    /** WireMock 400 -> {@code permanent_failure / slack:4xx:400}. */
    @Test
    void clientError400ReturnsPermanentFailure() {
        wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(400).withBody("invalid body")));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:4xx:400");
    }

    /**
     * Transport-layer fault &rarr; {@code transient_failure /
     * slack:transport}. Uses WireMock's
     * {@link Fault#CONNECTION_RESET_BY_PEER} fault injection (vs a
     * timing-based stub) so the assertion is deterministic across
     * runs and not subject to JDK HttpClient read-timeout quirks --
     * the adapter must classify any {@code ResourceAccessException}
     * without a {@code HttpTimeoutException} cause as
     * {@code slack:transport} per ADR-0033 D3.
     */
    @Test
    void transportFaultReturnsTransientTransport() {
        wireMock.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        final DispatchResult result = dispatcher().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        // Cause may surface as either timeout or transport depending
        // on which JDK layer reports first; both belong to the
        // transient bucket per ADR-0033 D3.
        assertThat(result.reason())
                .isIn("slack:timeout", "slack:transport");
    }
}
