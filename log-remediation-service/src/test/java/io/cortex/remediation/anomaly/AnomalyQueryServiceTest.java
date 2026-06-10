package io.cortex.remediation.anomaly;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link AnomalyQueryService} validation and limit
 * normalization.
 */
class AnomalyQueryServiceTest {

    /** Tenant fixture used by the query tests. */
    private static final String TENANT = "tenant-query";

    /**
     * Missing tenant id is rejected before the repository is called.
     */
    @Test
    void blankTenantIsRejected() {
        final AnomalyQueryService service =
                new AnomalyQueryService(Mockito.mock(AnomaliesRepository.class));

        assertThatThrownBy(() -> service.find("   ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    /**
     * Inverted time windows are rejected.
     */
    @Test
    void sinceAfterUntilIsRejected() {
        final AnomalyQueryService service =
                new AnomalyQueryService(Mockito.mock(AnomaliesRepository.class));

        assertThatThrownBy(() -> service.find(TENANT,
                Instant.parse("2026-06-09T10:00:01Z"),
                Instant.parse("2026-06-09T10:00:00Z"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("since");
    }

    /**
     * Non-positive limits are rejected.
     */
    @Test
    void nonPositiveLimitIsRejected() {
        final AnomalyQueryService service =
                new AnomalyQueryService(Mockito.mock(AnomaliesRepository.class));

        assertThatThrownBy(() -> service.find(TENANT, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    /**
     * Missing limit defaults to the service default.
     */
    @Test
    void missingLimitUsesDefault() {
        final AnomaliesRepository repository = Mockito.mock(AnomaliesRepository.class);
        final AnomalyQueryService service = new AnomalyQueryService(repository);

        service.find("  " + TENANT + "  ", null, null, null);

        verify(repository).findByTenant(TENANT, null, null,
                AnomalyQueryService.DEFAULT_LIMIT);
    }

    /**
     * Oversized limits are clamped to protect the direct API.
     */
    @Test
    void oversizedLimitIsClamped() {
        final AnomaliesRepository repository = Mockito.mock(AnomaliesRepository.class);
        final AnomalyQueryService service = new AnomalyQueryService(repository);

        service.find(TENANT, null, null, AnomalyQueryService.MAX_LIMIT + 1);

        verify(repository).findByTenant(TENANT, null, null,
                AnomalyQueryService.MAX_LIMIT);
    }
}
