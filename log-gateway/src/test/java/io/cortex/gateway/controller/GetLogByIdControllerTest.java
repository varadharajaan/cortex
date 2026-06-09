package io.cortex.gateway.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.LogEntry;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.exception.GlobalExceptionHandler;
import io.cortex.gateway.service.GetLogByIdService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link GetLogByIdController}: HTTP contract for
 * {@code GET /api/v1/logs/{eventId}}, including the happy path, the
 * 404 miss, the missing-tenant validation, and the downstream-failure
 * mapping surfaced from the shared {@link GetLogByIdService} (P9.2b /
 * ADR-0049).
 */
@WebMvcTest(controllers = GetLogByIdController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "cortex.gateway.get-log-by-id.enabled=true")
class GetLogByIdControllerTest {

    /** Canonical event id used across the tests. */
    private static final String EVENT_ID = "evt-abc-123";

    /** Tenant forwarded via the X-Tenant-Id header. */
    private static final String TENANT = "tenant-1";

    /** MockMvc auto-configured by {@code @WebMvcTest}. */
    @Autowired private MockMvc mockMvc;

    /** Mocked get-by-id service so the slice can simulate downstream outcomes. */
    @MockBean private GetLogByIdService service;

    @Test
    @WithMockUser(username = "alice")
    void getLogByIdReturns200OnHit() throws Exception {
        final LogEntry entry = new LogEntry(
                EVENT_ID, TENANT, "2026-06-09T10:00:00Z", "ERROR", "payment-svc",
                "boom", Map.of("env", "prod"), "2026-06-09T10:00:01Z");
        when(this.service.getLogById(eq(EVENT_ID), eq(TENANT))).thenReturn(entry);

        this.mockMvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(EVENT_ID))
                .andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(jsonPath("$.level").value("ERROR"))
                .andExpect(jsonPath("$.service").value("payment-svc"))
                .andExpect(jsonPath("$.message").value("boom"))
                .andExpect(jsonPath("$.labels.env").value("prod"))
                .andExpect(jsonPath("$.ts").value("2026-06-09T10:00:00Z"))
                .andExpect(jsonPath("$.receivedAt").value("2026-06-09T10:00:01Z"));
    }

    @Test
    @WithMockUser(username = "alice")
    void getLogByIdReturns400WhenTenantHeaderMissing() throws Exception {
        this.mockMvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void getLogByIdReturns400WhenTenantHeaderBlank() throws Exception {
        this.mockMvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID)
                        .header("X-Tenant-Id", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void getLogByIdReturns404OnMiss() throws Exception {
        when(this.service.getLogById(eq(EVENT_ID), eq(TENANT)))
                .thenThrow(new ApplicationException(ErrorCodes.NOT_FOUND, "log not found"));

        this.mockMvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.NOT_FOUND.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void getLogByIdReturns502OnUpstreamFailure() throws Exception {
        when(this.service.getLogById(eq(EVENT_ID), eq(TENANT)))
                .thenThrow(new ApplicationException(
                        ErrorCodes.GET_LOG_BY_ID_UPSTREAM_FAILED, "ingest unreachable"));

        this.mockMvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.GET_LOG_BY_ID_UPSTREAM_FAILED.name()));
    }
}
