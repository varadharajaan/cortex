package io.cortex.gateway.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.security.JwtProperties;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless HTTP security configuration for the gateway.
 *
 * <p>P3.1 baseline: layers an OAuth2 Resource Server (JWT bearer) on
 * top of the P3.0 stateless filter chain (rule B7.1). The
 * {@code /api/v1/auth/login} and {@code /api/v1/auth/refresh}
 * endpoints stay public so callers can bootstrap a token; every other
 * non-public endpoint requires a valid bearer JWT signed with the same
 * symmetric key the gateway issued.</p>
 *
 * <p>Method-level security ({@code @PreAuthorize}) is enabled per
 * rule 18.4. Passwords are hashed with Argon2 per rule B7.3.</p>
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
@SuppressFBWarnings(
        value = "SPRING_CSRF_PROTECTION_DISABLED",
        justification = "Stateless JSON API with JWT bearer auth; no cookie session to attack (ADR-0014).")
public class SecurityConfig {

    /** Minimum HS256 key length in bytes (256 bits). */
    private static final int HS256_MIN_KEY_BYTES = 32;

    /** Typed JWT configuration (secret, issuer, TTLs). */
    private final JwtProperties jwtProperties;

    /**
     * Builds the single application security filter chain.
     *
     * <p>CSRF is intentionally disabled (stateless JSON API; see
     * ADR-0014). Form login and HTTP basic are disabled. Sessions are
     * never created. The OAuth2 Resource Server converts the
     * {@code roles} claim into {@code ROLE_*} authorities so
     * {@code @PreAuthorize("hasRole('ADMIN')")} works out of the box.</p>
     *
     * @param http      Spring Security DSL builder
     * @param converter authority converter contributed by {@link #jwtAuthenticationConverter()}
     * @return a stateless filter chain
     * @throws Exception if the DSL builder rejects any configuration step
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            final HttpSecurity http,
            final JwtAuthenticationConverter converter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, ApiPaths.HEALTH).permitAll()
                        .requestMatchers(HttpMethod.POST, ApiPaths.AUTH_LOGIN, ApiPaths.AUTH_REFRESH).permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    /**
     * Argon2 password encoder (rule B7.3).
     *
     * @return an Argon2 encoder using the Spring Security 5.8+ defaults
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * System UTC clock; centralised so tests can swap a fixed clock.
     *
     * @return a system UTC clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * JWT decoder bound to the same HMAC key used for signing. Enforces
     * the configured signature algorithm (HS256).
     *
     * @return a Nimbus JWT decoder
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * JWT encoder bound to the symmetric HMAC key.
     *
     * <p>Wraps the secret in an {@link OctetSequenceKey} with the HS256
     * algorithm tag so Nimbus's JWK selector can match the signing key
     * when {@link NimbusJwtEncoder} resolves a header alg of HS256.</p>
     *
     * @return a Nimbus JWT encoder
     */
    @Bean
    public JwtEncoder jwtEncoder() {
        final OctetSequenceKey jwk = new OctetSequenceKey.Builder(secretKey().getEncoded())
                .algorithm(JWSAlgorithm.HS256)
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    /**
     * Maps the JWT {@code roles} claim to {@code ROLE_*} authorities so
     * {@code @PreAuthorize("hasRole('ADMIN')")} matches a token claim of
     * {@code {"roles":["ADMIN"]}}.
     *
     * @return a configured authentication converter
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        final JwtGrantedAuthoritiesConverter ga = new JwtGrantedAuthoritiesConverter();
        ga.setAuthoritiesClaimName("roles");
        ga.setAuthorityPrefix("ROLE_");
        final JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(ga);
        return converter;
    }

    /**
     * Decodes the Base64 secret and rejects keys shorter than 32 bytes
     * (the HS256 minimum per RFC 7518 section 3.2).
     *
     * @return a {@link SecretKey} suitable for HS256 signing and validation
     * @throws IllegalStateException if the configured secret decodes to fewer than 32 bytes
     */
    private SecretKey secretKey() {
        final byte[] raw = Base64.getDecoder().decode(this.jwtProperties.secret());
        if (raw.length < HS256_MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "cortex.gateway.security.jwt.secret must decode to at least "
                            + HS256_MIN_KEY_BYTES + " bytes (HS256). Configured length: " + raw.length);
        }
        return new SecretKeySpec(raw, JWSAlgorithm.HS256.getName());
    }
}
