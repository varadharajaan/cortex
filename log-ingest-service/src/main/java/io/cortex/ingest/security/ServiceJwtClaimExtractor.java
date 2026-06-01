package io.cortex.ingest.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Parses the {@code tid} (tenant id) claim out of an inbound
 * {@code X-Cortex-Service-JWT} header WITHOUT validating the
 * signature (P4.3 / plan.md row 169).
 *
 * <p>Signature verification + issuer / audience / expiry checks
 * are deferred to P5.x / B7.1 when the cluster-wide mTLS + OIDC
 * scaffold lands (ADR-0020). For P4.3 the extractor exists so
 * the tenant-resolution code path is wired end-to-end against
 * the JWT-first / header-fallback contract; the signature gate
 * follows in a separate sub-phase with a Nimbus dependency.</p>
 *
 * <p>The extractor is intentionally lenient: any parse failure
 * (malformed compact serialisation, invalid base64, non-JSON
 * payload, missing claim) returns {@link Optional#empty()} so
 * the caller can fall back to the header path. The extractor
 * never throws on invalid input.</p>
 */
@Component
public class ServiceJwtClaimExtractor {

    /** Canonical claim name carrying the tenant id. */
    public static final String CLAIM_TENANT_ID = "tid";

    /** Expected segment count of a compact-serialised JWT. */
    private static final int JWT_SEGMENTS = 3;

    /** Index of the payload segment in the compact serialisation. */
    private static final int PAYLOAD_INDEX = 1;

    /** Jackson decoder; shared instance is thread-safe. */
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection of the shared Jackson decoder.
     *
     * @param objectMapper shared Jackson decoder; must not be
     *                     {@code null}
     */
    public ServiceJwtClaimExtractor(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the {@code tid} claim from the supplied JWT
     * header value, or {@link Optional#empty()} when the header
     * is absent, malformed, or the claim is missing / blank.
     *
     * @param jwtHeader verbatim header value as received from
     *                  the HTTP request; may be {@code null}
     * @return non-blank tenant id when extraction succeeds;
     *         empty otherwise
     */
    public Optional<String> extractTenantId(final String jwtHeader) {
        if (jwtHeader == null || jwtHeader.isBlank()) {
            return Optional.empty();
        }
        final String[] segments = jwtHeader.trim().split("\\.", -1);
        if (segments.length != JWT_SEGMENTS) {
            return Optional.empty();
        }
        try {
            final byte[] payload = Base64.getUrlDecoder()
                    .decode(segments[PAYLOAD_INDEX]);
            final JsonNode root = this.objectMapper
                    .readTree(new String(payload, StandardCharsets.UTF_8));
            final JsonNode tid = root.get(CLAIM_TENANT_ID);
            if (tid == null || !tid.isTextual()) {
                return Optional.empty();
            }
            final String value = tid.asText().trim();
            if (value.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (IllegalArgumentException | java.io.IOException ex) {
            return Optional.empty();
        }
    }
}
