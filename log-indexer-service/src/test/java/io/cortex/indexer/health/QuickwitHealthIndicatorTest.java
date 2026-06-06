package io.cortex.indexer.health;

import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.admin.NoopQuickwitIndexAdmin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuickwitHealthIndicator} (P7.0 / ADR-0038 D4).
 * Verifies the indicator reports UP + surfaces the active admin
 * backend id as a detail.
 */
class QuickwitHealthIndicatorTest {

    @Test
    void reportsUpForNoopBackend() {
        final QuickwitHealthIndicator indicator =
                new QuickwitHealthIndicator(new NoopQuickwitIndexAdmin());

        final Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void exposesActiveBackendIdAsDetail() {
        final QuickwitHealthIndicator indicator =
                new QuickwitHealthIndicator(new NoopQuickwitIndexAdmin());

        final Health health = indicator.health();

        assertThat(health.getDetails())
                .containsEntry("backend", IndexAdminResult.BACKEND_NOOP);
    }
}
