package io.cortex.gateway.closer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.controller.GetLogByIdController;
import io.cortex.gateway.dto.response.LogEntry;
import io.cortex.gateway.graphql.GetLogByIdGraphQlController;
import io.cortex.gateway.service.GetLogByIdService;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Failsafe closer (P9.2b / ADR-0049 / LD104). Proves the REST
 * ({@link ApiPaths#LOGS_BY_ID}) and GraphQL
 * ({@link ApiPaths#GRAPHQL} {@code getLogById}) surfaces return
 * payload-identical results for the same id + caller, and that both
 * resolver methods declare the same {@code @RateLimitFeature} annotation
 * so a single Bucket4j sub-bucket is shared across both surfaces per JWT
 * subject (the parity contract).
 *
 * <p>Bootstraps the full Spring context with the real Spring Security
 * filter chain, acquires a JWT, mocks the shared {@link GetLogByIdService}
 * so the assertion focuses on transport-layer parity, and additionally
 * exercises the live tenant propagation (the {@code X-Tenant-Id} header is
 * lifted into the GraphQL context by {@code TenantHeaderGraphQlInterceptor}
 * and read by the resolver's {@code @ContextValue}).</p>
 */
@SpringBootTest(properties = "cortex.gateway.get-log-by-id.enabled=true")
@AutoConfigureMockMvc
class GetLogByIdRestAndGraphQlParityIT {

    /** Tenant forwarded via X-Tenant-Id on both surfaces. */
    private static final String TENANT = "tenant-1";

    /** Canonical event id for the parity lookup. */
    private static final String EVENT_ID = "evt-abc-123";

    /** MockMvc with the real Spring Security filter chain. */
    @Autowired private MockMvc mockMvc;

    /** Jackson mapper for request/response (de)serialisation. */
    @Autowired private ObjectMapper objectMapper;

    /** Mocked service so the closer asserts transport parity, not downstream semantics. */
    @MockBean private GetLogByIdService service;

    /**
     * Same id + same authenticated caller + same tenant -&gt; identical
     * entry payload on REST and GraphQL.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void restAndGraphQlReturnIdenticalPayloadsForSameId() throws Exception {
        final LogEntry expected = new LogEntry(
                EVENT_ID, TENANT, "2026-06-09T10:00:00Z", "ERROR", "payment-svc",
                "boom", Map.of("env", "prod"), "2026-06-09T10:00:01Z");
        when(this.service.getLogById(eq(EVENT_ID), eq(TENANT))).thenReturn(expected);

        final String bearer = "Bearer " + this.acquireAccessToken();

        // ---------- REST ----------
        final MvcResult restResult = this.mockMvc.perform(get(ApiPaths.LOGS_BY_ID, EVENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(HeaderNames.X_TENANT_ID, TENANT))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode restBody = this.objectMapper.readTree(restResult.getResponse().getContentAsString());

        // ---------- GraphQL ----------
        final String gqlQuery = "query Get($id: ID!) { getLogById(id: $id) "
                + "{ eventId tenantId ts level service message labels receivedAt } }";
        final String graphQlBody = this.objectMapper.writeValueAsString(
                Map.of("query", gqlQuery, "variables", Map.of("id", EVENT_ID)));
        final MvcResult gqlResult = this.mockMvc.perform(post(ApiPaths.GRAPHQL)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphQlBody))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode gqlEnvelope = this.objectMapper.readTree(gqlResult.getResponse().getContentAsString());
        assertThat(gqlEnvelope.has("errors")).as("GraphQL response must carry no errors").isFalse();
        final JsonNode gqlBody = gqlEnvelope.path("data").path("getLogById");

        // ---------- Parity assertions ----------
        for (final String field : new String[] {
                "eventId", "tenantId", "ts", "level", "service", "message", "receivedAt"}) {
            assertThat(gqlBody.path(field).asText())
                    .as("field %s parity", field)
                    .isEqualTo(restBody.path(field).asText());
        }
        assertThat(gqlBody.path("labels")).isEqualTo(restBody.path("labels"));
        assertThat(gqlBody.path("eventId").asText()).isEqualTo(EVENT_ID);
        assertThat(gqlBody.path("labels").path("env").asText()).isEqualTo("prod");
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

    /**
     * Parity contract closer: proves the GraphQL resolver declares an
     * {@link RateLimitFeature @RateLimitFeature} annotation byte-equal to
     * the REST controller's so both surfaces compute the same Bucket4j
     * key for the same JWT subject and share a single sub-bucket.
     *
     * @throws NoSuchMethodException if either resolver method is renamed
     */
    @Test
    void graphQlAndRestResolversDeclareIdenticalRateLimitFeatureAnnotation()
            throws NoSuchMethodException {
        final Method restMethod = GetLogByIdController.class.getDeclaredMethod(
                "getLogById", String.class, String.class);
        final Method graphQlMethod = GetLogByIdGraphQlController.class.getDeclaredMethod(
                "getLogById", String.class, String.class);

        final RateLimitFeature restAnnotation = AnnotatedElementUtils.findMergedAnnotation(
                restMethod, RateLimitFeature.class);
        final RateLimitFeature graphQlAnnotation = AnnotatedElementUtils.findMergedAnnotation(
                graphQlMethod, RateLimitFeature.class);

        assertThat(restAnnotation).as("REST controller must carry @RateLimitFeature").isNotNull();
        assertThat(graphQlAnnotation).as("GraphQL resolver must carry @RateLimitFeature").isNotNull();

        assertThat(graphQlAnnotation.name()).isEqualTo(restAnnotation.name());
        assertThat(graphQlAnnotation.capacity()).isEqualTo(restAnnotation.capacity());
        assertThat(graphQlAnnotation.refill()).isEqualTo(restAnnotation.refill());
        assertThat(graphQlAnnotation.errorCode()).isEqualTo(restAnnotation.errorCode());
        assertThat(graphQlAnnotation.keyPrefix()).isEqualTo(restAnnotation.keyPrefix());
    }
}
