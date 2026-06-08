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
import io.cortex.gateway.controller.SearchLogsController;
import io.cortex.gateway.dto.request.LogSearchRequest;
import io.cortex.gateway.dto.response.LogSearchResult;
import io.cortex.gateway.graphql.SearchLogsGraphQlController;
import io.cortex.gateway.service.SearchLogsService;
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
 * Failsafe closer (P9.1b / ADR-0049 / LD104). Proves the REST
 * ({@link ApiPaths#LOGS_SEARCH}) and GraphQL
 * ({@link ApiPaths#GRAPHQL} {@code searchLogs}) surfaces return
 * payload-identical results for the same query and same caller, and
 * that both resolver methods declare the same {@code @RateLimitFeature}
 * annotation so a single Bucket4j sub-bucket is shared across both
 * surfaces per JWT subject (the parity contract).
 *
 * <p>Bootstraps the full Spring context with the real Spring Security
 * filter chain active, acquires a JWT via {@link ApiPaths#AUTH_LOGIN},
 * mocks the shared {@link SearchLogsService} so the assertion focuses on
 * transport-layer parity rather than downstream semantics, and
 * dispatches both surfaces over MockMvc. The GraphQL path additionally
 * exercises the live tenant propagation: the {@code X-Tenant-Id} header
 * is lifted into the GraphQL execution context by
 * {@code TenantHeaderGraphQlInterceptor} and read by the resolver's
 * {@code @ContextValue}, mirroring the REST controller reading the same
 * header.</p>
 */
@SpringBootTest(properties = "cortex.gateway.search-logs.enabled=true")
@AutoConfigureMockMvc
class SearchLogsRestAndGraphQlParityIT {

    /** Tenant forwarded via X-Tenant-Id on both surfaces. */
    private static final String TENANT = "tenant-1";

    /** Canonical index id for the parity query. */
    private static final String INDEX = "cortex-tenant-1-logs";

    /** MockMvc with the real Spring Security filter chain. */
    @Autowired private MockMvc mockMvc;

    /** Jackson mapper for request/response (de)serialisation. */
    @Autowired private ObjectMapper objectMapper;

    /** Mocked search service so the closer asserts transport parity, not downstream semantics. */
    @MockBean private SearchLogsService service;

    /**
     * Same query + same authenticated caller + same tenant -&gt;
     * identical {@code (numHits, hits)} on REST and GraphQL.
     *
     * @throws Exception if MockMvc performs an unexpected I/O failure
     */
    @Test
    void restAndGraphQlReturnIdenticalPayloadsForSameQuery() throws Exception {
        final LogSearchResult expected = new LogSearchResult(
                2L, List.of(Map.of("message", "boom"), Map.of("message", "bang")));
        when(this.service.search(any(LogSearchRequest.class), eq(TENANT))).thenReturn(expected);

        final String bearer = "Bearer " + this.acquireAccessToken();

        // ---------- REST ----------
        final MvcResult restResult = this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", INDEX)
                        .param("q", "level:ERROR")
                        .param("maxHits", "10"))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode restBody = this.objectMapper.readTree(restResult.getResponse().getContentAsString());

        // ---------- GraphQL ----------
        final String graphQlBody = """
                {"query":"query Search($in: LogSearchInput!) { searchLogs(input: $in) { numHits hits } }",
                 "variables":{"in":{"indexId":"%s","query":"level:ERROR","maxHits":10}}}
                """.formatted(INDEX);
        final MvcResult gqlResult = this.mockMvc.perform(post(ApiPaths.GRAPHQL)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(graphQlBody))
                .andExpect(status().isOk())
                .andReturn();
        final JsonNode gqlEnvelope = this.objectMapper.readTree(gqlResult.getResponse().getContentAsString());
        assertThat(gqlEnvelope.has("errors")).as("GraphQL response must carry no errors").isFalse();
        final JsonNode gqlBody = gqlEnvelope.path("data").path("searchLogs");

        // ---------- Parity assertions ----------
        assertThat(gqlBody.path("numHits").asLong()).isEqualTo(restBody.path("numHits").asLong());
        assertThat(gqlBody.path("hits")).isEqualTo(restBody.path("hits"));
        assertThat(gqlBody.path("numHits").asLong()).isEqualTo(2L);
        assertThat(gqlBody.path("hits")).hasSize(2);
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
     * {@link RateLimitFeature @RateLimitFeature} annotation that is
     * byte-equal to the REST controller's annotation so both surfaces
     * compute the same Bucket4j key for the same JWT subject and share a
     * single sub-bucket (the P9.1b parity contract). Mirrors the P9.0a
     * annotation-equality check from
     * {@code NlQueryRestAndGraphQlParityIT}.
     *
     * @throws NoSuchMethodException if either resolver method is renamed
     */
    @Test
    void graphQlAndRestResolversDeclareIdenticalRateLimitFeatureAnnotation()
            throws NoSuchMethodException {
        final Method restMethod = SearchLogsController.class.getDeclaredMethod(
                "searchLogs", String.class, String.class, String.class, Integer.class);
        final Method graphQlMethod = SearchLogsGraphQlController.class.getDeclaredMethod(
                "searchLogs", LogSearchRequest.class, String.class);

        final RateLimitFeature restAnnotation = AnnotatedElementUtils.findMergedAnnotation(
                restMethod, RateLimitFeature.class);
        final RateLimitFeature graphQlAnnotation = AnnotatedElementUtils.findMergedAnnotation(
                graphQlMethod, RateLimitFeature.class);

        assertThat(restAnnotation)
                .as("REST controller must carry @RateLimitFeature")
                .isNotNull();
        assertThat(graphQlAnnotation)
                .as("GraphQL resolver must carry @RateLimitFeature (parity contract)")
                .isNotNull();

        assertThat(graphQlAnnotation.name()).isEqualTo(restAnnotation.name());
        assertThat(graphQlAnnotation.capacity()).isEqualTo(restAnnotation.capacity());
        assertThat(graphQlAnnotation.refill()).isEqualTo(restAnnotation.refill());
        assertThat(graphQlAnnotation.errorCode()).isEqualTo(restAnnotation.errorCode());
        assertThat(graphQlAnnotation.keyPrefix()).isEqualTo(restAnnotation.keyPrefix());
    }
}
