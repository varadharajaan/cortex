package io.cortex.indexer.controller;

import java.util.stream.Collectors;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps client-input failures from {@link SearchController} to
 * {@code 400 Bad Request} RFC 7807 {@link ProblemDetail} bodies
 * (P9.1a / ADR-0042 Amendment 1).
 *
 * <p>Scoped to {@link SearchController} via
 * {@code assignableTypes} so it does not alter actuator or any
 * future controller's error handling. The search SPI itself never
 * throws (ADR-0042 D6) -- every <em>downstream</em> failure is a
 * verdict the controller maps to a non-400 status -- so this advice
 * only covers <em>client-input</em> faults:</p>
 * <ul>
 *   <li>{@link MissingRequestHeaderException} -- the required
 *       {@code X-Tenant-Id} header was absent.</li>
 *   <li>{@link MethodArgumentNotValidException} -- a
 *       {@code @Valid} body constraint failed
 *       (blank {@code indexId} / {@code query}, non-positive
 *       {@code maxHits}).</li>
 *   <li>{@link IllegalArgumentException} -- the domain
 *       {@code SearchRequest} canonical constructor rejected a
 *       blank header value (e.g. a present-but-empty
 *       {@code X-Tenant-Id}).</li>
 *   <li>{@link HttpMessageNotReadableException} -- a malformed or
 *       absent JSON body.</li>
 * </ul>
 */
@RestControllerAdvice(assignableTypes = SearchController.class)
public class SearchControllerAdvice {

    /** Stable error code surfaced in the {@code errorCode} problem property. */
    static final String ERROR_CODE = "SEARCH_BAD_REQUEST";

    /**
     * Maps an absent required request header to 400.
     *
     * @param ex the missing-header exception
     * @return a 400 problem body naming the absent header
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(
            final MissingRequestHeaderException ex) {
        return badRequest(ex.getHeaderName() + " header is required");
    }

    /**
     * Maps a failed {@code @Valid} body constraint to 400.
     *
     * @param ex the validation exception
     * @return a 400 problem body concatenating each violation message
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            final MethodArgumentNotValidException ex) {
        final String detail = ex.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return badRequest(detail.isBlank() ? "validation failed" : detail);
    }

    /**
     * Maps a domain-record validation failure (blank header value)
     * to 400.
     *
     * @param ex the illegal-argument exception thrown by the
     *           {@code SearchRequest} canonical constructor
     * @return a 400 problem body carrying the rejection message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(
            final IllegalArgumentException ex) {
        return badRequest(ex.getMessage());
    }

    /**
     * Maps a malformed / absent JSON body to 400.
     *
     * @param ex the message-not-readable exception
     * @return a 400 problem body
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(
            final HttpMessageNotReadableException ex) {
        return badRequest("request body is missing or malformed");
    }

    /**
     * Builds a 400 RFC 7807 problem body with the stable
     * {@link #ERROR_CODE}.
     *
     * @param detail human-readable detail message
     * @return a populated {@link ProblemDetail}
     */
    private static ProblemDetail badRequest(final String detail) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, detail);
        problem.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        problem.setProperty("errorCode", ERROR_CODE);
        return problem;
    }
}
