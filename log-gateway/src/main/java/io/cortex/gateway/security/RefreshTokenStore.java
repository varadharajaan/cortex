package io.cortex.gateway.security;

import java.time.Instant;

/**
 * Tracks refresh-token identifiers so refresh is single-use and revocable
 * (rule B7.5).
 *
 * <p>P3.1 ships an in-memory implementation
 * ({@link InMemoryRefreshTokenStore}). A later sub-phase swaps in a
 * Redis-backed implementation so revocation survives a restart and
 * works across replicas.</p>
 */
public interface RefreshTokenStore {

    /**
     * Records a freshly issued refresh-token id.
     *
     * @param jti       the {@code jti} claim of the refresh token
     * @param subject   the principal the token was issued to
     * @param expiresAt the refresh token's wall-clock expiry
     */
    void register(String jti, String subject, Instant expiresAt);

    /**
     * Atomically removes the supplied {@code jti} and returns whether it
     * was present (i.e. a valid, single-use refresh token).
     *
     * @param jti the {@code jti} claim of the refresh token to consume
     * @return {@code true} when the token id was known and not expired
     */
    boolean consume(String jti);
}
