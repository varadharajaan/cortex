package io.cortex.gateway.security;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.exception.ApplicationException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Nimbus-backed {@link JwtIssuer} (rule B7.1).
 *
 * <p>Issues HS256-signed access and refresh JWTs. Refresh tokens are
 * single-use: each issued refresh embeds a fresh {@code jti}, recorded
 * with {@link RefreshTokenStore}; consumption removes it atomically so
 * a leaked refresh cannot be replayed (rule B7.5).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NimbusJwtIssuer implements JwtIssuer {

    /** Claim name for the role list embedded in access tokens. */
    public static final String CLAIM_ROLES = "roles";

    /** Claim name distinguishing access from refresh tokens. */
    public static final String CLAIM_TOKEN_TYPE = "typ";

    /** Value of {@link #CLAIM_TOKEN_TYPE} for access tokens. */
    public static final String TYPE_ACCESS = "access";

    /** Value of {@link #CLAIM_TOKEN_TYPE} for refresh tokens. */
    public static final String TYPE_REFRESH = "refresh";

    /** Token encoder bound to the symmetric HMAC key. */
    private final JwtEncoder encoder;

    /** Token decoder bound to the same HMAC key (signature + exp checks). */
    private final JwtDecoder decoder;

    /** Typed JWT configuration (issuer + TTLs). */
    private final JwtProperties properties;

    /** Refresh-token registry enforcing single-use semantics. */
    private final RefreshTokenStore refreshStore;

    /** Clock used for all token time stamps; overridable in tests. */
    private final Clock clock;

    @Override
    public IssuedTokens issue(final String subject, final Collection<String> roles) {
        final Instant now = Instant.now(this.clock);
        final Instant accessExp = now.plus(this.properties.accessTtl());
        final Instant refreshExp = now.plus(this.properties.refreshTtl());
        final String refreshJti = UUID.randomUUID().toString();

        final String access = encode(JwtClaimsSet.builder()
                .issuer(this.properties.issuer())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(accessExp)
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_ROLES, List.copyOf(roles))
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .build());

        final String refresh = encode(JwtClaimsSet.builder()
                .issuer(this.properties.issuer())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(refreshExp)
                .id(refreshJti)
                .claim(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
                .build());

        this.refreshStore.register(refreshJti, subject, refreshExp);
        log.debug("issued tokens subject={} accessExp={} refreshJti={}", subject, accessExp, refreshJti);

        return new IssuedTokens(access, refresh, OffsetDateTime.ofInstant(accessExp, ZoneOffset.UTC));
    }

    @Override
    public String consumeRefreshAndReturnSubject(final String refreshToken) {
        final Jwt decoded = decodeOrReject(refreshToken);
        if (!TYPE_REFRESH.equals(decoded.getClaimAsString(CLAIM_TOKEN_TYPE))) {
            throw new ApplicationException(ErrorCodes.UNAUTHENTICATED, "wrong token type");
        }
        final String jti = decoded.getId();
        if (jti == null || !this.refreshStore.consume(jti)) {
            throw new ApplicationException(ErrorCodes.UNAUTHENTICATED, "refresh token revoked or unknown");
        }
        return decoded.getSubject();
    }

    /**
     * Signs the supplied claims with the symmetric HMAC key using an
     * explicit HS256 header. Spring Security's {@code NimbusJwtEncoder}
     * defaults to RS256 when no header is supplied, which would never
     * match a symmetric key.
     *
     * @param claims populated claim set
     * @return the encoded JWT string
     */
    private String encode(final JwtClaimsSet claims) {
        final JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /**
     * Decodes and validates a JWT or throws {@link ApplicationException}
     * with {@link ErrorCodes#UNAUTHENTICATED}.
     *
     * @param token the raw JWT value
     * @return the decoded JWT
     * @throws ApplicationException if the token is malformed, tampered, or expired
     */
    private Jwt decodeOrReject(final String token) {
        try {
            return this.decoder.decode(token);
        } catch (JwtException ex) {
            throw new ApplicationException(ErrorCodes.UNAUTHENTICATED, "invalid token", ex);
        }
    }
}
