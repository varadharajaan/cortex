package io.cortex.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.exception.ApplicationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Unit tests for {@link NimbusJwtIssuer}: end-to-end issue + decode round
 * trip, claim shape, refresh single-use semantics, and rejection of
 * tampered / wrong-type tokens.
 */
class NimbusJwtIssuerTest {

    /** Fixed wall-clock for deterministic exp claims. */
    private static final Instant NOW = Instant.parse("2026-05-30T10:00:00Z");

    /** Deterministic 32-byte HMAC key encoded with the test secret. */
    private static final String SECRET_B64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** Issuer claim asserted on every emitted token. */
    private static final String ISSUER = "cortex-gateway-test";

    /** Fixed UTC clock pinned at {@link #NOW}. */
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Issuer under test (constructed in {@link #setUp()}). */
    private NimbusJwtIssuer issuer;

    /** Refresh store backing the issuer (constructed in {@link #setUp()}). */
    private InMemoryRefreshTokenStore refreshStore;

    /** Decoder used to inspect emitted tokens. */
    private JwtDecoder decoder;

    /** Wires fresh collaborators per test so cases are isolated. */
    @BeforeEach
    void setUp() {
        final SecretKey key = new SecretKeySpec(
                Base64.getDecoder().decode(SECRET_B64), MacAlgorithm.HS256.getName());
        final OctetSequenceKey jwk = new OctetSequenceKey.Builder(key.getEncoded())
                .algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
                .build();
        final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        final NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        final JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(this.clock);
        nimbusDecoder.setJwtValidator(timestampValidator);
        this.decoder = nimbusDecoder;
        this.refreshStore = new InMemoryRefreshTokenStore(this.clock);
        final JwtProperties props = new JwtProperties(
                SECRET_B64, ISSUER, Duration.ofMinutes(15), Duration.ofDays(1));
        this.issuer = new NimbusJwtIssuer(encoder, this.decoder, props, this.refreshStore, this.clock);
    }

    /** {@link NimbusJwtIssuer#issue} produces a signed access token with the expected claims. */
    @Test
    void issuesAccessTokenWithSubjectIssuerAndRoles() {
        final IssuedTokens tokens = this.issuer.issue("alice", List.of("ADMIN", "USER"));

        final Jwt access = this.decoder.decode(tokens.accessToken());
        assertThat(access.getSubject()).isEqualTo("alice");
        assertThat(access.getClaimAsString("iss")).isEqualTo(ISSUER);
        assertThat(access.getClaimAsString(NimbusJwtIssuer.CLAIM_TOKEN_TYPE)).isEqualTo(NimbusJwtIssuer.TYPE_ACCESS);
        assertThat(access.<List<String>>getClaim(NimbusJwtIssuer.CLAIM_ROLES))
                .containsExactly("ADMIN", "USER");
        assertThat(access.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    /** {@link NimbusJwtIssuer#issue} produces a refresh token that is registered with the store. */
    @Test
    void issuesRefreshTokenAndRegistersJti() {
        final IssuedTokens tokens = this.issuer.issue("alice", List.of("USER"));
        final Jwt refresh = this.decoder.decode(tokens.refreshToken());

        assertThat(refresh.getClaimAsString(NimbusJwtIssuer.CLAIM_TOKEN_TYPE)).isEqualTo(NimbusJwtIssuer.TYPE_REFRESH);
        assertThat(refresh.getId()).isNotBlank();
        // jti is now consumable - meaning it was registered.
        assertThat(this.refreshStore.consume(refresh.getId())).isTrue();
    }

    /** Refresh consume returns the subject and burns the jti (single-use). */
    @Test
    void consumeRefreshReturnsSubjectAndIsSingleUse() {
        final IssuedTokens tokens = this.issuer.issue("alice", List.of("USER"));

        final String subject = this.issuer.consumeRefreshAndReturnSubject(tokens.refreshToken());
        assertThat(subject).isEqualTo("alice");

        // Second consume must fail because the jti was removed.
        assertThatThrownBy(() -> this.issuer.consumeRefreshAndReturnSubject(tokens.refreshToken()))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCodes.UNAUTHENTICATED);
    }

    /** Presenting an access token to the refresh endpoint is rejected. */
    @Test
    void consumeRefreshRejectsAccessTokenType() {
        final IssuedTokens tokens = this.issuer.issue("alice", List.of("USER"));

        assertThatThrownBy(() -> this.issuer.consumeRefreshAndReturnSubject(tokens.accessToken()))
                .isInstanceOf(ApplicationException.class)
                .hasMessage("wrong token type");
    }

    /** A tampered or malformed token is rejected with UNAUTHENTICATED. */
    @Test
    void consumeRefreshRejectsTamperedToken() {
        assertThatThrownBy(() -> this.issuer.consumeRefreshAndReturnSubject("not-a-jwt"))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCodes.UNAUTHENTICATED);
    }
}
