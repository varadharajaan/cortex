package io.cortex.ingest.tenant;

import io.cortex.ingest.constants.ErrorCodes;
import io.cortex.ingest.exception.ApplicationException;
import io.cortex.ingest.security.ServiceJwtClaimExtractor;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the active tenant id for an inbound ingest request
 * (P4.1 / P4.3 / D5).
 *
 * <p>Resolution order (P4.3 / plan row 169):</p>
 * <ol>
 *   <li>The {@code tid} claim of the inbound
 *       {@code X-Cortex-Service-JWT} header (parsed without
 *       signature verification; full verify is deferred to
 *       P5.x / B7.1 per ADR-0020).</li>
 *   <li>The {@code X-Tenant-Id} header (kept as the
 *       service-to-service fallback so legacy callers that
 *       have not minted a JWT yet still work).</li>
 * </ol>
 *
 * <p>When neither input is present, the resolver throws
 * {@link ApplicationException} with
 * {@link ErrorCodes#VALIDATION_FAILED} so the global handler
 * surfaces an RFC 7807 400.</p>
 */
@Component
public class TenantResolver {

    /** JWT claim extractor (P4.3); never {@code null}. */
    private final ServiceJwtClaimExtractor jwtClaimExtractor;

    /**
     * Constructor injection of the JWT claim extractor.
     *
     * @param jwtClaimExtractor extractor that parses the
     *                          {@code tid} claim from the
     *                          service JWT payload; must not
     *                          be {@code null}
     */
    public TenantResolver(final ServiceJwtClaimExtractor jwtClaimExtractor) {
        this.jwtClaimExtractor = jwtClaimExtractor;
    }

    /**
     * Resolves the tenant id from the supplied
     * {@code X-Tenant-Id} header value. Retained for the
     * tests + legacy call sites that do not carry a service
     * JWT. Equivalent to {@link #resolve(String, String)}
     * with a {@code null} JWT header.
     *
     * @param headerValue verbatim header value as received from
     *                    the HTTP request; may be {@code null}
     *                    or blank
     * @return trimmed, non-blank tenant id
     * @throws ApplicationException with
     *                              {@link ErrorCodes#VALIDATION_FAILED}
     *                              when the header is missing,
     *                              blank, or whitespace-only
     */
    public String resolve(final String headerValue) {
        return this.resolve(null, headerValue);
    }

    /**
     * JWT-first / header-fallback resolution (P4.3).
     *
     * @param jwtHeader    verbatim
     *                     {@code X-Cortex-Service-JWT} header
     *                     value; may be {@code null}
     * @param tenantHeader verbatim {@code X-Tenant-Id} header
     *                     value; may be {@code null} or blank
     *                     (only inspected when the JWT path
     *                     yields nothing)
     * @return trimmed, non-blank tenant id
     * @throws ApplicationException with
     *                              {@link ErrorCodes#VALIDATION_FAILED}
     *                              when neither input yields a
     *                              non-blank value
     */
    public String resolve(final String jwtHeader, final String tenantHeader) {
        final Optional<String> fromJwt = this.jwtClaimExtractor.extractTenantId(jwtHeader);
        if (fromJwt.isPresent()) {
            return fromJwt.get();
        }
        if (tenantHeader == null || tenantHeader.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "X-Tenant-Id header is required when no service JWT is present");
        }
        return tenantHeader.trim();
    }
}
