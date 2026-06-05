package io.cortex.remediation.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.cortex.remediation.parse.AnomalyEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
 * Unit tests for {@link JiraRemediationDispatcher} (P6.3 /
 * ADR-0035 D3 outcome table).
 *
 * <p>RestClient is mocked end-to-end (request-spec, header-spec,
 * body-spec, response-spec) so each test exercises one HTTP
 * outcome branch deterministically. The WireMock IT in the failsafe
 * pass proves the same wire-format contract against a real HTTP
 * server.</p>
 *
 * <p>Per LD119 (captured during P6.1): Spring's
 * {@code RequestBodySpec.body(Object)} returns
 * {@link RestClient.RequestBodySpec} (self-type), so the body stub
 * uses {@code doReturn(..).when(..).body(any(Object.class))}.</p>
 */
@DisplayName("JiraRemediationDispatcher unit tests")
class JiraRemediationDispatcherTest {

    private static final String SAMPLE_BASE_URL = "https://cortex.atlassian.net";
    private static final String SAMPLE_EMAIL = "ops@cortex.io";
    private static final String SAMPLE_API_TOKEN = "dummy-jira-token";
    private static final String SAMPLE_PROJECT_KEY = "OPS";

    private RestClient mockClient;
    private RestClient.RequestBodyUriSpec uriSpec;
    private RestClient.RequestBodySpec bodySpec;
    private RestClient.ResponseSpec responseSpec;

    /**
     * Wires the RestClient mock chain
     * ({@code post() -> uri() -> header() -> contentType() -> body() -> retrieve()})
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
        when(this.bodySpec.header(eq(HttpHeaders.AUTHORIZATION), any(String.class)))
                .thenReturn(this.bodySpec);
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
     * Builds a dispatcher with the supplied four credential / target
     * fields plus default issue-type / severity-label-prefix.
     *
     * @param baseUrl    Jira Cloud site root
     * @param email      Basic-auth email
     * @param apiToken   Atlassian API token
     * @param projectKey Jira project key
     * @return a new {@link JiraRemediationDispatcher} backed by the
     *     shared mock {@link RestClient}
     */
    private JiraRemediationDispatcher dispatcherWith(final String baseUrl,
                                                     final String email,
                                                     final String apiToken,
                                                     final String projectKey) {
        return new JiraRemediationDispatcher(
                new JiraProperties(baseUrl, email, apiToken,
                        Duration.ofSeconds(5), projectKey,
                        JiraProperties.DEFAULT_ISSUE_TYPE,
                        JiraProperties.DEFAULT_SEVERITY_LABEL_PREFIX),
                this.mockClient);
    }

    /**
     * Builds a fully-configured dispatcher used by the happy-path
     * tests.
     *
     * @return a new {@link JiraRemediationDispatcher}
     */
    private JiraRemediationDispatcher fullyConfigured() {
        return dispatcherWith(SAMPLE_BASE_URL, SAMPLE_EMAIL,
                SAMPLE_API_TOKEN, SAMPLE_PROJECT_KEY);
    }

