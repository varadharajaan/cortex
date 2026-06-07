package io.cortex.indexer.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SearchResult} factories + compact-ctor
 * defensive copy semantics (P7.4 / ADR-0042 D1).
 */
@DisplayName("SearchResult")
class SearchResultTest {

    @Test
    void noopFactoryProducesNoopBackendNoopOutcome() {
        final SearchResult r = SearchResult.noop("scaffold");
        assertThat(r.backend()).isEqualTo(SearchResult.BACKEND_NOOP);
        assertThat(r.outcome()).isEqualTo(SearchResult.OUTCOME_NOOP);
        assertThat(r.reason()).isEqualTo("scaffold");
        assertThat(r.numHits()).isZero();
        assertThat(r.hits()).isEmpty();
    }

    @Test
    void noopFactoryCoercesNullReasonToEmptyString() {
        final SearchResult r = SearchResult.noop(null);
        assertThat(r.reason()).isEmpty();
    }

    @Test
    void searchOkFactoryRetainsBackendAndHits() {
        final List<Map<String, Object>> hits = List.of(
                Map.of("message", "hello"),
                Map.of("message", "world"));
        final SearchResult r = SearchResult.searchOk(
                SearchResult.BACKEND_QUICKWIT, 42L, hits);

        assertThat(r.backend()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
        assertThat(r.outcome()).isEqualTo(SearchResult.OUTCOME_SEARCH_OK);
        assertThat(r.numHits()).isEqualTo(42L);
        assertThat(r.hits()).hasSize(2);
    }

    @Test
    void searchOkFactoryClampsNegativeNumHitsToZero() {
        final SearchResult r = SearchResult.searchOk(
                SearchResult.BACKEND_QUICKWIT, -1L, List.of());
        assertThat(r.numHits()).isZero();
    }

    @Test
    void searchOkFactoryCoercesNullBackendToNoop() {
        final SearchResult r = SearchResult.searchOk(
                null, 0L, List.of());
        assertThat(r.backend()).isEqualTo(SearchResult.BACKEND_NOOP);
    }

    @Test
    void transientFailureFactoryRetainsReason() {
        final SearchResult r = SearchResult.transientFailure(
                SearchResult.BACKEND_QUICKWIT, "quickwit:5xx:500");
        assertThat(r.backend()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
        assertThat(r.outcome())
                .isEqualTo(SearchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(r.reason()).isEqualTo("quickwit:5xx:500");
        assertThat(r.hits()).isEmpty();
    }

    @Test
    void permanentFailureFactoryRetainsReason() {
        final SearchResult r = SearchResult.permanentFailure(
                SearchResult.BACKEND_QUICKWIT, "quickwit:tenant-mismatch");
        assertThat(r.backend()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
        assertThat(r.outcome())
                .isEqualTo(SearchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(r.reason()).isEqualTo("quickwit:tenant-mismatch");
        assertThat(r.hits()).isEmpty();
    }

    @Test
    void compactCtorDefensivelyCopiesHits() {
        final List<Map<String, Object>> mutable = new ArrayList<>();
        mutable.add(Map.of("k", "v1"));
        final SearchResult r = new SearchResult(
                SearchResult.BACKEND_QUICKWIT,
                SearchResult.OUTCOME_SEARCH_OK,
                "", 1L, mutable);

        // Mutating the caller list MUST NOT leak into the record.
        mutable.add(Map.of("k", "v2"));
        assertThat(r.hits()).hasSize(1);

        // The published list itself is immutable.
        assertThatThrownBy(() -> r.hits().add(Map.of("k", "v3")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void compactCtorReplacesNullHitsWithEmptyList() {
        final SearchResult r = new SearchResult(
                SearchResult.BACKEND_NOOP, SearchResult.OUTCOME_NOOP,
                "", 0L, null);
        assertThat(r.hits()).isEmpty();
    }
}
