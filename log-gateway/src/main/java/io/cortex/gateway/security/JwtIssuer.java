package io.cortex.gateway.security;

import java.util.Collection;

/**
 * Issues access and refresh JWTs and consumes refresh tokens (rule B7.1, B7.5).
 *
 * <p>Implementations sign with the symmetric secret loaded from
 * {@link JwtProperties} and embed the issuer, subject, roles, and
 * a per-token {@code jti} so refresh tokens are single-use and revocable.</p>
 */
public interface JwtIssuer {

    /**
     * Issues a fresh access + refresh pair for the supplied subject.
     *
     * @param subject authenticated principal name; must not be {@code null}
     * @param roles   roles to embed in the access token; must not be {@code null}
     * @return the signed token pair plus the access-token expiry
     */
    IssuedTokens issue(String subject, Collection<String> roles);

    /**
     * Validates a refresh JWT, atomically marks its {@code jti} consumed,
     * and returns the subject so the caller can re-issue a fresh pair.
     *
     * @param refreshToken the refresh JWT presented by the client
     * @return the subject claim from the refresh token
     */
    String consumeRefreshAndReturnSubject(String refreshToken);
}
