package io.cortex.ingest.security;

import io.cortex.ingest.constants.HeaderNames;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-JWT inbound filter scaffold per O8 / LD39 / ADR-0020.
 *
 * <p>P4.0 ships the filter as a no-op by default
 * ({@link ServiceJwtProperties#required()} is {@code false}). When
 * the flag is enabled (P5.x rollout), the filter rejects any request
 * to {@code /api/**} that does not carry an
 * {@value HeaderNames#SERVICE_JWT} header with {@code 401
 * Unauthorized}. Actuator endpoints under {@code /actuator/**} stay
 * unguarded so Kubernetes probes keep working.</p>
 *
 * <p>Full JWT parsing + signature validation lands in P5.x; P4.0 is
 * intentionally limited to presence checking so the scaffold can be
 * verified end-to-end without a Vault / OIDC issuer running.</p>
 */
@Component
public class ServiceJwtFilter extends OncePerRequestFilter {

    /** Configuration backing this filter. */
    private final ServiceJwtProperties properties;

    /**
     * Constructs the filter with its bound configuration.
     *
     * @param properties typed configuration; must not be {@code null}
     */
    public ServiceJwtFilter(final ServiceJwtProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain)
            throws ServletException, IOException {
        if (!this.properties.required()) {
            chain.doFilter(request, response);
            return;
        }
        final String header = request.getHeader(HeaderNames.SERVICE_JWT);
        if (header == null || header.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        final String uri = request.getRequestURI();
        return uri.startsWith("/actuator")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui");
    }
}
