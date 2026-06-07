package io.cortex.indexer.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoopLogSearchClient} (P7.4 default-dev
 * scaffold). Verifies the SPI contract holds without any
 * Quickwit dependency.
 */
@DisplayName("NoopLogSearchClient")
class NoopLogSearchClientTest {

    private final NoopLogSearchClient client = new NoopLogSearchClient();

    @Test
    void backendIdReturnsNoop() {
        assertThat(this.client.backendId())
                .isEqualTo(SearchResult.BACKEND_NOOP);
    }

    @Test
    void searchReturnsNoopVerdictForValidRequest() {
        final SearchRequest req = new SearchRequest(
                "tenant-a", "cortex-tenanta-v1", "*", 5);
        final SearchResult result = this.client.search(req);

        assertThat(result.backend()).isEqualTo(SearchResult.BACKEND_NOOP);
        assertThat(result.outcome()).isEqualTo(SearchResult.OUTCOME_NOOP);
        assertThat(result.numHits()).isZero();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void searchReturnsNoopVerdictForNullRequest() {
        // Noop is permissive: it never throws and never contacts a backend.
        final SearchResult result = this.client.search(null);
        assertThat(result.backend()).isEqualTo(SearchResult.BACKEND_NOOP);
        assertThat(result.outcome()).isEqualTo(SearchResult.OUTCOME_NOOP);
    }
}
