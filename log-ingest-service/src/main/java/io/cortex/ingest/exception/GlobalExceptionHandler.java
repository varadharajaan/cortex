package io.cortex.ingest.exception;

import io.cortex.ingest.constants.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Single global handler producing RFC 7807 {@link ProblemDetail}
 * responses for log-ingest-service (rules A10.1, A10.2, A10.7,
 * 17.4). Mirrors the gateway pattern so cross-service clients see a
 * uniform error contract.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Custom problem field name for the stable error code. */
    private static final String FIELD_ERROR_CODE = "errorCode";

    /** Custom problem field name for the response timestamp. */
    private static final String FIELD_TIMESTAMP = "timestamp";

    /** SLF4J logger (rule A8.7). */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Default constructor used by Spring. */
    public GlobalExceptionHandler() {
        // no state; Spring instantiates via reflection
    }

    /**
     * Maps every {@link ApplicationException} to a problem response.
     * The HTTP status is derived from the carried {@link ErrorCodes}
     * value.
     *
     * @param ex      thrown application exception
     * @param request inbound HTTP request (used to populate
     *                {@code instance})
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ProblemDetail> handleApplication(
            final ApplicationException ex, final HttpServletRequest request) {
        final HttpStatus status = mapStatus(ex.getErrorCode());
        LOG.warn("application exception code={} status={} message={}",
                ex.getErrorCode(), status.value(), ex.getMessage());
        return ResponseEntity.status(status).body(
                buildProblem(status, ex.getErrorCode(), ex.getMessage(), request));
    }

    /**
     * Maps {@code @Valid} body / parameter failures to
     * {@code 400 Bad Request} with
     * {@link ErrorCodes#VALIDATION_FAILED}.
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
     * Maps body-parse failures (malformed JSON, missing body, etc.)
     * to {@code 400 Bad Request} with
     * {@link ErrorCodes#BAD_REQUEST}.
     *
     * @param ex      thrown read exception
     * @param request inbound HTTP request
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(
            final HttpMessageNotReadableException ex, final HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                buildProblem(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                        "malformed request body", request));
    }

    /**
     * Maps {@link NoResourceFoundException} to {@code 404 Not Found}
     * with {@link ErrorCodes#NOT_FOUND}.
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
     * Last-resort catch-all. Logs at {@code ERROR} with full stack
     * and returns {@code 500} with a generic problem body. The
     * internal message is NEVER leaked to the client (rule 18.1,
     * A8.6).
     *
     * @param ex      unexpected throwable
     * @param request inbound HTTP request
     * @return RFC 7807 problem detail wrapped in a response entity
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            final Throwable ex, final HttpServletRequest request) {
        LOG.error("unhandled ingest error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                buildProblem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                        "internal error", request));
    }

    /**
     * Builds a populated {@link ProblemDetail} including the custom
     * extension fields.
     *
     * @param status   HTTP status to return
     * @param code     stable error code
     * @param detail   human-readable detail
     * @param request  inbound HTTP request used for the
     *                 {@code instance} URI
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
        return problem;
    }

    /**
     * Maps a stable {@link ErrorCodes} value to the matching HTTP
     * status.
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
