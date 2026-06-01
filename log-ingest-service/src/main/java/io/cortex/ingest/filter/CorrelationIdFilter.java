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
 * Source of truth is the inbound {@code X-Request-Id} header set by
 * the upstream log-gateway; if absent, a UUID is generated so this
 * service can still be invoked directly during smoke tests. The value
 * is echoed back as the same response header so callers can correlate
 * across hops.</p>
 *
 * <p>Runs as the highest-precedence filter so every downstream
 * component (controller, JDBC writer, error handler) sees the id in
 * MDC.</p>
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

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
        response.setHeader(HeaderNames.X_REQUEST_ID, traceId);
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
     * Returns the inbound correlation id when present and non-blank,
     * otherwise a freshly minted UUID.
     *
     * @param request inbound HTTP request
     * @return non-null, non-blank trace id
     */
    private static String resolveTraceId(final HttpServletRequest request) {
        final String inbound = request.getHeader(HeaderNames.X_REQUEST_ID);
        if (inbound != null && !inbound.isBlank()) {
            return inbound;
        }
        return UUID.randomUUID().toString();
    }
}
