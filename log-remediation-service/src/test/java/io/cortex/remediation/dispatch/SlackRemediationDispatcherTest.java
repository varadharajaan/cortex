package io.cortex.remediation.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.cortex.remediation.parse.AnomalyEvent;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Unit tests for {@link SlackRemediationDispatcher} (P6.1 /
 * ADR-0033 D3 outcome table).
 *
 * <p>RestClient is mocked end-to-end (request-spec, body-spec,
 * response-spec) so each test exercises one HTTP outcome branch
 * deterministically. The WireMock IT in the failsafe pass proves
 * the same wire-format contract against a real HTTP server.</p>
 */
@DisplayName("SlackRemediationDispatcher unit tests")
class SlackRemediationDispatcherTest {

    private static final String SAMPLE_WEBHOOK =
            "https://hooks.slack.com/services/T000/B000/XXXXXXXX";

    private RestClient mockClient;
    private RestClient.RequestBodyUriSpec uriSpec;
    private RestClient.RequestBodySpec bodySpec;
    private RestClient.ResponseSpec responseSpec;

    /**
     * Wires the RestClient mock chain
     * ({@code post() -> uri() -> contentType() -> body() -> retrieve()})
     * fresh per test. Spring's {@code body(Object)} returns
     * {@link RestClient.RequestBodySpec} (it has multiple overloads
     * including {@code body(StreamingHttpOutputMessage.Body)} which
     * confuses Mockito {@code any()} overload resolution), so the
     * body stub uses {@code doReturn(..).when(..).body(any(Object.class))}.
     */
    @BeforeEach
    void setUp() {
        this.mockClient = mock(RestClient.class);
        this.uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        this.bodySpec = mock(RestClient.RequestBodySpec.class);
        this.responseSpec = mock(RestClient.ResponseSpec.class);

        when(this.mockClient.post()).thenReturn(this.uriSpec);
        when(this.uriSpec.uri(any(String.class))).thenReturn(this.bodySpec);
        when(this.bodySpec.contentType(eq(MediaType.APPLICATION_JSON)))
                .thenReturn(this.bodySpec);
        doReturn(this.bodySpec).when(this.bodySpec).body(any(Object.class));
        when(this.bodySpec.retrieve()).thenReturn(this.responseSpec);
    }

    /**
     * A representative non-null event used by every happy-path test.
     *
     * @return a fresh sample {@link AnomalyEvent} instance
     */
    private static AnomalyEvent sampleEvent() {
        return new AnomalyEvent(
                "evt-1",
                "tenant-abc",
                "HIGH",
                "checkout 5xx burst",
                Instant.parse("2026-06-04T15:00:00Z"),
                "ERROR",
                "checkout",
                "503 from /pay endpoint");
    }

    /**
     * Builds a dispatcher with a fully-configured Slack webhook URL.
     *
     * @param url the webhook URL to inject into {@link SlackProperties}
     * @return a new {@link SlackRemediationDispatcher} backed by the
     *     shared mock {@link org.springframework.web.client.RestClient}
     */
    private SlackRemediationDispatcher dispatcherWith(final String url) {
        return new SlackRemediationDispatcher(
                new SlackProperties(url, Duration.ofSeconds(5),
                        "cortex-remediation", ""),
                this.mockClient);
    }

