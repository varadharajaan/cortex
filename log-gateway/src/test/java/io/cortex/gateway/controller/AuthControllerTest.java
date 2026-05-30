package io.cortex.gateway.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.request.LoginRequest;
import io.cortex.gateway.dto.request.RefreshRequest;
import io.cortex.gateway.dto.response.TokenResponse;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.exception.GlobalExceptionHandler;
import io.cortex.gateway.service.AuthService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link AuthController}: HTTP contract for login + refresh,
 * including validation rejection and the 401 mapping for bad credentials.
 *
 * <p>{@code addFilters = false} skips the Spring Security filter chain so
 * the slice can exercise controller routing + validation without bringing
 * up the JWT decoder. End-to-end security is covered by
 * {@link io.cortex.gateway.AuthIntegrationTest}.</p>
 */
@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    /** MockMvc auto-configured by {@code @WebMvcTest}. */
    @Autowired private MockMvc mockMvc;

    /** Jackson mapper for request-body serialisation. */
    @Autowired private ObjectMapper objectMapper;

    /** Mocked auth service so the slice can simulate login outcomes. */
    @MockBean private AuthService authService;

    /**
     * A valid credential set returns a Bearer {@link TokenResponse}.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void loginReturnsTokenResponseOnValidCredentials() throws Exception {
        when(this.authService.login(eq("admin"), eq("dev-admin-pass")))
                .thenReturn(new TokenResponse("acc", "ref", "Bearer", OffsetDateTime.now()));

        this.mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(new LoginRequest("admin", "dev-admin-pass"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("acc"))
                .andExpect(jsonPath("$.refreshToken").value("ref"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessExpiresAt").isNotEmpty());
    }

    /**
     * Invalid credentials surface as a {@code 401} problem detail.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void loginReturns401OnBadCredentials() throws Exception {
        when(this.authService.login(eq("admin"), eq("wrong")))
                .thenThrow(new ApplicationException(ErrorCodes.UNAUTHENTICATED, "invalid credentials"));

        this.mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(new LoginRequest("admin", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.UNAUTHENTICATED.name()))
                .andExpect(jsonPath("$.detail").value("invalid credentials"));
    }

    /**
     * A blank password fails {@code @Valid} and surfaces as 400 VALIDATION_FAILED.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void loginReturns400OnBlankPassword() throws Exception {
        this.mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    /**
     * Refresh delegates to {@link AuthService#refresh} and returns the new pair.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void refreshReturnsRotatedTokens() throws Exception {
        when(this.authService.refresh(eq("ref-old")))
                .thenReturn(new TokenResponse("acc2", "ref2", "Bearer", OffsetDateTime.now()));

        this.mockMvc.perform(post(ApiPaths.AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(new RefreshRequest("ref-old"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("acc2"))
                .andExpect(jsonPath("$.refreshToken").value("ref2"));
    }
}
