package io.cortex.gateway.controller;

import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.dto.request.LoginRequest;
import io.cortex.gateway.dto.request.RefreshRequest;
import io.cortex.gateway.dto.response.TokenResponse;
import io.cortex.gateway.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login / refresh endpoints for JWT-based authentication (rule B7.1).
 *
 * <p>Both endpoints are explicitly public ({@link PreAuthorize}
 * {@code permitAll()}) so {@link io.cortex.gateway.config.SecurityConfig}
 * still requires every other endpoint to be authenticated (rule 18.4).</p>
 */
@RestController
@RequestMapping(ApiPaths.AUTH_BASE)
@RequiredArgsConstructor
public class AuthController {

    /** Delegated authentication + token-rotation logic. */
    private final AuthService authService;

    /**
     * Authenticates the supplied credentials and returns a fresh token pair.
     *
     * @param request validated login body
     * @return HTTP 200 with the token response
     */
    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody final LoginRequest request) {
        return ResponseEntity.ok(this.authService.login(request.username(), request.password()));
    }

    /**
     * Consumes a refresh token and returns a freshly rotated pair.
     *
     * @param request validated refresh body
     * @return HTTP 200 with the new token response
     */
    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody final RefreshRequest request) {
        return ResponseEntity.ok(this.authService.refresh(request.refreshToken()));
    }
}
