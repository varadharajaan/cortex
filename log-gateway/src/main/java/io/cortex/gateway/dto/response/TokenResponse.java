package io.cortex.gateway.dto.response;

import java.time.OffsetDateTime;

/**
 * Token response body returned by {@code POST /api/v1/auth/login} and
 * {@code POST /api/v1/auth/refresh}.
 *
 * @param accessToken     signed access JWT (present as a Bearer credential)
 * @param refreshToken    signed refresh JWT (single-use)
 * @param tokenType       OAuth2 token type; always {@code "Bearer"}
 * @param accessExpiresAt wall-clock expiry of {@code accessToken}, UTC
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        OffsetDateTime accessExpiresAt) {
}
