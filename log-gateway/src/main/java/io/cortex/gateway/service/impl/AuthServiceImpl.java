package io.cortex.gateway.service.impl;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.TokenResponse;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.security.BootstrapUsersProperties;
import io.cortex.gateway.security.BootstrapUsersProperties.BootstrapUser;
import io.cortex.gateway.security.IssuedTokens;
import io.cortex.gateway.security.JwtIssuer;
import io.cortex.gateway.service.AuthService;
import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * In-memory {@link AuthService} (P3.1 placeholder).
 *
 * <p>Loads bootstrap users from configuration, Argon2-hashes their
 * passwords once at startup (rule B7.3), and verifies submitted
 * credentials against the hashed copy. P4 replaces this whole class
 * with a PostgreSQL-backed user store.</p>
 *
 * <p>Login and refresh both produce a fresh access + refresh pair via
 * {@link JwtIssuer}; the previous refresh token is consumed by the
 * issuer so refresh is single-use (rule B7.5).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** Bootstrap user list loaded from {@code cortex.gateway.security.bootstrap.users[*]}. */
    private final BootstrapUsersProperties bootstrap;

    /** Argon2 password encoder bean (rule B7.3). */
    private final PasswordEncoder passwordEncoder;

    /** JWT issuer that signs and rotates tokens. */
    private final JwtIssuer jwtIssuer;

    /** Hashed users keyed by username (immutable after {@link #hashBootstrapUsers}). */
    private Map<String, HashedUser> hashedUsers = Map.of();

    /**
     * Hashes every bootstrap password once at startup so each login
     * verification pays only the {@link PasswordEncoder#matches} cost.
     */
    @PostConstruct
    void hashBootstrapUsers() {
        final Map<String, HashedUser> hashed = new HashMap<>();
        for (final BootstrapUser u : this.bootstrap.users()) {
            hashed.put(u.username(), new HashedUser(
                    this.passwordEncoder.encode(u.password()),
                    List.copyOf(u.roles())));
        }
        this.hashedUsers = Map.copyOf(hashed);
        log.info("loaded {} bootstrap users", this.hashedUsers.size());
    }

    @Override
    public TokenResponse login(final String username, final String password) {
        final HashedUser user = this.hashedUsers.get(username);
        if (user == null || !this.passwordEncoder.matches(password, user.passwordHash())) {
            throw new ApplicationException(ErrorCodes.UNAUTHENTICATED, "invalid credentials");
        }
        return toTokenResponse(this.jwtIssuer.issue(username, user.roles()));
    }

    @Override
    public TokenResponse refresh(final String refreshToken) {
        final String subject = this.jwtIssuer.consumeRefreshAndReturnSubject(refreshToken);
        final HashedUser user = this.hashedUsers.get(subject);
        if (user == null) {
            throw new ApplicationException(ErrorCodes.UNAUTHENTICATED, "subject no longer exists");
        }
        return toTokenResponse(this.jwtIssuer.issue(subject, user.roles()));
    }

    /**
     * Adapts the internal {@link IssuedTokens} value to the HTTP
     * {@link TokenResponse} DTO.
     *
     * @param issued tokens emitted by the issuer
     * @return the HTTP-facing token response
     */
    private static TokenResponse toTokenResponse(final IssuedTokens issued) {
        return new TokenResponse(
                issued.accessToken(),
                issued.refreshToken(),
                "Bearer",
                issued.accessExpiresAt());
    }

    /**
     * Internal record holding the Argon2 hash and role list for a user.
     *
     * @param passwordHash Argon2-hashed password
     * @param roles        immutable role list
     */
    private record HashedUser(String passwordHash, List<String> roles) {
    }
}
