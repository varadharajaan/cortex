package io.cortex.gateway.security;

import java.time.OffsetDateTime;

/**
 * Pair of access + refresh tokens emitted by {@link JwtIssuer}.
 *
 * @param accessToken      signed JWT presented as a bearer credential
 * @param refreshToken     signed JWT used to obtain a new pair
 * @param accessExpiresAt  wall-clock expiry of the access token (UTC)
 */
public record IssuedTokens(
        String accessToken,
        String refreshToken,
        OffsetDateTime accessExpiresAt) {
}
