package io.cortex.gateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.gateway.constants.HeaderNames;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Maps {@link RateLimitedException} to an RFC 7807 {@code 429} response
 * for handlers that the {@code @ExceptionHandler} machinery does not
 * cover -- specifically the functional GraphQL HTTP endpoint
 * ({@code POST /graphql}, served by a {@code RouterFunction} via
 * {@code GraphQlWebMvcAutoConfiguration}).
 *
 * <p><strong>Why this exists</strong> (P9.0b / ADR-0049 Amendment 2):
 * {@link GlobalExceptionHandler} is a {@code @RestControllerAdvice}, and
 * Spring's {@code ExceptionHandlerExceptionResolver} only applies
 * {@code @ExceptionHandler} methods to {@link HandlerMethod} handlers
 * (i.e. {@code @RequestMapping} controllers). The GraphQL transport is a
 * functional endpoint, so a {@link RateLimitedException} thrown by
 * {@link io.cortex.gateway.interceptor.RateLimitGraphQlInterceptor}
 * escapes the advice and the servlet container renders a generic
 * {@code 500}. This resolver closes that gap so the GraphQL surface
 * returns the SAME {@code 429} + {@code Retry-After} +
 * {@code application/problem+json} body as the REST surface (the parity
 * contract), reusing {@link ProblemDetailFactory} so the body is
 * byte-identical.</p>
 *
 * <p>The resolver runs at {@link Ordered#HIGHEST_PRECEDENCE} but
 * <em>defers</em> to {@code @ExceptionHandler} for {@link HandlerMethod}
 * handlers (the REST surface) by returning {@code null}, so REST
 * behaviour is unchanged; it only acts on the functional
 * (non-{@code HandlerMethod}) GraphQL endpoint. It is gated by the same
 * {@code cortex.gateway.rate-limit.enabled=true} property as the
 * interceptor that throws the exception, so it is inert on test slices
 * that disable rate limiting.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cortex.gateway.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitProblemExceptionResolver implements HandlerExceptionResolver, Ordered {

    /** Maximum cause-chain depth scanned for a {@link RateLimitedException}. */
    private static final int MAX_CAUSE_DEPTH = 5;

    /** Spring Boot's configured Jackson mapper (carries the ProblemDetail mixin). */
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection of the shared Jackson mapper.
     *
     * @param objectMapper Spring Boot's configured Jackson mapper so the
     *                     emitted body matches the REST surface byte-for-byte
     */
    public RateLimitProblemExceptionResolver(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Resolves a {@link RateLimitedException} thrown from a functional
     * (non-{@code HandlerMethod}) endpoint into a {@code 429} RFC 7807
     * response. Returns {@code null} (no-op) for {@link HandlerMethod}
     * handlers and for any non-rate-limit exception so the normal
     * {@code @ExceptionHandler} chain handles them.
     *
     * @param request  inbound HTTP request
     * @param response HTTP response to write the problem body to
     * @param handler  the resolved handler (or {@code null} for async errors)
     * @param ex       the exception thrown during request handling
     * @return an empty {@link ModelAndView} when handled, otherwise {@code null}
     */
    @Override
    public ModelAndView resolveException(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final Object handler,
            final Exception ex) {
        // The REST surface is served by @RequestMapping HandlerMethods, which
        // GlobalExceptionHandler (@ExceptionHandler) already maps to 429. Defer
        // to it so REST behaviour is untouched; only functional endpoints (the
        // GraphQL RouterFunction, whose handler is NOT a HandlerMethod) fall
        // through to this resolver.
        if (handler instanceof HandlerMethod) {
            return null;
        }
        final RateLimitedException rle = findRateLimited(ex);
        if (rle == null) {
            return null;
        }
        try {
            writeProblem(request, response, rle);
        } catch (final IOException io) {
            log.warn("failed to write GraphQL rate-limit problem response", io);
            return null;
        }
        log.warn("mapped GraphQL RateLimitedException to RFC 7807 429 errorCode={}",
                rle.getErrorCode());
        return new ModelAndView();
    }

    /**
     * Writes the {@code 429} RFC 7807 body, the {@code Retry-After}
     * header, and the {@code application/problem+json} content type,
     * reusing {@link ProblemDetailFactory} so the body is byte-identical
     * to the REST surface.
     *
     * @param request  inbound HTTP request (supplies the {@code instance} URI)
     * @param response HTTP response to populate
     * @param rle      the rate-limit exception carrying the error code +
     *                 retry-after seconds
     * @throws IOException if the response body cannot be written
     */
    private void writeProblem(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final RateLimitedException rle) throws IOException {
        final ProblemDetail problem = ProblemDetailFactory.build(
                HttpStatus.TOO_MANY_REQUESTS,
                rle.getErrorCode(),
                rle.getMessage(),
                request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HeaderNames.RETRY_AFTER, Long.toString(rle.getRetryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        final byte[] payload = this.objectMapper.writeValueAsBytes(problem);
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
        // Commit the body immediately. The GraphQL endpoint dispatches the
        // error through the servlet async machinery; without an explicit
        // flush the unflushed buffer is discarded when the async dispatch
        // finalises the (already status-set) response, yielding a 429 with
        // an EMPTY body (P9.0b live-smoke finding).
        response.flushBuffer();
    }

    /**
     * Scans the exception cause chain (bounded depth) for a
     * {@link RateLimitedException}. The GraphQL transport may wrap the
     * interceptor's exception, so the direct exception and a few cause
     * levels are inspected.
     *
     * @param ex the exception passed to the resolver
     * @return the first {@link RateLimitedException} found, or {@code null}
     */
    private static RateLimitedException findRateLimited(final Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof RateLimitedException rle) {
                return rle;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }
}
