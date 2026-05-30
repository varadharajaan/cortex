package io.cortex.gateway.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bootstrap users defined in configuration (P3.1 placeholder).
 *
 * <p>Loaded from {@code cortex.gateway.security.bootstrap.users[*]}.
 * Each entry's plaintext password is Argon2-hashed at startup (rule
 * B7.3) and held in memory. P4 replaces this whole class with a
 * PostgreSQL-backed user store.</p>
 *
 * @param users user entries; must contain at least one entry
 */
@Validated
@ConfigurationProperties(prefix = "cortex.gateway.security.bootstrap")
public record BootstrapUsersProperties(
        @Valid @NotEmpty List<BootstrapUser> users) {

    /**
     * A single bootstrap user.
     *
     * @param username unique login name
     * @param password plaintext password (Argon2-hashed at startup)
     * @param roles    role names (without the {@code ROLE_} prefix)
     */
    public record BootstrapUser(
            @NotNull String username,
            @NotNull String password,
            @NotEmpty List<String> roles) {
    }
}