    /** A 2xx Slack webhook response must yield {@code outcome=dispatched}. */
    @Test
    void happyPathReturnsDispatched() {
        when(this.responseSpec.toBodilessEntity())
                .thenReturn(org.springframework.http.ResponseEntity.ok().build());

        final DispatchResult result =
                dispatcherWith(SAMPLE_WEBHOOK).dispatch(sampleEvent());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_SLACK);
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_DISPATCHED);
        assertThat(result.reason()).isEmpty();
    }

    /** A 429 response must map to {@code transient_failure / slack:429}. */
    @Test
    void tooManyRequestsReturnsTransientFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.TOO_MANY_REQUESTS,
                        "rate limited"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_WEBHOOK).dispatch(sampleEvent());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:429");
    }

    /** A 5xx response must map to {@code transient_failure / slack:5xx:<code>}. */
    @Test
    void serverErrorReturnsTransientFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "boom"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_WEBHOOK).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:5xx:500");
    }

    /** A 4xx (non-429) response must map to {@code permanent_failure / slack:4xx:<code>}. */
    @Test
    void clientErrorReturnsPermanentFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.BAD_REQUEST,
                        "invalid body"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_WEBHOOK).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:4xx:400");
    }

    /** A connection / read IO failure must map to {@code transient_failure / slack:transport}. */
    @Test
    void transportFailureReturnsTransientTransport() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new ResourceAccessException("connection refused",
                        new java.io.IOException("conn reset")));

        final DispatchResult result =
                dispatcherWith(SAMPLE_WEBHOOK).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:transport");
    }

    /** A read timeout must map to {@code transient_failure / slack:timeout}. */
    @Test
    void timeoutReturnsTransientTimeout() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new ResourceAccessException("slow",
                        new java.net.http.HttpTimeoutException("read timed out")));

        final DispatchResult result =
                dispatcherWith(SAMPLE_WEBHOOK).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:timeout");
    }

    /** Any unexpected RuntimeException must map to {@code transient_failure / slack:unknown}. */
    @Test
    void unexpectedRuntimeExceptionReturnsTransientUnknown() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new IllegalStateException("client closed"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_WEBHOOK).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:unknown");
    }

    /** A blank webhook URL must short-circuit to {@code skipped / slack:unconfigured}. */
    @Test
    void blankWebhookUrlReturnsSkipped() {
        final DispatchResult result =
                dispatcherWith("").dispatch(sampleEvent());

        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isEqualTo("slack:unconfigured");
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_NOOP);
    }

    /** A null event must short-circuit to {@code skipped / slack:null-event}. */
    @Test
    void nullEventReturnsSkipped() {
        final DispatchResult result =
                dispatcherWith(SAMPLE_WEBHOOK).dispatch(null);

        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isEqualTo("slack:null-event");
    }

    /** Body renderer must include the username override when configured. */
    @Test
    void renderBodyIncludesUsernameWhenSet() {
        final SlackRemediationDispatcher dispatcher = dispatcherWith(SAMPLE_WEBHOOK);
        final Map<String, Object> body = dispatcher.renderBody(sampleEvent());

        assertThat(body).containsKey("text");
        assertThat(body.get("text").toString())
                .contains(":rotating_light:")
                .contains("HIGH")
                .contains("checkout")
                .contains("tenant-abc")
                .contains("checkout 5xx burst");
        assertThat(body).containsEntry("username", "cortex-remediation");
        assertThat(body).doesNotContainKey("channel");
    }

    /** Body renderer must include the channel override when configured. */
    @Test
    void renderBodyIncludesChannelOverrideWhenSet() {
        final SlackRemediationDispatcher dispatcher = new SlackRemediationDispatcher(
                new SlackProperties(SAMPLE_WEBHOOK, Duration.ofSeconds(5),
                        "", "#sre"),
                this.mockClient);
        final Map<String, Object> body = dispatcher.renderBody(sampleEvent());

        assertThat(body).containsEntry("channel", "#sre");
        assertThat(body).doesNotContainKey("username");
    }

    /**
     * Builds a {@link RestClientResponseException} for the supplied
     * status code with a stock empty body / no headers.
     *
     * @param status HTTP status to surface
     * @param msg    reason phrase / message body
     * @return a configured response exception
     */
    private static RestClientResponseException httpException(
            final HttpStatus status, final String msg) {
        return new RestClientResponseException(
                msg, status.value(), msg, HttpHeaders.EMPTY,
                msg.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Sanity that URI accepts plain Strings in this RestClient stub setup. */
    @Test
    void uriIsAcceptedAsString() {
        assertThat(URI.create(SAMPLE_WEBHOOK).getHost())
                .isEqualTo("hooks.slack.com");
    }
}
