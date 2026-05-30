package io.cortex.gateway.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Refresh request body for {@code POST /api/v1/auth/refresh}.
 *
 * @param refreshToken refresh JWT previously issued by the gateway
 */
public record RefreshRequest(
        @NotBlank @Size(max = 4096) String refreshToken) {
}
