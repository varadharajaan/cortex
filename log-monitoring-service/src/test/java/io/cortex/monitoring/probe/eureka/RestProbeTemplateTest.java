package io.cortex.monitoring.probe.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.monitoring.probe.HealthSnapshot;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Mockito-free unit guards for {@link RestProbeTemplate}
 * (P8.1 / ADR-0045 D3 outcome table).
 *
 * <p>Locks the {@code 429 -> transient :429}, {@code 5xx -> transient :5xx:<n>},
 * other {@code 4xx -> permanent :4xx:<n>}, timeout-cause -&gt;
 * transient {@code :timeout}, generic transport -&gt; transient
 * {@code :transport}, and {@link RuntimeException} -&gt; transient
 * {@code :unknown} contract.</p>
 */
@DisplayName("RestProbeTemplate outcome classification")
class RestProbeTemplateTest {

    private static final String BACKEND = HealthSnapshot.BACKEND_EUREKA_ACTUATOR;

    private final RestProbeTemplate template = new RestProbeTemplate(BACKEND);

    @Test
    @DisplayName("429 -> transient backend:429")
    void rateLimitedMapsToTransient429() {
        final HealthSnapshot result = template.classifyHttp(
                HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":429");
        assertThat(result.backend()).isEqualTo(BACKEND);
    }

    @Test
    @DisplayName("500 -> transient backend:5xx:500")
    void serverError500MapsToTransient5xx() {
        final HealthSnapshot result = template.classifyHttp(
                HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Internal Server Error",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":5xx:500");
    }

    @Test
    @DisplayName("503 -> transient backend:5xx:503")
    void serverError503MapsToTransient5xx() {
        final HealthSnapshot result = template.classifyHttp(
                HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":5xx:503");
    }

    @Test
    @DisplayName("404 -> permanent backend:4xx:404")
    void notFoundMapsToPermanent4xx() {
        final HealthSnapshot result = template.classifyHttp(
                HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":4xx:404");
    }

    @Test
    @DisplayName("400 -> permanent backend:4xx:400")
    void badRequestMapsToPermanent4xx() {
        final HealthSnapshot result = template.classifyHttp(
                HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], null));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":4xx:400");
    }

    @Test
    @DisplayName("HttpTimeoutException cause -> transient backend:timeout")
    void httpTimeoutMapsToTransientTimeout() {
        final HealthSnapshot result = template.classifyTransport(
                new ResourceAccessException(
                        "io",
                        new HttpTimeoutException("read timed out")));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":timeout");
    }

    @Test
    @DisplayName("TimeoutException cause -> transient backend:timeout")
    void timeoutExceptionMapsToTransientTimeout() {
        // ResourceAccessException's 2-arg ctor only accepts IOException
        // as the cause type, but Throwable.initCause() is generic --
        // the single-arg ctor leaves cause unset (== this) so initCause
        // is allowed to attach a TimeoutException post-hoc.
        final ResourceAccessException ex =
                new ResourceAccessException("timed out");
        ex.initCause(new TimeoutException("op timeout"));

        final HealthSnapshot result = template.classifyTransport(ex);

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":timeout");
    }

    @Test
    @DisplayName("non-timeout transport cause -> transient backend:transport")
    void genericTransportMapsToTransientTransport() {
        final HealthSnapshot result = template.classifyTransport(
                new ResourceAccessException(
                        "io", new java.net.SocketException("reset")));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":transport");
    }

    @Test
    @DisplayName("null-cause transport -> transient backend:transport")
    void nullCauseTransportMapsToTransientTransport() {
        final HealthSnapshot result = template.classifyTransport(
                new ResourceAccessException("io"));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":transport");
    }

    @Test
    @DisplayName("unknown RuntimeException -> transient backend:unknown")
    void unknownRuntimeMapsToTransientUnknown() {
        final HealthSnapshot result = template.classifyUnknown(
                new IllegalStateException("boom"));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo(BACKEND + ":unknown");
    }
}
