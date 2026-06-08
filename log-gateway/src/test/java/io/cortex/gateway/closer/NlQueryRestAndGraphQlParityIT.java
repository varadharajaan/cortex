package io.cortex.gateway.closer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.dto.request.NlQueryRequest;
import io.cortex.gateway.dto.response.NlQueryResponse;
import io.cortex.gateway.service.NlQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Failsafe closer (P9.0 / ADR-0049 / LD104). Proves the REST
 * ({@link ApiPaths#QUERY_NL}) and GraphQL
 * ({@link ApiPaths#GRAPHQL} {@code nlToLogQL}) surfaces return
 * payload-identical results for the same prompt and same caller.
 *
 * <p>Bootstraps the full Spring context with the real Spring Security
 * filter chain active, acquires a JWT via {@link ApiPaths#AUTH_LOGIN},
 * mocks the shared {@link NlQueryService} so the assertion focuses on
 * transport-layer parity rather than model semantics, and dispatches
 * both surfaces over MockMvc.</p>
 *
 * <p>The four NL error mappings ({@code NL_QUERY_INVALID},
 * {@code NL_QUERY_REFUSED}, {@code NL_QUERY_UPSTREAM_FAILED},
 * {@code NL_QUERY_RATE_LIMITED}) live in the surface-specific slice
 * tests; this closer focuses on the happy-path payload equality that
 * gives the parity claim its teeth.</p>
 */
@SpringBootTest(properties = "cortex.gateway.nl-query.enabled=true")
@AutoConfigureMockMvc
class NlQueryRestAndGraphQlParityIT {

    /** MockMvc with the real Spring Security filter chain. */
    @Autowired private MockMvc mockMvc;

    /** Jackson mapper for request/response (de)serialisation. */
    @Autowired private ObjectMapper objectMapper;

    /** Mocked NL service so the closer asserts transport parity, not model semantics. */
    @MockBean private NlQueryService service;

    /**
     * Same prompt + same authenticated caller -&gt; identical
     * {@code (logql, confidence, explanation)} on REST and GraphQL.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void restAndGraphQlReturnIdenticalPayloadsForSamePrompt() throws Exception {
        final String prompt = "errors in payments last 1h";
        final NlQueryResponse expected = new NlQueryResponse(
                "{service=\"payments\"} |= \"error\"", 0.87, "filter on service label");
        when(this.service.translate(any(NlQueryRequest.class), eq("admin"))).thenReturn(expected);

        final String bearer = "Bearer " + this.acquireAccessToken();

        // ---------- REST ----------
        final MvcResult restResult = this.mockMvc.perform(post(ApiPaths.QUERY_NL)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(this.objectMapper.writeValueAsString(new NlQueryRequest(prompt))))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode restBody = this.objectMapper.readTree(restResult.getResponse().getContentAsString());

        // ---------- GraphQL ----------
        final String graphQlBody = """
                {"query":"query Translate($p: String!) { nlToLogQL(prompt: $p) { logql confidence explanation } }",
                 "variables":{"p":"%s"}}
                """.formatted(prompt);
        final MvcResult gqlResult = this.mockMvc.perform(post(ApiPaths.GRAPHQL)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphQlBody))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode gqlEnvelope = this.objectMapper.readTree(gqlResult.getResponse().getContentAsString());
        assertThat(gqlEnvelope.has("errors")).as("GraphQL response must carry no errors").isFalse();
        final JsonNode gqlBody = gqlEnvelope.path("data").path("nlToLogQL");

        // ---------- Parity assertions ----------
        assertThat(gqlBody.path("logql").asText()).isEqualTo(restBody.path("logql").asText());
        assertThat(gqlBody.path("confidence").asDouble()).isEqualTo(restBody.path("confidence").asDouble());
        assertThat(gqlBody.path("explanation").asText()).isEqualTo(restBody.path("explanation").asText());
    }

    /**
     * Acquires a bearer access token via the auth endpoint with the
     * canonical test admin credentials wired into the test profile.
     *
     * @return the raw access token string (no {@code Bearer } prefix)
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    private String acquireAccessToken() throws Exception {
        final MvcResult result = this.mockMvc.perform(post(ApiPaths.AUTH_LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return this.objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