    /** A 2xx (201 Created) Jira response must yield {@code outcome=dispatched}. */
    @Test
    void happyPathReturnsDispatched() {
        when(this.responseSpec.toBodilessEntity())
                .thenReturn(org.springframework.http.ResponseEntity
                        .status(HttpStatus.CREATED).build());

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.dispatched()).isTrue();
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_JIRA);
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_DISPATCHED);
        assertThat(result.reason()).isEmpty();
    }

    /** A 429 response must map to {@code transient_failure / jira:429}. */
    @Test
    void tooManyRequestsReturnsTransientFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.TOO_MANY_REQUESTS,
                        "rate limited"));

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.dispatched()).isFalse();
        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:429");
    }

    /** A 5xx response must map to {@code transient_failure / jira:5xx:<code>}. */
    @Test
    void serverErrorReturnsTransientFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "boom"));

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:5xx:500");
    }

    /** A 4xx (non-429) response must map to {@code permanent_failure / jira:4xx:<code>}. */
    @Test
    void clientErrorReturnsPermanentFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.BAD_REQUEST,
                        "invalid issue body"));

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:4xx:400");
    }

    /** A 401 unauthorized must also map to {@code permanent_failure}. */
    @Test
    void unauthorizedReturnsPermanentFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.UNAUTHORIZED, "auth"));

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:4xx:401");
    }

    /** A 404 (project not found) must map to {@code permanent_failure / jira:4xx:404}. */
    @Test
    void projectNotFoundReturnsPermanentFailure() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(httpException(HttpStatus.NOT_FOUND, "no project"));

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:4xx:404");
    }

    /** A connection / read IO failure must map to {@code transient_failure / jira:transport}. */
    @Test
    void transportFailureReturnsTransientTransport() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new ResourceAccessException("connection refused",
                        new java.io.IOException("conn reset")));

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:transport");
    }

    /** A read timeout must map to {@code transient_failure / jira:timeout}. */
    @Test
    void timeoutReturnsTransientTimeout() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new ResourceAccessException("slow",
                        new java.net.http.HttpTimeoutException("read timed out")));

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:timeout");
    }

    /** Any unexpected RuntimeException must map to {@code transient_failure / jira:unknown}. */
    @Test
    void unexpectedRuntimeExceptionReturnsTransientUnknown() {
        when(this.responseSpec.toBodilessEntity())
                .thenThrow(new IllegalStateException("client closed"));

        final DispatchResult result = fullyConfigured().dispatch(sampleEvent());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:unknown");
    }

    /** Any blank credential or target field must short-circuit to {@code skipped / jira:unconfigured}. */
    @Test
    void blankCredentialsReturnSkipped() {
        // Blank baseUrl
        assertThat(dispatcherWith("", SAMPLE_EMAIL,
                SAMPLE_API_TOKEN, SAMPLE_PROJECT_KEY).dispatch(sampleEvent())
                .reason()).isEqualTo("jira:unconfigured");
        // Blank email
        assertThat(dispatcherWith(SAMPLE_BASE_URL, "",
                SAMPLE_API_TOKEN, SAMPLE_PROJECT_KEY).dispatch(sampleEvent())
                .reason()).isEqualTo("jira:unconfigured");
        // Blank apiToken
        assertThat(dispatcherWith(SAMPLE_BASE_URL, SAMPLE_EMAIL,
                "", SAMPLE_PROJECT_KEY).dispatch(sampleEvent())
                .reason()).isEqualTo("jira:unconfigured");
        // Blank projectKey
        final DispatchResult result = dispatcherWith(SAMPLE_BASE_URL, SAMPLE_EMAIL,
                SAMPLE_API_TOKEN, "").dispatch(sampleEvent());
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isEqualTo("jira:unconfigured");
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_NOOP);
    }

    /** A null event must short-circuit to {@code skipped / jira:null-event}. */
    @Test
    void nullEventReturnsSkipped() {
        final DispatchResult result = fullyConfigured().dispatch(null);

        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isEqualTo("jira:null-event");
    }

    /** Body renderer must produce the documented REST API v3 create-issue envelope shape with an ADF description. */
    @Test
    void renderBodyProducesCreateIssueEnvelopeWithAdfDescription() {
        final JiraRemediationDispatcher dispatcher = fullyConfigured();
        final Map<String, Object> body = dispatcher.renderBody(sampleEvent());

        @SuppressWarnings("unchecked")
        final Map<String, Object> fields = (Map<String, Object>) body.get("fields");
        assertThat(fields).isNotNull();

        @SuppressWarnings("unchecked")
        final Map<String, Object> project = (Map<String, Object>) fields.get("project");
        assertThat(project).containsEntry("key", SAMPLE_PROJECT_KEY);

        @SuppressWarnings("unchecked")
        final Map<String, Object> issueType =
                (Map<String, Object>) fields.get("issuetype");
        assertThat(issueType).containsEntry("name", JiraProperties.DEFAULT_ISSUE_TYPE);

        assertThat(fields.get("summary").toString())
                .contains("[HIGH]")
                .contains("checkout")
                .contains("checkout 5xx burst");

        @SuppressWarnings("unchecked")
        final Map<String, Object> description =
                (Map<String, Object>) fields.get("description");
        assertThat(description).containsEntry("type", "doc");
        assertThat(description).containsEntry("version", 1);

        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> content =
                (List<Map<String, Object>>) description.get("content");
        assertThat(content).isNotEmpty();
        // Every node MUST be a paragraph; every paragraph carries
        // exactly one text node whose value is "<label>: <value>".
        for (final Map<String, Object> para : content) {
            assertThat(para).containsEntry("type", "paragraph");
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> textNodes =
                    (List<Map<String, Object>>) para.get("content");
            assertThat(textNodes).hasSize(1);
            assertThat(textNodes.get(0)).containsEntry("type", "text");
            assertThat(textNodes.get(0).get("text").toString()).contains(": ");
        }
    }

    /** Label renderer must always emit the static label plus tenant + severity labels. */
    @Test
    void renderBodyProducesExpectedLabels() {
        final JiraRemediationDispatcher dispatcher = fullyConfigured();
        final Map<String, Object> body = dispatcher.renderBody(sampleEvent());

        @SuppressWarnings("unchecked")
        final Map<String, Object> fields = (Map<String, Object>) body.get("fields");
        @SuppressWarnings("unchecked")
        final List<String> labels = (List<String>) fields.get("labels");

        assertThat(labels).containsExactly(
                "cortex-remediation",
                "tenant:tenant-abc",
                "anomaly-severity-high");
    }

    /** Basic-auth header build must Base64-encode {@code email:apiToken} per ADR-0035 D2. */
    @Test
    void buildAuthHeaderProducesBase64BasicScheme() {
        final JiraRemediationDispatcher dispatcher = fullyConfigured();

        final String header = dispatcher.buildAuthHeader();
        final String expectedB64 = Base64.getEncoder().encodeToString(
                (SAMPLE_EMAIL + ":" + SAMPLE_API_TOKEN)
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(header).isEqualTo("Basic " + expectedB64);
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
                msg.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
