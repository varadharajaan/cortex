package io.cortex.ingest.tenant;

import io.cortex.ingest.constants.ErrorCodes;
import io.cortex.ingest.exception.ApplicationException;
import org.springframework.stereotype.Component;

/**
 * Resolves the active tenant id for an inbound ingest request
 * (P4.1 / D5).
 *
 * <p>P4.1 honours only the {@code X-Tenant-Id} header. P5.x will
 * extend this resolver to prefer a {@code tenant_id} claim from the
 * verified service-JWT (parsed by
 * {@link io.cortex.ingest.security.ServiceJwtFilter}) and fall back
 * to the header for service-to-service callers without a token. The
 * header path is kept stable so wired Postman / smoke contracts do
 * not churn between the two sub-phases.</p>
 */
@Component
public class TenantResolver {

    /** Default constructor used by Spring. */
    public TenantResolver() {
        // no state
    }

    /**
     * Resolves the tenant id from the supplied {@code X-Tenant-Id}
     * header value.
     *
     * @param headerValue verbatim header value as received from the
     *                    HTTP request; may be {@code null} or blank
     * @return trimmed, non-blank tenant id
     * @throws ApplicationException with {@link ErrorCodes#VALIDATION_FAILED}
     *                              when the header is missing, blank,
     *                              or whitespace-only
     */
    public String resolve(final String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED,
                    "X-Tenant-Id header is required");
        }
        return headerValue.trim();
    }
}
