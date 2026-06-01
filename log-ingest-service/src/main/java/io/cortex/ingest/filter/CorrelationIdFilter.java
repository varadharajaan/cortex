package io.cortex.ingest.filter;

import io.cortex.ingest.constants.HeaderNames;
import io.cortex.ingest.constants.LogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads or mints the per-request correlation id and pins it to MDC.
 *
 * <p>Rule A8.2 and 17.5: every log line MUST carry a {@code traceId}.
 * Inbound resolution order (P4.3 / plan row 169):</p>
 * <ol>
 *   <li>{@code X-Request-Id} header (set by upstream log-gateway).</li>
 *   <li>{@code X-Correlation-Id} header (set by external callers
 *       that use the W3C-style name).</li>
 *   <li>A freshly minted UUID when both are absent so this service
 *       can be invoked directly during smoke tests.</li>
 * </ol>
 *
 * <p>The resolved id is echoed back as both {@code X-Request-Id}
 * and {@code X-Correlation-Id} response headers so callers using
 * either name can correlate across hops. It is also stashed as a
 * request attribute under {@link #ATTRIBUTE_CORRELATION_ID} so
 * downstream controllers can forward it into the persisted event
 * without re-reading MDC (LD3: avoid string-key MDC reads at call
 * sites).</p>
 *
 * <p>Runs as the highest-precedence filter so every downstream
 * component (controller, JDBC writer, error handler) sees the id in
 * MDC.</p>
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    /**
     * Request attribute key under which the resolved correlation
     * id is exposed to downstream handlers (P4.3 / LD3).
     */
    public static final String ATTRIBUTE_CORRELATION_ID =
            CorrelationIdFilter.class.getName() + ".correlationId";

    /**
     * Servlet filter callback. Sets MDC before the chain runs and
     * clears it in {@code finally} so the worker thread cannot leak ids
     * between requests (rule A8.5).
     *
     * @param request  inbound HTTP request
     * @param response outbound HTTP response
     * @param chain    remaining filter chain
     * @throws ServletException re-thrown from the chain
     * @throws IOException      re-thrown from the chain
     */
    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain) throws ServletException, IOException {
        final String traceId = resolveTraceId(request);
        MDC.put(LogFields.TRACE_ID, traceId);
        request.setAttribute(ATTRIBUTE_CORRELATION_ID, traceId);
        response.setHeader(HeaderNames.X_REQUEST_ID, traceId);
        response.setHeader(HeaderNames.CORRELATION_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LogFields.TRACE_ID);
        }
    }

    /**
     * Returns the highest precedence so this filter runs first.
     *
     * @return Spring's highest precedence ordinal
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Returns the first non-blank inbound correlation id header
     * ({@code X-Request-Id} then {@code X-Correlation-Id}), or a
     * freshly minted UUID when both are absent.
     *
     * @param request inbound HTTP request
     * @return non-null, non-blank trace id
     */
    private static String resolveTraceId(final HttpServletRequest request) {
        final String requestId = request.getHeader(HeaderNames.X_REQUEST_ID);
        if (requestId != null && !requestId.isBlank()) {
            return requestId.trim();
        }
        final String correlationId = request.getHeader(HeaderNames.CORRELATION_ID);
        if (correlationId != null && !correlationId.isBlank()) {
            return correlationId.trim();
        }
        return UUID.randomUUID().toString();
    }
}
