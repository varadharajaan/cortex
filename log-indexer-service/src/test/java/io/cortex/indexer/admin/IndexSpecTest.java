package io.cortex.indexer.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link IndexSpec} value object (P7.0).
 * Verifies the canonical constructor rejects null / blank inputs +
 * accepts a well-formed triple.
 */
class IndexSpecTest {

    @Test
    void wellFormedSpecConstructs() {
        final IndexSpec spec = new IndexSpec("tenant-a", "cortex-tenant-a-v1", "v1");
        assertThat(spec.tenantId()).isEqualTo("tenant-a");
        assertThat(spec.indexId()).isEqualTo("cortex-tenant-a-v1");
        assertThat(spec.docMappingVersion()).isEqualTo("v1");
    }

    @Test
    void blankTenantIdIsRejected() {
        assertThatThrownBy(() -> new IndexSpec(" ", "i", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void nullTenantIdIsRejected() {
        assertThatThrownBy(() -> new IndexSpec(null, "i", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void blankIndexIdIsRejected() {
        assertThatThrownBy(() -> new IndexSpec("t", "", "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexId");
    }

    @Test
    void nullIndexIdIsRejected() {
        assertThatThrownBy(() -> new IndexSpec("t", null, "v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indexId");
    }

    @Test
    void blankDocMappingVersionIsRejected() {
        assertThatThrownBy(() -> new IndexSpec("t", "i", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docMappingVersion");
    }

    @Test
    void nullDocMappingVersionIsRejected() {
        assertThatThrownBy(() -> new IndexSpec("t", "i", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docMappingVersion");
    }
}
