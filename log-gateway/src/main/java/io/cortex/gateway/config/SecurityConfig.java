package io.cortex.gateway.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cortex.gateway.constants.ApiPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless HTTP security configuration for the gateway.
 *
 * <p>P3.0 baseline: enables method-level security so every controller
 * MUST declare {@link org.springframework.security.access.prepost.PreAuthorize}
 * (rule 18.4). Disables CSRF (stateless API), disables form login and
 * HTTP basic, and permits the public health and actuator endpoints.
 * P3.1 will layer JWT and API-key authentication filters on top.</p>
 */
@Configuration
@EnableMethodSecurity
@SuppressFBWarnings(
        value = "SPRING_CSRF_PROTECTION_DISABLED",
        justification = "Stateless JSON API with JWT bearer auth; no cookie session to attack (ADR-0014).")
public class SecurityConfig {

    /**
     * Builds the single application security filter chain.
     *
     * <p>CSRF is intentionally disabled: this gateway exposes a stateless
     * JSON API consumed by non-browser clients (CLI, agents, other
     * services) using JWT bearer tokens; there is no cookie-based session
     * for an attacker to ride. See ADR-0014.</p>
     *
     * @param http Spring Security DSL builder injected by the framework
     * @return a stateless filter chain
     * @throws Exception if the DSL builder rejects any configuration step
     */
    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, ApiPaths.HEALTH).permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
