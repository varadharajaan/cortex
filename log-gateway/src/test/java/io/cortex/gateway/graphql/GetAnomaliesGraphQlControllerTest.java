package io.cortex.gateway.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.Anomaly;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.GetAnomaliesService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link GetAnomaliesGraphQlController} (P9.3b / ADR-0049).
 *
 * <p>The resolver reads the tenant from a {@code @ContextValue} that a
 * {@code WebGraphQlInterceptor} normally populates from the
 * {@code X-Tenant-Id} header; {@code @GraphQlTest} cannot easily seed
 * context values, so this is a direct method-level unit test of the
 * delegation + tenant-guard logic. Live context propagation is proven by
 * {@code GetAnomaliesRestAndGraphQlParityIT}.</p>
 */
class GetAnomaliesGraphQlControllerTest {

    private static final String TENANT = "tenant-1";

    @Test
    void getAnomaliesDelegatesToServiceWithContextTenant() {
        final GetAnomaliesService service = mock(GetAnomaliesService.class);
        final Anomaly anomaly = new Anomaly(
                TENANT, "evt-1", "HIGH", "spike", "2026-06-10T10:00:00Z", "ERROR",
                "svc", "boom", 0.95, "latency", "pay-latency", "2026-06-10T10:00:01Z");
        when(service.getAnomalies(eq(TENANT), isNull(), isNull(), eq(10)))
                .thenReturn(List.of(anomaly));

        final GetAnomaliesGraphQlController resolver = new GetAnomaliesGraphQlController(service);
        final List<Anomaly> actual = resolver.getAnomalies(null, null, 10, TENANT);

        assertThat(actual).containsExactly(anomaly);
        verify(service).getAnomalies(TENANT, null, null, 10);
    }

    @Test
    void getAnomaliesRejectsMissingTenantWithoutCallingService() {
        final GetAnomaliesService service = mock(GetAnomaliesService.class);
        final GetAnomaliesGraphQlController resolver = new GetAnomaliesGraphQlController(service);

        assertThatThrownBy(() -> resolver.getAnomalies(null, null, null, null))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        verifyNoInteractions(service);
    }

    @Test
    void getAnomaliesRejectsBlankTenantWithoutCallingService() {
        final GetAnomaliesService service = mock(GetAnomaliesService.class);
        final GetAnomaliesGraphQlController resolver = new GetAnomaliesGraphQlController(service);

        assertThatThrownBy(() -> resolver.getAnomalies(null, null, null, "  "))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        verifyNoInteractions(service);
    }
}
