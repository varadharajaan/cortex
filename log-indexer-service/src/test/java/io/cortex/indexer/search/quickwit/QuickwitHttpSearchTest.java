package io.cortex.indexer.search.quickwit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.indexer.admin.quickwit.QuickwitProperties;
import io.cortex.indexer.metrics.IndexerMetrics;
import io.cortex.indexer.search.NoopLogSearchClient;
import io.cortex.indexer.search.SearchRequest;
import io.cortex.indexer.search.SearchResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Mockito-free unit tests for {@link QuickwitHttpSearch}
 * (P7.4 / ADR-0042). Covers the guards that resolve
 * <strong>without</strong> hitting the wire so the WireMock IT
 * does not need to re-prove them.
 */
@DisplayName("QuickwitHttpSearch")
class QuickwitHttpSearchTest {

    private static final SearchRequest VALID_REQUEST = new SearchRequest(
            "tenant-A", "cortex-tenant-A-v1", "level:ERROR", 10);

    private final ObjectMapper mapper = new ObjectMapper();
    private final IndexerMetrics metrics = new IndexerMetrics(
            new SimpleMeterRegistry(), List.of(),
            List.of(new NoopLogSearchClient()));
    private final QuickwitProperties properties = new QuickwitProperties(
            QuickwitProperties.DEFAULT_BASE_URL,
            QuickwitProperties.DEFAULT_REQUEST_TIMEOUT,
            QuickwitProperties.DEFAULT_DOC_MAPPING_VERSION);
    private final RestClient restClient = RestClient.builder().build();
    private final QuickwitHttpSearch search = new QuickwitHttpSearch(
            this.properties, this.restClient, this.metrics, this.mapper);

    @Test
    void backendIdReturnsQuickwit() {
        assertThat(this.search.backendId())
                .isEqualTo(SearchResult.BACKEND_QUICKWIT);
    }

    @Test
    void searchRejectsNullRequest() {
        final SearchResult result = this.search.search(null);

        assertThat(result.backend()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
        assertThat(result.outcome())
                .isEqualTo(SearchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:null-request");
    }

    @Test
    void searchRejectsIndexIdNotMatchingTenantPrefix() {
        // tenantId is "tenant-A" but indexId does NOT start with
        // "cortex-tenant-A-" -- the guardrail must fire BEFORE any
        // RestClient call (so this test does not need WireMock).
        final SearchRequest crossTenant = new SearchRequest(
                "tenant-A", "cortex-tenant-B-v1", "*", 10);

        final SearchResult result = this.search.search(crossTenant);

        assertThat(result.backend()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
        assertThat(result.outcome())
                .isEqualTo(SearchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:tenant-mismatch");
    }

    @Test
    void searchRejectsIndexIdMissingPrefixEntirely() {
        final SearchRequest noPrefix = new SearchRequest(
                "tenant-A", "some-other-index", "*", 10);

        final SearchResult result = this.search.search(noPrefix);

        assertThat(result.outcome())
                .isEqualTo(SearchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:tenant-mismatch");
    }

    @Test
    void renderRequestBodyContainsCanonicalKeys() {
        final Map<String, Object> body =
                this.search.renderRequestBody(VALID_REQUEST);

        assertThat(body)
                .containsEntry(QuickwitHttpSearch.BODY_KEY_QUERY,
                        VALID_REQUEST.query())
                .containsEntry(QuickwitHttpSearch.BODY_KEY_MAX_HITS,
                        VALID_REQUEST.maxHits());
    }
}
