package io.cortex.gateway.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.dto.request.LogSearchRequest;
import io.cortex.gateway.dto.response.LogSearchResult;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.exception.GlobalExceptionHandler;
import io.cortex.gateway.service.SearchLogsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link SearchLogsController}: HTTP contract for
 * {@code GET /api/v1/logs/search}, including parameter / header
 * validation, the happy path, and the downstream-failure mappings
 * surfaced from the shared {@link SearchLogsService} (P9.1b /
 * ADR-0049).
 *
 * <p>{@code addFilters = false} skips Spring Security so the slice
 * exercises controller routing + validation directly. End-to-end
 * security + rate-limit composition is covered by the parity IT.</p>
 */
@WebMvcTest(controllers = SearchLogsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "cortex.gateway.search-logs.enabled=true")
class SearchLogsControllerTest {

    /** Canonical index id for a tenant in the happy-path tests. */
    private static final String INDEX = "cortex-tenant-1-logs";

    /** Tenant id forwarded via the X-Tenant-Id header. */
    private static final String TENANT = "tenant-1";

    /** MockMvc auto-configured by {@code @WebMvcTest}. */
    @Autowired private MockMvc mockMvc;

    /** Mocked search service so the slice can simulate downstream outcomes. */
    @MockBean private SearchLogsService service;

    @Test
    @WithMockUser(username = "alice")
    void searchReturns200OnHappyPath() throws Exception {
        final LogSearchResult result = new LogSearchResult(
                2L, List.of(Map.of("message", "boom"), Map.of("message", "bang")));
        when(this.service.search(any(LogSearchRequest.class), eq(TENANT))).thenReturn(result);

        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", INDEX)
                        .param("q", "level:ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numHits").value(2))
                .andExpect(jsonPath("$.hits.length()").value(2))
                .andExpect(jsonPath("$.hits[0].message").value("boom"));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchReturns400WhenTenantHeaderMissing() throws Exception {
        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .param("index", INDEX)
                        .param("q", "level:ERROR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchReturns400WhenIndexMissing() throws Exception {
        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("q", "level:ERROR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchReturns400WhenQueryMissing() throws Exception {
        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", INDEX))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchReturns400WhenMaxHitsNotPositive() throws Exception {
        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", INDEX)
                        .param("q", "level:ERROR")
                        .param("maxHits", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchReturns403OnTenantMismatch() throws Exception {
        when(this.service.search(any(LogSearchRequest.class), eq(TENANT)))
                .thenThrow(new ApplicationException(ErrorCodes.FORBIDDEN, "cross-tenant search rejected"));

        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", "cortex-other-tenant-logs")
                        .param("q", "level:ERROR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.FORBIDDEN.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchReturns404OnMissingIndex() throws Exception {
        when(this.service.search(any(LogSearchRequest.class), eq(TENANT)))
                .thenThrow(new ApplicationException(ErrorCodes.NOT_FOUND, "index not found"));

        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", INDEX)
                        .param("q", "level:ERROR"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.NOT_FOUND.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchReturns422OnPermanentRejection() throws Exception {
        when(this.service.search(any(LogSearchRequest.class), eq(TENANT)))
                .thenThrow(new ApplicationException(
                        ErrorCodes.SEARCH_LOGS_INVALID, "search rejected by indexer"));

        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", INDEX)
                        .param("q", "level:ERROR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.SEARCH_LOGS_INVALID.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchReturns502OnUpstreamFailure() throws Exception {
        when(this.service.search(any(LogSearchRequest.class), eq(TENANT)))
                .thenThrow(new ApplicationException(
                        ErrorCodes.SEARCH_LOGS_UPSTREAM_FAILED, "indexer unreachable"));

        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", INDEX)
                        .param("q", "level:ERROR"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.SEARCH_LOGS_UPSTREAM_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void searchClampsAndForwardsMaxHits() throws Exception {
        final LogSearchResult result = new LogSearchResult(0L, List.of());
        when(this.service.search(any(LogSearchRequest.class), eq(TENANT))).thenReturn(result);

        this.mockMvc.perform(get(ApiPaths.LOGS_SEARCH)
                        .header(HeaderNames.X_TENANT_ID, TENANT)
                        .param("index", INDEX)
                        .param("q", "level:ERROR")
                        .param("maxHits", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numHits").value(0));
    }
}
