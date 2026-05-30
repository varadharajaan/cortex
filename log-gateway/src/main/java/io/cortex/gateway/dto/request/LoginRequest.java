package io.cortex.gateway.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login request body for {@code POST /api/v1/auth/login}.
 *
 * @param username caller-supplied login name
 * @param password caller-supplied plaintext password
 */
public record LoginRequest(
        @NotBlank @Size(max = 128) String username,
        @NotBlank @Size(max = 256) String password) {
}
