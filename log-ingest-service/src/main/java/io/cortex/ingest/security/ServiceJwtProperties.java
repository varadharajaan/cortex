package io.cortex.ingest.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the inbound service-JWT scaffold mandated by
 * O8 / LD39 / ADR-0020.
 *
 * <p>P4.0 ships the scaffold disabled by default
 * ({@code required=false}) so the local-dev experience does not
 * require minting a service JWT. P5.x enforces it cluster-wide.</p>
 *
 * @param required        when {@code true}, every inbound HTTP
 *                        request MUST carry a valid
 *                        {@code X-Cortex-Service-JWT} header
 *                        (validation lands in P5.x; P4.0 only checks
 *                        presence)
 * @param expectedIssuer  expected JWT {@code iss} claim once
 *                        validation lands; reserved for forward use
 */
@ConfigurationProperties(prefix = "cortex.security.service-jwt")
public record ServiceJwtProperties(
        boolean required,
        String expectedIssuer) {

    /**
     * Canonical constructor with defaults: when {@code required} is
     * not set it stays {@code false}; when {@code expectedIssuer} is
     * not set it falls back to the empty string so the property
     * binder never injects {@code null} into the filter.
     */
    public ServiceJwtProperties {
        if (expectedIssuer == null) {
            expectedIssuer = "";
        }
    }
}
