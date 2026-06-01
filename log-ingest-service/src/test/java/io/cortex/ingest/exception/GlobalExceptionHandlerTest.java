package io.cortex.ingest.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.cortex.ingest.constants.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>The integration tests already cover the
 * {@link ErrorCodes#VALIDATION_FAILED} and
 * {@link ErrorCodes#INTERNAL_ERROR} branches via real HTTP traffic;
 * the unit tests below drive every remaining branch in
 * {@code mapStatus(...)} plus the {@code @ExceptionHandler} methods
 * that are not exercised by the production happy paths, lifting
 * branch coverage above the 0.80 JaCoCo gate (P4.1 / LD3 /
 * Rule 12.5 / 13.4).</p>
 */
class GlobalExceptionHandlerTest {

    /** SUT - stateless, instantiated per test for isolation. */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Default constructor used by JUnit. */
    GlobalExceptionHandlerTest() {
        // no state
    }

    /**
     * {@link ErrorCodes#VALIDATION_FAILED} and
     * {@link ErrorCodes#BAD_REQUEST} both map to
     * {@link HttpStatus#BAD_REQUEST}.
     */
    @Test
    void applicationExceptionWithValidationOrBadRequestMapsToBadRequest() {
        for (final ErrorCodes code : List.of(
                ErrorCodes.VALIDATION_FAILED, ErrorCodes.BAD_REQUEST)) {
            final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                    new ApplicationException(code, "bad input"), mockRequest());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
        }
    }

    /**
     * {@link ErrorCodes#UNAUTHENTICATED} maps to
     * {@link HttpStatus#UNAUTHORIZED}.
     */
    @Test
    void applicationExceptionWithUnauthenticatedMapsToUnauthorized() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.UNAUTHENTICATED, "no creds"),
                mockRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * {@link ErrorCodes#FORBIDDEN} maps to {@link HttpStatus#FORBIDDEN}.
     */
    @Test
    void applicationExceptionWithForbiddenMapsToForbidden() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.FORBIDDEN, "nope"),
                mockRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * {@link ErrorCodes#NOT_FOUND} maps to {@link HttpStatus#NOT_FOUND}.
     */
    @Test
    void applicationExceptionWithNotFoundMapsToNotFound() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.NOT_FOUND, "missing"),
                mockRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * {@link ErrorCodes#UPSTREAM_UNAVAILABLE} maps to
     * {@link HttpStatus#BAD_GATEWAY}.
     */
    @Test
    void applicationExceptionWithUpstreamUnavailableMapsToBadGateway() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.UPSTREAM_UNAVAILABLE, "db down"),
                mockRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    /**
     * {@link ErrorCodes#RATE_LIMITED} maps to
     * {@link HttpStatus#TOO_MANY_REQUESTS}.
     */
    @Test
    void applicationExceptionWithRateLimitedMapsToTooManyRequests() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.RATE_LIMITED, "throttle"),
                mockRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * {@link ErrorCodes#INTERNAL_ERROR} maps to
     * {@link HttpStatus#INTERNAL_SERVER_ERROR}.
     */
    @Test
    void applicationExceptionWithInternalErrorMapsTo500() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleApplication(
                new ApplicationException(ErrorCodes.INTERNAL_ERROR, "boom"),
                mockRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Validation failures with at least one field error surface the
     * collected default messages as the problem detail.
     */
    @Test
    void validationWithFieldErrorsCollectsMessages() {
        final BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "service", "must not be blank"));
        final MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(null, bindingResult);

        final ResponseEntity<ProblemDetail> response =
                this.handler.handleValidation(ex, mockRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("must not be blank");
    }

    /**
     * Validation failures with an empty error list fall back to the
     * generic {@code "validation failed"} detail (the
     * {@code detail.isEmpty()} branch in {@code handleValidation}).
     */
    @Test
    void validationWithoutFieldErrorsUsesFallbackDetail() {
        final BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(new Object(), "target");
        final MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(null, bindingResult);

        final ResponseEntity<ProblemDetail> response =
                this.handler.handleValidation(ex, mockRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("validation failed");
    }

    /**
     * Malformed request bodies surface as
     * {@link HttpStatus#BAD_REQUEST} with
     * {@link ErrorCodes#BAD_REQUEST}.
     */
    @Test
    void notReadableMapsToBadRequest() {
        final HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "bad json", null, new org.springframework.http.server.ServletServerHttpRequest(
                        new org.springframework.mock.web.MockHttpServletRequest()));

        final ResponseEntity<ProblemDetail> response =
                this.handler.handleNotReadable(ex, mockRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
    }

    /**
     * Missing static resources / unknown routes map to
     * {@link HttpStatus#NOT_FOUND}.
     */
    @Test
    void noResourceFoundMapsToNotFound() {
        final NoResourceFoundException ex =
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/missing");

        final ResponseEntity<ProblemDetail> response =
                this.handler.handleNoResource(ex, mockRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
    }

    /**
     * Unexpected throwables are sanitised behind a generic
     * 500 / {@link ErrorCodes#INTERNAL_ERROR} response (no leak of
     * the internal message).
     */
    @Test
    void unexpectedThrowableMapsTo500() {
        final ResponseEntity<ProblemDetail> response = this.handler.handleUnexpected(
                new IllegalStateException("internal secret"), mockRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("internal error");
    }

    /**
     * Returns a stub {@link HttpServletRequest} that only exposes
     * the request URI used by {@code buildProblem(...)}.
     *
     * @return mock request scoped to a fixed URI
     */
    private static HttpServletRequest mockRequest() {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/test");
        return request;
    }
}
