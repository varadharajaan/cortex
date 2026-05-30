package io.cortex.gateway.exception;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.constants.LogFields;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unit tests for {@link GlobalExceptionHandler}: maps every exception
 * branch and asserts the RFC 7807 body shape including the custom
 * {@code errorCode}, {@code timestamp}, and {@code traceId} properties.
 */
class GlobalExceptionHandlerTest {

    /** Handler under test. */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Shared request stub with a stable URI. */
    private final HttpServletRequest request = buildRequest();

    /** Clears MDC after every test so cases stay isolated. */
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** {@link ErrorCodes#VALIDATION_FAILED} maps to {@code 400 Bad Request}. */
    @Test
    void mapsValidationFailedToBadRequest() {
        final ApplicationException ex = new ApplicationException(
                ErrorCodes.VALIDATION_FAILED, "bad input");

        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(ex, this.request);

        assertProblem(response, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "bad input");
    }

    /** {@link ErrorCodes#UPSTREAM_UNAVAILABLE} maps to {@code 502 Bad Gateway}. */
    @Test
    void mapsUpstreamUnavailableToBadGateway() {
        final ApplicationException ex = new ApplicationException(
                ErrorCodes.UPSTREAM_UNAVAILABLE, "ingest down", new IllegalStateException("ioe"));

        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(ex, this.request);

        assertProblem(response, HttpStatus.BAD_GATEWAY, ErrorCodes.UPSTREAM_UNAVAILABLE, "ingest down");
    }

    /** {@link ErrorCodes#NOT_FOUND} maps to {@code 404 Not Found}. */
    @Test
    void mapsNotFound() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.NOT_FOUND, "missing"), this.request);

        assertProblem(response, HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "missing");
    }

    /** {@link ErrorCodes#INTERNAL_ERROR} maps to {@code 500 Internal Server Error}. */
    @Test
    void mapsInternalError() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.INTERNAL_ERROR, "boom"), this.request);

        assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "boom");
    }

    /** {@link ErrorCodes#BAD_REQUEST} maps to {@code 400 Bad Request}. */
    @Test
    void mapsBadRequest() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.BAD_REQUEST, "rejected"), this.request);

        assertProblem(response, HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "rejected");
    }

    /** {@link ErrorCodes#FORBIDDEN} via application exception maps to {@code 403}. */
    @Test
    void mapsForbiddenViaApplicationException() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.FORBIDDEN, "nope"), this.request);

        assertProblem(response, HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "nope");
    }

    /** {@link ErrorCodes#UNAUTHENTICATED} via application exception maps to {@code 401}. */
    @Test
    void mapsUnauthenticatedViaApplicationException() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.UNAUTHENTICATED, "no token"), this.request);

        assertProblem(response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, "no token");
    }

    /** Spring Security {@link AuthenticationException} maps to {@code 401}. */
    @Test
    void mapsAuthenticationException() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleAuth(
                new TestAuthException("invalid"), this.request);

        assertProblem(response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED, "invalid");
    }

    /** Spring Security {@link AccessDeniedException} maps to {@code 403}. */
    @Test
    void mapsAccessDenied() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleDenied(
                new AccessDeniedException("denied"), this.request);

        assertProblem(response, HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, "denied");
    }

    /** Arbitrary throwable produces a generic 500 with the static detail message. */
    @Test
    void mapsUnexpectedToGenericInternalError() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleUnexpected(
                new RuntimeException("dont leak this"), this.request);

        assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "internal error");
    }

    /**
     * {@link NoResourceFoundException} (thrown by Spring MVC when no handler
     * matches an authenticated request) maps to {@code 404 Not Found} with
     * the generic detail {@code "not found"} (no internal info leaked).
     */
    @Test
    void mapsNoResourceFoundTo404() {
        final NoResourceFoundException ex = new NoResourceFoundException(
                HttpMethod.GET, "/api/v1/__nope__");

        final ResponseEntity<ProblemDetail> response = this.handler.handleNoResource(ex, this.request);

        assertProblem(response, HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "not found");
    }

    /** When MDC carries a {@code traceId}, it appears on the problem body. */
    @Test
    void includesTraceIdFromMdcWhenPresent() {
        MDC.put(LogFields.TRACE_ID, "trace-abc");

        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.BAD_REQUEST, "x"), this.request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("traceId", "trace-abc");
    }

    /**
     * {@link RateLimitedException} maps to {@code 429 Too Many Requests}
     * with a {@code Retry-After} header sourced from the exception's
     * {@code retryAfterSeconds}. The X-RateLimit-* headers are NOT
     * re-set here because the filter sets them before throwing.
     */
    @Test
    void mapsRateLimitedTo429WithRetryAfterHeader() {
        final RateLimitedException ex = new RateLimitedException(100L, 0L, 42L);

        final ResponseEntity<ProblemDetail> response = this.handler.handleRateLimited(ex, this.request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HeaderNames.RETRY_AFTER)).isEqualTo("42");
        // Headers set by the filter must NOT be re-asserted by the handler
        // (would create duplicate header values when both Spring writes).
        assertThat(response.getHeaders().get(HeaderNames.X_RATELIMIT_LIMIT)).isNull();
        assertThat(response.getHeaders().get(HeaderNames.X_RATELIMIT_REMAINING)).isNull();
        assertThat(response.getHeaders().get(HeaderNames.X_RATELIMIT_RESET)).isNull();
        final ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getDetail()).isEqualTo("rate limit exceeded");
        assertThat(body.getProperties()).containsEntry("errorCode", ErrorCodes.RATE_LIMITED.name());
    }

    /**
     * @return a mock request with a fixed URI for the {@code instance} field
     */
    private static MockHttpServletRequest buildRequest() {
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/v1/health");
        return req;
    }

    /**
     * Shared assertion that verifies status, detail, and the gateway-specific
     * {@code errorCode} / {@code timestamp} problem properties.
     *
     * @param response response entity returned by the handler
     * @param status   expected HTTP status
     * @param code     expected stable error code
     * @param detail   expected detail message
     */
    private static void assertProblem(
            final ResponseEntity<ProblemDetail> response,
            final HttpStatus status,
            final ErrorCodes code,
            final String detail) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        final ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getDetail()).isEqualTo(detail);
        assertThat(body.getProperties()).containsEntry("errorCode", code.name());
        assertThat(body.getProperties()).containsKey("timestamp");
    }

    /** Minimal subclass to instantiate the abstract {@link AuthenticationException}. */
    private static final class TestAuthException extends AuthenticationException {
        private static final long serialVersionUID = 1L;

        /**
         * Builds a test authentication exception with the supplied message.
         *
         * @param msg detail message
         */
        TestAuthException(final String msg) {
            super(msg);
        }
    }
}
