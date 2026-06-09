package io.cortex.gateway.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.LogEntry;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.GetLogByIdService;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link GetLogByIdGraphQlController} (P9.2b / ADR-0049).
 *
 * <p>The resolver reads the tenant from a {@code @ContextValue} that a
 * {@code WebGraphQlInterceptor} normally populates from the
 * {@code X-Tenant-Id} header; {@code @GraphQlTest} cannot easily seed
 * context values, so this is a direct method-level unit test of the
 * delegation + tenant-guard logic. Live context propagation is proven by
 * {@code GetLogByIdRestAndGraphQlParityIT}.</p>
 */
class GetLogByIdGraphQlControllerTest {

    private static final String EVENT_ID = "evt-abc-123";

    @Test
    void getLogByIdDelegatesToServiceWithContextTenant() {
        final GetLogByIdService service = mock(GetLogByIdService.class);
        final LogEntry expected = new LogEntry(
                EVENT_ID, "tenant-1", "2026-06-09T10:00:00Z", "ERROR", "svc",
                "boom", Map.of("k", "v"), "2026-06-09T10:00:01Z");
        when(service.getLogById(eq(EVENT_ID), eq("tenant-1"))).thenReturn(expected);

        final GetLogByIdGraphQlController resolver = new GetLogByIdGraphQlController(service);
        final LogEntry actual = resolver.getLogById(EVENT_ID, "tenant-1");

        assertThat(actual).isSameAs(expected);
        verify(service).getLogById(EVENT_ID, "tenant-1");
    }

    @Test
    void getLogByIdRejectsMissingTenantWithoutCallingService() {
        final GetLogByIdService service = mock(GetLogByIdService.class);
        final GetLogByIdGraphQlController resolver = new GetLogByIdGraphQlController(service);

        assertThatThrownBy(() -> resolver.getLogById(EVENT_ID, null))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        verifyNoInteractions(service);
    }

    @Test
    void getLogByIdRejectsBlankTenantWithoutCallingService() {
        final GetLogByIdService service = mock(GetLogByIdService.class);
        final GetLogByIdGraphQlController resolver = new GetLogByIdGraphQlController(service);

        assertThatThrownBy(() -> resolver.getLogById(EVENT_ID, "  "))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        verifyNoInteractions(service);
    }
}
