package io.cortex.gateway.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.request.LogSearchRequest;
import io.cortex.gateway.dto.response.LogSearchResult;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.SearchLogsService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link SearchLogsGraphQlController} (P9.1b / ADR-0049).
 *
 * <p>The resolver reads the tenant from a {@code @ContextValue} that a
 * {@code WebGraphQlInterceptor} normally populates from the
 * {@code X-Tenant-Id} header. {@code @GraphQlTest} cannot easily seed
 * arbitrary context values, so this is a direct method-level unit test
 * of the resolver's delegation + tenant-guard logic. Live context
 * propagation is proven end-to-end by
 * {@code SearchLogsRestAndGraphQlParityIT}.</p>
 */
class SearchLogsGraphQlControllerTest {

    /** Canonical input for the happy-path delegation test. */
    private static final LogSearchRequest INPUT =
            new LogSearchRequest("cortex-tenant-1-logs", "level:ERROR", 25);

    @Test
    void searchLogsDelegatesToServiceWithContextTenant() {
        final SearchLogsService service = mock(SearchLogsService.class);
        final LogSearchResult expected = new LogSearchResult(1L, List.of(Map.of("k", "v")));
        when(service.search(eq(INPUT), eq("tenant-1"))).thenReturn(expected);

        final SearchLogsGraphQlController resolver = new SearchLogsGraphQlController(service);
        final LogSearchResult actual = resolver.searchLogs(INPUT, "tenant-1");

        assertThat(actual).isSameAs(expected);
        verify(service).search(INPUT, "tenant-1");
    }

    @Test
    void searchLogsRejectsMissingTenantWithoutCallingService() {
        final SearchLogsService service = mock(SearchLogsService.class);
        final SearchLogsGraphQlController resolver = new SearchLogsGraphQlController(service);

        assertThatThrownBy(() -> resolver.searchLogs(INPUT, null))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        verifyNoInteractions(service);
    }

    @Test
    void searchLogsRejectsBlankTenantWithoutCallingService() {
        final SearchLogsService service = mock(SearchLogsService.class);
        final SearchLogsGraphQlController resolver = new SearchLogsGraphQlController(service);

        assertThatThrownBy(() -> resolver.searchLogs(INPUT, "  "))
                .isInstanceOf(ApplicationException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCodes.VALIDATION_FAILED);
        verifyNoInteractions(service);
    }
}
