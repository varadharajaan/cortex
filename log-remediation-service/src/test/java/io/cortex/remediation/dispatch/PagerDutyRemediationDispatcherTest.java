package io.cortex.remediation.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.cortex.remediation.parse.AnomalyEvent;
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
 * Unit tests for {@link PagerDutyRemediationDispatcher} (P6.2 /
 * ADR-0034 D3 outcome table).
 *
 * <p>RestClient is mocked end-to-end (request-spec, body-spec,
 * response-spec) so each test exercises one HTTP outcome branch
 * deterministically. The WireMock IT in the failsafe pass proves
 * the same wire-format contract against a real HTTP server.</p>
 *
 * <p>Per LD119 (captured during P6.1): Spring's
 * {@code RequestBodySpec.body(Object)} returns
 * {@link RestClient.RequestBodySpec} (self-type), so the body stub
 * uses {@code doReturn(..).when(..).body(any(Object.class))}.</p>
 */
@DisplayName("PagerDutyRemediationDispatcher unit tests")
class PagerDutyRemediationDispatcherTest {

    private static final String SAMPLE_ROUTING_KEY =
            "abcdef1234567890abcdef1234567890";
    private static final String SAMPLE_EVENTS_URL =
            "https://events.pagerduty.com/v2/enqueue";

    private RestClient mockClient;
    private RestClient.RequestBodyUriSpec uriSpec;
    private RestClient.RequestBodySpec bodySpec;
    private RestClient.ResponseSpec responseSpec;

    /**
     * Wires the RestClient mock chain
     * ({@code post() -> uri() -> contentType() -> body() -> retrieve()})
     * fresh per test. Per LD119: {@code body(Object)} returns
     * {@link RestClient.RequestBodySpec} (self-type), so the body
     * stub uses {@code doReturn(..).when(..).body(any(Object.class))}.
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
     * Builds a dispatcher with a fully-configured PagerDuty routing
     * key + default Events API v2 endpoint + default template /
     * source / severity-default.
     *
     * @param routingKey the routing key to inject into {@link PagerDutyProperties}
     * @return a new {@link PagerDutyRemediationDispatcher} backed
     *     by the shared mock {@link RestClient}
     */
    private PagerDutyRemediationDispatcher dispatcherWith(final String routingKey) {
        return new PagerDutyRemediationDispatcher(
                new PagerDutyProperties(routingKey, Duration.ofSeconds(5),
                        SAMPLE_EVENTS_URL,
                        PagerDutyProperties.DEFAULT_DEDUP_KEY_TEMPLATE,
                        PagerDutyProperties.DEFAULT_SOURCE,
                        PagerDutyProperties.DEFAULT_SEVERITY),
                this.mockClient);
    }

