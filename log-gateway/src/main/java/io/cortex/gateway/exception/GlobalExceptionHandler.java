package io.cortex.gateway.exception;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.constants.LogFields;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single global handler producing RFC 7807 {@link ProblemDetail} responses
 * (rules A10.1, A10.2, A10.7, 17.4).
 *
 * <p>Every error body includes the {@code traceId} from MDC so clients
 * and operators can correlate logs (rule A10.6, 17.5).</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Custom problem field name for the stable error code. */
    private static final String FIELD_ERROR_CODE = "errorCode";

    /** Custom problem field name for the correlation id. */
    private static final String FIELD_TRACE_ID = "traceId";

    /** Custom problem field name for the response timestamp. */
    private static final String FIELD_TIMESTAMP = "timestamp";

    /**
     * Maps {@link RateLimitedException} to {@code 429 Too Many Requests}
     * (RFC 6585 \u00a74). The {@code Retry-After} header is set from the
     * exception's {@code retryAfterSeconds}. The {@code X-RateLimit-*}
     * triple is NOT re-asserted here because
     * {@link io.cortex.gateway.filter.RateLimitFilter} already set them
     * on the response before throwing; re-setting via
     * {@link ResponseEntity#header} would emit duplicate header values.
     *
     * @param ex      thrown rate-limit exception
     * @param request inbound HTTP request
     * @return 429 problem response with retry-after header
     */
    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(
            final RateLimitedException ex, final HttpServletRequest request) {
        final ProblemDetail body = buildProblem(
                HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, ex.getMessage(), request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HeaderNames.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .body(body);
    }

    /**
     * Maps every {@link ApplicationException} to a problem response. The
     * HTTP status is derived from the carried {@link ErrorCodes} value.
     *
     * @param ex      thrown application exception
     * @param request inbound HTTP request (used to populate {@code instance})
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ProblemDetail> handleApplication(
            final ApplicationException ex, final HttpServletRequest request) {
        final HttpStatus status = mapStatus(ex.getErrorCode());
        log.warn("application exception code={} status={} message={}",
                ex.getErrorCode(), status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(
                buildProblem(status, ex.getErrorCode(), ex.getMessage(), request));
    }

    /**
     * Maps Spring Security authentication failures to {@code 401 Unauthorized}.
     *
     * @param ex      thrown authentication exception
     * @param request inbound HTTP request
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuth(
            final AuthenticationException ex, final HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                buildProblem(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHENTICATED,
                        ex.getMessage(), request));
    }

    /**
     * Maps Spring Security authorization failures to {@code 403 Forbidden}.
     *
     * @param ex      thrown access-denied exception
     * @param request inbound HTTP request
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleDenied(
            final AccessDeniedException ex, final HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                buildProblem(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN,
                        ex.getMessage(), request));
    }

    /**
     * Maps {@code @Valid} body / parameter failures to {@code 400 Bad Request}
     * with {@link ErrorCodes#VALIDATION_FAILED}. The detail message is the
     * concatenated default messages of each binding violation.
     *
     * @param ex      thrown validation exception
     * @param request inbound HTTP request
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            final MethodArgumentNotValidException ex, final HttpServletRequest request) {
        final String detail = ex.getBindingResult().getAllErrors().stream()
                .map(err -> err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                buildProblem(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
                        detail.isEmpty() ? "validation failed" : detail, request));
    }

    /**
     * Maps {@link NoResourceFoundException} (thrown by Spring MVC when no
     * handler resolves a request, e.g. an authenticated bearer hitting an
     * unknown sub-path) to {@code 404 Not Found} with
     * {@link ErrorCodes#NOT_FOUND}. Without this, the catch-all
     * {@link #handleUnexpected} would return {@code 500} (Part 21 Level 3
     * failure caught by `scripts/smoke-p3-1.ps1` test 18).
     *
     * @param ex      thrown no-resource-found exception
     * @param request inbound HTTP request
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(
            final NoResourceFoundException ex, final HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                buildProblem(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND,
                        "not found", request));
    }

    /**
     * Last-resort catch-all. Logs at {@code ERROR} with full stack and
     * returns {@code 500} with a generic problem body. The internal
     * message is NEVER leaked to the client (rule 18.1, A8.6).
     *
     * @param ex      unexpected throwable
     * @param request inbound HTTP request
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            final Throwable ex, final HttpServletRequest request) {
        log.error("unhandled gateway error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                        "internal error", request));
    }

    /**
     * Builds a populated {@link ProblemDetail} including the custom
     * gateway fields and the correlation id from MDC.
     *
     * @param status   HTTP status to return
     * @param code     stable error code
     * @param detail   human-readable detail
     * @param request  inbound HTTP request used for the {@code instance} URI
     * @return populated problem detail
     */
    private static ProblemDetail buildProblem(
            final HttpStatus status,
            final ErrorCodes code,
            final String detail,
            final HttpServletRequest request) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty(FIELD_ERROR_CODE, code.name());
        problem.setProperty(FIELD_TIMESTAMP, OffsetDateTime.now().toString());
        final String traceId = MDC.get(LogFields.TRACE_ID);
        if (traceId != null) {
            problem.setProperty(FIELD_TRACE_ID, traceId);
        }
        return problem;
    }

    /**
     * Maps a stable {@link ErrorCodes} value to the matching HTTP status.
     *
     * @param code stable error code
     * @return HTTP status; defaults to {@code 500} for unmapped codes
     */
    private static HttpStatus mapStatus(final ErrorCodes code) {
        return switch (code) {
            case VALIDATION_FAILED, BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UPSTREAM_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
