package io.cortex.indexer.admin;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the default {@link NoopQuickwitIndexAdmin} admin
 * (P7.0 / ADR-0038 D1; P7.2 / ADR-0040 applyRetention addition).
 * Verifies the noop returns the expected
 * {@code backend=noop, outcome=noop} verdict on every SPI method.
 */
class NoopQuickwitIndexAdminTest {

    private final NoopQuickwitIndexAdmin admin = new NoopQuickwitIndexAdmin();

    @Test
    void backendIdIsNoop() {
        assertThat(this.admin.backendId())
                .isEqualTo(IndexAdminResult.BACKEND_NOOP);
    }

    @Test
    void ensureIndexReturnsNoopVerdict() {
        final IndexSpec spec = new IndexSpec("tenant-a", "cortex-tenant-a-v1", "v1");

        final IndexAdminResult result = this.admin.ensureIndex(spec);

        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_NOOP);
        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_NOOP);
        assertThat(result.reason()).contains("noop admin");
    }

    @Test
    void dropIndexReturnsNoopVerdict() {
        final IndexAdminResult result =
                this.admin.dropIndex("cortex-tenant-a-v1");

        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_NOOP);
        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_NOOP);
        assertThat(result.reason()).contains("noop admin");
    }

    @Test
    void applyRetentionReturnsNoopVerdict() {
        final IndexSpec spec =
                new IndexSpec("tenant-a", "cortex-tenant-a-v1", "v1");
        final RetentionPolicy policy =
                new RetentionPolicy(Duration.ofDays(7));

        final IndexAdminResult result =
                this.admin.applyRetention(spec, policy);

        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_NOOP);
        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_NOOP);
        assertThat(result.reason()).contains("noop admin");
    }
}
