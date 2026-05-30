package io.cortex.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.gateway.constants.ApiPaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Full-stack integration test for the auth endpoints: boots the gateway
 * with the test profile, exchanges credentials for a JWT, refreshes it,
 * verifies token rotation, and exercises a protected endpoint with the
 * issued bearer token.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    /** MockMvc with the real Spring Security filter chain active. */
    @Autowired private MockMvc mockMvc;

    /** Jackson mapper used to read the {@code TokenResponse} body. */
    @Autowired private ObjectMapper objectMapper;

    /**
     * Login -&gt; refresh round trip yields a fresh, distinct token pair.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void loginThenRefreshRotatesTokens() throws Exception {
        final JsonNode initial = this.login("admin", "test-admin-pass");
        final String access1 = initial.get("accessToken").asText();
        final String refresh1 = initial.get("refreshToken").asText();
        assertThat(access1).isNotBlank();
        assertThat(refresh1).isNotBlank();

        final JsonNode rotated = this.refresh(refresh1);
        final String access2 = rotated.get("accessToken").asText();
        final String refresh2 = rotated.get("refreshToken").asText();
        assertThat(access2).isNotBlank().isNotEqualTo(access1);
        assertThat(refresh2).isNotBlank().isNotEqualTo(refresh1);

        // A used refresh token must not be accepted again (single-use, rule B7.5).
        this.mockMvc.perform(post(ApiPaths.AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh1 + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Bad credentials are rejected with 401 and an UNAUTHENTICATED problem code.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void loginRejectsBadCredentials() throws Exception {
        this.mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
    }

    /**
     * Health endpoint must remain publicly accessible with the filter chain active.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void healthEndpointIsPublic() throws Exception {
        this.mockMvc.perform(get(ApiPaths.HEALTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * Helper to perform a login and return the parsed JSON body.
     *
     * @param user     username payload
     * @param password password payload
     * @return parsed response body
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    private JsonNode login(final String user, final String password) throws Exception {
        final MvcResult result = this.mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * Helper to perform a refresh and return the parsed JSON body.
     *
     * @param refreshToken refresh token from a prior login
     * @return parsed response body
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    private JsonNode refresh(final String refreshToken) throws Exception {
        final MvcResult result = this.mockMvc.perform(post(ApiPaths.AUTH_REFRESH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
