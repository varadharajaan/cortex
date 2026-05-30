package io.cortex.gateway.service;

import io.cortex.gateway.dto.response.TokenResponse;

/**
 * Verifies caller credentials and rotates JWT pairs (rule B7.1 / B7.5).
 *
 * <p>Implementations encapsulate the user-store lookup and password
 * verification (Argon2 per rule B7.3) and delegate to the JWT issuer
 * for signing.</p>
 */
public interface AuthService {

    /**
     * Authenticates the supplied credentials and returns a fresh token pair.
     *
     * @param username caller-supplied login name; must not be {@code null}
     * @param password caller-supplied plaintext password; must not be {@code null}
     * @return the access + refresh tokens with the access-token expiry
     */
    TokenResponse login(String username, String password);

    /**
     * Validates and consumes the supplied refresh token then re-issues
     * a fresh access + refresh pair for the same subject.
     *
     * @param refreshToken the refresh JWT presented by the client
     * @return the new access + refresh tokens
     */
    TokenResponse refresh(String refreshToken);
}
