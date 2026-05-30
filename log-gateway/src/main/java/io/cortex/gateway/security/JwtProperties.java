package io.cortex.gateway.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for JWT issuance and validation (rule A6.1, B7.1).
 *
 * <p>Bound from {@code cortex.gateway.security.jwt.*} properties. The
 * application fails fast at startup if any value is missing.
 * The {@code secret} value must be Base64-encoded; the JWT decoder bean
 * verifies it decodes to at least 32 bytes (the HS256 minimum).</p>
 *
 * @param secret     Base64-encoded HMAC key (>= 32 raw bytes after decoding)
 * @param issuer     issuer claim ({@code iss}) embedded in every token
 * @param accessTtl  access-token lifetime
 * @param refreshTtl refresh-token lifetime
 */
@Validated
@ConfigurationProperties(prefix = "cortex.gateway.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTtl,
        @NotNull Duration refreshTtl) {
}
