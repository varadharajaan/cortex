package io.cortex.indexer.admin.quickwit;

import io.cortex.indexer.admin.IndexAdminResult;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RestAdminTemplate} (P7.1 / ADR-0039 D3).
 *
 * <p>Exhaustive coverage of the outcome classification table -- 429
 * + every 5xx + several 4xx + transport (timeout / non-timeout) +
 * unknown. Verifies the {@code &lt;backend&gt;:&lt;tag&gt;} reason
 * shape used downstream by alerting + retry policy.</p>
 */
@DisplayName("RestAdminTemplate")
class RestAdminTemplateTest {

    /** Backend id reused across every test (matches the production wire-up). */
    private static final String BACKEND = IndexAdminResult.BACKEND_QUICKWIT;

    private final RestAdminTemplate template = new RestAdminTemplate(BACKEND);

    /** 429 maps to transient with reason {@code quickwit:429}. */
    @Test
    void tooManyRequestsMapsToTransient429() {
        final IndexAdminResult result = this.template.classifyHttp(httpEx(429));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:429");
        assertThat(result.backend()).isEqualTo(BACKEND);
    }

    /** 500 maps to transient with reason {@code quickwit:5xx:500}. */
    @Test
    void internalServerErrorMapsToTransient5xx500() {
        final IndexAdminResult result = this.template.classifyHttp(httpEx(500));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:5xx:500");
    }

    /** 503 maps to transient with reason {@code quickwit:5xx:503}. */
    @Test
    void serviceUnavailableMapsToTransient5xx503() {
        final IndexAdminResult result = this.template.classifyHttp(httpEx(503));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:5xx:503");
    }

    /** 400 maps to permanent with reason {@code quickwit:4xx:400}. */
    @Test
    void badRequestMapsToPermanent4xx400() {
        final IndexAdminResult result = this.template.classifyHttp(httpEx(400));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:400");
    }

    /** 401 maps to permanent with reason {@code quickwit:4xx:401}. */
    @Test
    void unauthorizedMapsToPermanent4xx401() {
        final IndexAdminResult result = this.template.classifyHttp(httpEx(401));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:401");
    }

    /** 403 maps to permanent with reason {@code quickwit:4xx:403}. */
    @Test
    void forbiddenMapsToPermanent4xx403() {
        final IndexAdminResult result = this.template.classifyHttp(httpEx(403));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:403");
    }

    /** 415 maps to permanent with reason {@code quickwit:4xx:415}. */
    @Test
    void unsupportedMediaTypeMapsToPermanent4xx415() {
        final IndexAdminResult result = this.template.classifyHttp(httpEx(415));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:415");
    }

    /** Transport with HttpTimeoutException cause maps to transient timeout. */
    @Test
    void httpTimeoutExceptionCauseMapsToTimeout() {
        final ResourceAccessException ex = new ResourceAccessException(
                "read timed out", new HttpTimeoutException("request timeout"));
        final IndexAdminResult result = this.template.classifyTransport(ex);
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:timeout");
    }

    /** Transport with TimeoutException cause maps to transient timeout. */
    @Test
    void timeoutExceptionCauseMapsToTimeout() {
        // ResourceAccessException's 2-arg ctor only accepts IOException
        // as the cause type, but Throwable.initCause() is generic --
        // the single-arg ctor leaves cause unset (== this) so initCause
        // is allowed to attach a TimeoutException post-hoc.
        final ResourceAccessException ex =
                new ResourceAccessException("timed out");
        ex.initCause(new TimeoutException("op timeout"));
        final IndexAdminResult result = this.template.classifyTransport(ex);
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:timeout");
    }

    /**
     * Transport with a non-timeout cause (e.g. {@link IOException} for a
     * connection reset) maps to transient {@code transport}.
     */
    @Test
    void nonTimeoutCauseMapsToTransport() {
        final ResourceAccessException ex = new ResourceAccessException(
                "connection reset", new IOException("RST received"));
        final IndexAdminResult result = this.template.classifyTransport(ex);
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:transport");
    }

    /**
     * Transport with no cause at all also maps to transient
     * {@code transport} (not timeout).
     */
    @Test
    void noCauseMapsToTransport() {
        final ResourceAccessException ex = new ResourceAccessException(
                "bare transport failure");
        final IndexAdminResult result = this.template.classifyTransport(ex);
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:transport");
    }

    /** Any unexpected {@link RuntimeException} maps to transient unknown. */
    @Test
    void runtimeExceptionMapsToUnknown() {
        final IndexAdminResult result = this.template.classifyUnknown(
                new IllegalStateException("bug"));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:unknown");
    }

    /** Build a {@link RestClientResponseException} matching the supplied status. */
    private static RestClientResponseException httpEx(final int status) {
        final HttpStatusCode code = HttpStatusCode.valueOf(status);
        if (code.is4xxClientError()) {
            return HttpClientErrorException.create(
                    HttpStatus.valueOf(status), HttpStatus.valueOf(status).getReasonPhrase(),
                    HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
        }
        return HttpServerErrorException.create(
                HttpStatus.valueOf(status), HttpStatus.valueOf(status).getReasonPhrase(),
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }
}
