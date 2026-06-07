package io.cortex.indexer.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SearchRequest} compact-ctor validation
 * (P7.4 / ADR-0042 D2). Covers the null / blank / non-positive
 * matrix on every field plus the happy-path round trip.
 */
@DisplayName("SearchRequest")
class SearchRequestTest {

    @Test
    void happyPathRetainsAllFourFields() {
        final SearchRequest req = new SearchRequest(
                "tenant-a", "cortex-tenanta-v1", "level:ERROR", 10);

        assertThat(req.tenantId()).isEqualTo("tenant-a");
        assertThat(req.indexId()).isEqualTo("cortex-tenanta-v1");
        assertThat(req.query()).isEqualTo("level:ERROR");
        assertThat(req.maxHits()).isEqualTo(10);
    }

    @Test
    void rejectsNullTenantId() {
        assertThatThrownBy(() -> new SearchRequest(
                null, "cortex-tenanta-v1", "q", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsBlankTenantId() {
        assertThatThrownBy(() -> new SearchRequest(
                "   ", "cortex-tenanta-v1", "q", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsNullIndexId() {
        assertThatThrownBy(() -> new SearchRequest(
                "tenant-a", null, "q", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexId");
    }

    @Test
    void rejectsBlankIndexId() {
        assertThatThrownBy(() -> new SearchRequest(
                "tenant-a", "", "q", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexId");
    }

    @Test
    void rejectsNullQuery() {
        assertThatThrownBy(() -> new SearchRequest(
                "tenant-a", "cortex-tenanta-v1", null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> new SearchRequest(
                "tenant-a", "cortex-tenanta-v1", " \t ", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    @Test
    void rejectsZeroMaxHits() {
        assertThatThrownBy(() -> new SearchRequest(
                "tenant-a", "cortex-tenanta-v1", "q", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHits");
    }

    @Test
    void rejectsNegativeMaxHits() {
        assertThatThrownBy(() -> new SearchRequest(
                "tenant-a", "cortex-tenanta-v1", "q", -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHits");
    }
}
