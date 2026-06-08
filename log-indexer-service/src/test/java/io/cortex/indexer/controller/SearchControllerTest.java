package io.cortex.indexer.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.indexer.search.LogSearchClient;
import io.cortex.indexer.search.SearchRequest;
import io.cortex.indexer.search.SearchResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Surefire slice for {@link SearchController} + {@link SearchControllerAdvice}
 * (P9.1a / ADR-0042 Amendment 1).
 *
 * <p>Boots only the web layer via {@code @WebMvcTest} with a mocked
 * {@link LogSearchClient}, so it exercises the controller's
 * outcome-&gt;HTTP mapping table and the advice's client-input
 * 400 mapping without standing up Quickwit or the full context.
 * The end-to-end path through the real Quickwit HTTP adapter is
 * covered by {@code SearchControllerWireMockIT}.</p>
 */
@WebMvcTest(controllers = SearchController.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("SearchController (web slice)")
class SearchControllerTest {

    private static final String URL = "/api/v1/search";
    private static final String TENANT = "tenant-a";
    private static final String INDEX = "cortex-tenant-a-v1";
    private static final String BODY =
            "{\"indexId\":\"cortex-tenant-a-v1\",\"query\":\"level:ERROR\",\"maxHits\":10}";

    private final MockMvc mockMvc;

    @MockBean
    private LogSearchClient searchClient;

    SearchControllerTest(final MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void searchOkReturns200WithHits() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class))).thenReturn(
                SearchResult.searchOk(SearchResult.BACKEND_QUICKWIT, 2L,
                        List.of(Map.of("msg", "boom"), Map.of("msg", "bang"))));

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numHits").value(2))
                .andExpect(jsonPath("$.hits.length()").value(2))
                .andExpect(jsonPath("$.hits[0].msg").value("boom"));
    }

    @Test
    void noopReturns200Empty() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class)))
                .thenReturn(SearchResult.noop("search backend disabled"));

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numHits").value(0))
                .andExpect(jsonPath("$.hits.length()").value(0));
    }

    @Test
    void tenantMismatchReturns403() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class)))
                .thenReturn(SearchResult.permanentFailure(
                        SearchResult.BACKEND_QUICKWIT,
                        SearchController.REASON_TENANT_MISMATCH));

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEARCH_PERMANENT_FAILURE"));
    }

    @Test
    void notFoundReasonReturns404() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class)))
                .thenReturn(SearchResult.permanentFailure(
                        SearchResult.BACKEND_QUICKWIT, "quickwit:4xx:404"));

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherPermanentFailureReturns422() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class)))
                .thenReturn(SearchResult.permanentFailure(
                        SearchResult.BACKEND_QUICKWIT, "quickwit:4xx:400"));

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rateLimitedReturns429WithRetryAfter() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class)))
                .thenReturn(SearchResult.transientFailure(
                        SearchResult.BACKEND_QUICKWIT,
                        SearchController.REASON_RATE_LIMITED));

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void otherTransientFailureReturns503() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class)))
                .thenReturn(SearchResult.transientFailure(
                        SearchResult.BACKEND_QUICKWIT, "quickwit:5xx:500"));

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void missingTenantHeaderReturns400() throws Exception {
        this.mockMvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SEARCH_BAD_REQUEST"));
    }

    @Test
    void blankIndexIdReturns400() throws Exception {
        final String body = "{\"indexId\":\"\",\"query\":\"x\",\"maxHits\":5}";

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SEARCH_BAD_REQUEST"));
    }

    @Test
    void nonPositiveMaxHitsReturns400() throws Exception {
        final String body = "{\"indexId\":\"" + INDEX + "\",\"query\":\"x\",\"maxHits\":0}";

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankTenantHeaderReturns400() throws Exception {
        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, " ")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedBodyReturns400() throws Exception {
        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void absentMaxHitsAppliesServerDefault() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class)))
                .thenReturn(SearchResult.searchOk(SearchResult.BACKEND_QUICKWIT, 0L, List.of()));
        final String body = "{\"indexId\":\"" + INDEX + "\",\"query\":\"x\"}";

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        final ArgumentCaptor<SearchRequest> captor =
                ArgumentCaptor.forClass(SearchRequest.class);
        verify(this.searchClient).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().maxHits())
                .isEqualTo(SearchController.DEFAULT_MAX_HITS);
    }

    @Test
    void overlargeMaxHitsIsClampedToCeiling() throws Exception {
        when(this.searchClient.search(any(SearchRequest.class)))
                .thenReturn(SearchResult.searchOk(SearchResult.BACKEND_QUICKWIT, 0L, List.of()));
        final String body =
                "{\"indexId\":\"" + INDEX + "\",\"query\":\"x\",\"maxHits\":999999}";

        this.mockMvc.perform(post(URL)
                        .header(SearchController.TENANT_HEADER, TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        final ArgumentCaptor<SearchRequest> captor =
                ArgumentCaptor.forClass(SearchRequest.class);
        verify(this.searchClient).search(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().maxHits())
                .isEqualTo(SearchController.MAX_HITS_CEILING);
    }
}