    /** A 2xx (202 Accepted) PagerDuty response must yield {@code outcome=dispatched}. */
    @Test
    void happyPathReturnsDispatched() {
        when(this.responseSpec.toBodilessEntity())
                .thenReturn(org.springframework.http.ResponseEntity.accepted().build());

        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(sampleEvent());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_PAGERDUTY);
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_DISPATCHED);
        assertThat(result.reason()).isEmpty();
    }

    /** A 429 response must map to {@code transient_failure / pagerduty:429}. */
    @Test
    void tooManyRequestsReturnsTransientFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.TOO_MANY_REQUESTS,
                        "rate limited"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(sampleEvent());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:429");
    }

    /** A 5xx response must map to {@code transient_failure / pagerduty:5xx:<code>}. */
    @Test
    void serverErrorReturnsTransientFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "boom"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:5xx:500");
    }

    /** A 4xx (non-429) response must map to {@code permanent_failure / pagerduty:4xx:<code>}. */
    @Test
    void clientErrorReturnsPermanentFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.BAD_REQUEST,
                        "invalid routing key"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:4xx:400");
    }

    /** A 401 unauthorized must also map to {@code permanent_failure}. */
    @Test
    void unauthorizedReturnsPermanentFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.UNAUTHORIZED, "auth"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:4xx:401");
    }

    /** A connection / read IO failure must map to {@code transient_failure / pagerduty:transport}. */
    @Test
    void transportFailureReturnsTransientTransport() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new ResourceAccessException("connection refused",
                        new java.io.IOException("conn reset")));

        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:transport");
    }

    /** A read timeout must map to {@code transient_failure / pagerduty:timeout}. */
    @Test
    void timeoutReturnsTransientTimeout() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new ResourceAccessException("slow",
                        new java.net.http.HttpTimeoutException("read timed out")));

        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:timeout");
    }

    /** Any unexpected RuntimeException must map to {@code transient_failure / pagerduty:unknown}. */
    @Test
    void unexpectedRuntimeExceptionReturnsTransientUnknown() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new IllegalStateException("client closed"));

        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("pagerduty:unknown");
    }

    /** A blank routing key must short-circuit to {@code skipped / pagerduty:unconfigured}. */
    @Test
    void blankRoutingKeyReturnsSkipped() {
        final DispatchResult result =
                dispatcherWith("").dispatch(sampleEvent());

        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isEqualTo("pagerduty:unconfigured");
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_NOOP);
    }

    /** A null event must short-circuit to {@code skipped / pagerduty:null-event}. */
    @Test
    void nullEventReturnsSkipped() {
        final DispatchResult result =
                dispatcherWith(SAMPLE_ROUTING_KEY).dispatch(null);

        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isEqualTo("pagerduty:null-event");
    }

    /** Body renderer must produce the documented Events API v2 envelope shape. */
    @Test
    void renderBodyProducesEventsApiV2Envelope() {
        final PagerDutyRemediationDispatcher dispatcher =
                dispatcherWith(SAMPLE_ROUTING_KEY);
        final Map<String, Object> body = dispatcher.renderBody(sampleEvent());

        assertThat(body).containsEntry("routing_key", SAMPLE_ROUTING_KEY);
        assertThat(body).containsEntry("event_action", "trigger");
        assertThat(body).containsEntry("dedup_key", "tenant-abc:evt-1");

        @SuppressWarnings("unchecked")
        final Map<String, Object> payload = (Map<String, Object>) body.get("payload");
        assertThat(payload).isNotNull();
        assertThat(payload.get("summary").toString())
                .contains("HIGH")
                .contains("checkout")
                .contains("checkout 5xx burst");
        assertThat(payload).containsEntry("severity", "error"); // HIGH -> default
        assertThat(payload).containsEntry("source",
                PagerDutyProperties.DEFAULT_SOURCE);

        @SuppressWarnings("unchecked")
        final Map<String, Object> details =
                (Map<String, Object>) payload.get("custom_details");
        assertThat(details).containsEntry("eventId", "evt-1");
        assertThat(details).containsEntry("tenantId", "tenant-abc");
        assertThat(details).containsEntry("severity", "HIGH");
        assertThat(details).containsEntry("service", "checkout");
    }

    /** Severity mapping must pass-through PagerDuty's accepted values (case-insensitive). */
    @Test
    void severityMappingPassesThroughAcceptedValues() {
        final PagerDutyRemediationDispatcher dispatcher =
                dispatcherWith(SAMPLE_ROUTING_KEY);

        for (final String accepted : new String[] {
                "critical", "error", "warning", "info"}) {
            final AnomalyEvent ev = new AnomalyEvent("e", "t",
                    accepted.toUpperCase(java.util.Locale.ROOT),
                    "r", Instant.EPOCH, "L", "s", "m");
            final Map<String, Object> body = dispatcher.renderBody(ev);
            @SuppressWarnings("unchecked")
            final Map<String, Object> payload =
                    (Map<String, Object>) body.get("payload");
            assertThat(payload.get("severity"))
                    .as("severity=%s must map to lowercase pass-through", accepted)
                    .isEqualTo(accepted);
        }
    }

    /** Severity fallback must kick in for unrecognised values. */
    @Test
    void severityFallbackKicksInForUnknownValues() {
        final PagerDutyRemediationDispatcher dispatcher =
                new PagerDutyRemediationDispatcher(
                        new PagerDutyProperties(SAMPLE_ROUTING_KEY,
                                Duration.ofSeconds(5), SAMPLE_EVENTS_URL,
                                PagerDutyProperties.DEFAULT_DEDUP_KEY_TEMPLATE,
                                PagerDutyProperties.DEFAULT_SOURCE,
                                "critical"),
                        this.mockClient);
        final AnomalyEvent ev = new AnomalyEvent("e", "t",
                "MAYHEM", "r", Instant.EPOCH, "L", "s", "m");
        final Map<String, Object> body = dispatcher.renderBody(ev);
        @SuppressWarnings("unchecked")
        final Map<String, Object> payload =
                (Map<String, Object>) body.get("payload");
        assertThat(payload).containsEntry("severity", "critical");
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
}
