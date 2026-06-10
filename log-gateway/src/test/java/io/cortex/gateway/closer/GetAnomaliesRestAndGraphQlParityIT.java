package io.cortex.gateway.closer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import io.cortex.gateway.controller.GetAnomaliesController;
import io.cortex.gateway.dto.response.Anomaly;
import io.cortex.gateway.graphql.GetAnomaliesGraphQlController;
import io.cortex.gateway.service.GetAnomaliesService;
import java.lang.reflect.Method;
import java.util.List;
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
 * Failsafe closer (P9.3b / ADR-0049 / LD104). Proves the REST
 * ({@link ApiPaths#ANOMALIES}) and GraphQL ({@link ApiPaths#GRAPHQL}
 * {@code getAnomalies}) surfaces return payload-identical results for the
 * same caller + tenant + filters, and that both resolver methods declare
 * the same {@code @RateLimitFeature} annotation so a single Bucket4j
 * sub-bucket is shared across both surfaces per JWT subject (the parity
 * contract).
 *
 * <p>Bootstraps the full Spring context with the real Spring Security
 * filter chain, acquires a JWT, mocks the shared
 * {@link GetAnomaliesService} so the assertion focuses on transport-layer
 * parity, and additionally exercises the live tenant propagation (the
 * {@code X-Tenant-Id} header is lifted into the GraphQL context by
 * {@code TenantHeaderGraphQlInterceptor} and read by the resolver's
 * {@code @ContextValue}).</p>
 */
@SpringBootTest(properties = "cortex.gateway.get-anomalies.enabled=true")
@AutoConfigureMockMvc
class GetAnomaliesRestAndGraphQlParityIT {

    /** Tenant forwarded via X-Tenant-Id on both surfaces. */
    private static final String TENANT = "tenant-1";

    /** MockMvc with the real Spring Security filter chain. */
    @Autowired private MockMvc mockMvc;

    /** Jackson mapper for request/response (de)serialisation. */
    @Autowired private ObjectMapper objectMapper;

    /** Mocked service so the closer asserts transport parity, not downstream semantics. */
    @MockBean private GetAnomaliesService service;

    /**
     * Same filters + same authenticated caller + same tenant -> identical
     * anomaly payload on REST and GraphQL.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void restAndGraphQlReturnIdenticalPayloads() throws Exception {
        final Anomaly expected = new Anomaly(
                TENANT, "evt-1", "HIGH", "spike", "2026-06-10T10:00:00Z", "ERROR",
                "payment-svc", "boom", 0.95, "latency", "pay-latency", "2026-06-10T10:00:01Z");
        when(this.service.getAnomalies(eq(TENANT), any(), any(), eq(10)))
                .thenReturn(List.of(expected));

        final String bearer = "Bearer " + this.acquireAccessToken();

        // ---------- REST ----------
        final MvcResult restResult = this.mockMvc.perform(get(ApiPaths.ANOMALIES)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode restBody = this.objectMapper.readTree(
                restResult.getResponse().getContentAsString()).path(0);

        // ---------- GraphQL ----------
        final String gqlQuery = "query Get($limit: Int) { getAnomalies(limit: $limit) "
                + "{ tenantId eventId severity reason ts level service message "
                + "confidence anomalyType remediationKey receivedAt } }";
        final String graphQlBody = this.objectMapper.writeValueAsString(
                Map.of("query", gqlQuery, "variables", Map.of("limit", 10)));
        final MvcResult gqlResult = this.mockMvc.perform(post(ApiPaths.GRAPHQL)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphQlBody))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode gqlEnvelope = this.objectMapper.readTree(gqlResult.getResponse().getContentAsString());
        assertThat(gqlEnvelope.has("errors")).as("GraphQL response must carry no errors").isFalse();
        final JsonNode gqlBody = gqlEnvelope.path("data").path("getAnomalies").path(0);

        // ---------- Parity assertions ----------
        for (final String field : new String[] {
                "tenantId", "eventId", "severity", "reason", "ts", "level",
                "service", "message", "anomalyType", "remediationKey", "receivedAt"}) {
            assertThat(gqlBody.path(field).asText())
                    .as("field %s parity", field)
                    .isEqualTo(restBody.path(field).asText());
        }
        assertThat(gqlBody.path("confidence").asDouble())
                .as("confidence parity")
                .isEqualTo(restBody.path("confidence").asDouble());
        assertThat(gqlBody.path("eventId").asText()).isEqualTo("evt-1");
        assertThat(gqlBody.path("confidence").asDouble()).isEqualTo(0.95);
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
        final Method restMethod = GetAnomaliesController.class.getDeclaredMethod(
                "getAnomalies", String.class, String.class, String.class, Integer.class);
        final Method graphQlMethod = GetAnomaliesGraphQlController.class.getDeclaredMethod(
                "getAnomalies", String.class, String.class, Integer.class, String.class);

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
