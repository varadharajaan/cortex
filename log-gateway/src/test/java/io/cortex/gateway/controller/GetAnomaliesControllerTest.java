package io.cortex.gateway.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.Anomaly;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.exception.GlobalExceptionHandler;
import io.cortex.gateway.service.GetAnomaliesService;
import java.util.List;
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
 * Slice test for {@link GetAnomaliesController}: HTTP contract for
 * {@code GET /api/v1/anomalies}, including the happy path, the empty
 * list, the missing-tenant validation, and the downstream-failure
 * mapping surfaced from the shared {@link GetAnomaliesService} (P9.3b /
 * ADR-0049).
 */
@WebMvcTest(controllers = GetAnomaliesController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = "cortex.gateway.get-anomalies.enabled=true")
class GetAnomaliesControllerTest {

    /** Tenant forwarded via the X-Tenant-Id header. */
    private static final String TENANT = "tenant-1";

    /** MockMvc auto-configured by {@code @WebMvcTest}. */
    @Autowired private MockMvc mockMvc;

    /** Mocked anomalies service so the slice can simulate downstream outcomes. */
    @MockBean private GetAnomaliesService service;

    @Test
    @WithMockUser(username = "alice")
    void getAnomaliesReturns200WithList() throws Exception {
        final Anomaly anomaly = new Anomaly(
                TENANT, "evt-1", "HIGH", "spike", "2026-06-10T10:00:00Z", "ERROR",
                "payment-svc", "boom", 0.95, "latency", "pay-latency", "2026-06-10T10:00:01Z");
        when(this.service.getAnomalies(eq(TENANT), isNull(), isNull(), eq(10)))
                .thenReturn(List.of(anomaly));

        this.mockMvc.perform(get("/api/v1/anomalies")
                        .header("X-Tenant-Id", TENANT)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$[0].tenantId").value(TENANT))
                .andExpect(jsonPath("$[0].severity").value("HIGH"))
                .andExpect(jsonPath("$[0].confidence").value(0.95))
                .andExpect(jsonPath("$[0].remediationKey").value("pay-latency"))
                .andExpect(jsonPath("$[0].ts").value("2026-06-10T10:00:00Z"))
                .andExpect(jsonPath("$[0].receivedAt").value("2026-06-10T10:00:01Z"));
    }

    @Test
    @WithMockUser(username = "alice")
    void getAnomaliesReturns200WithEmptyList() throws Exception {
        when(this.service.getAnomalies(eq(TENANT), isNull(), isNull(), isNull()))
                .thenReturn(List.of());

        this.mockMvc.perform(get("/api/v1/anomalies").header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @WithMockUser(username = "alice")
    void getAnomaliesReturns400WhenTenantHeaderMissing() throws Exception {
        this.mockMvc.perform(get("/api/v1/anomalies"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void getAnomaliesReturns400WhenTenantHeaderBlank() throws Exception {
        this.mockMvc.perform(get("/api/v1/anomalies").header("X-Tenant-Id", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void getAnomaliesReturns422OnInvalidQuery() throws Exception {
        when(this.service.getAnomalies(eq(TENANT), eq("bad"), isNull(), isNull()))
                .thenThrow(new ApplicationException(
                        ErrorCodes.GET_ANOMALIES_INVALID, "remediation rejected query"));

        this.mockMvc.perform(get("/api/v1/anomalies")
                        .header("X-Tenant-Id", TENANT)
                        .param("since", "bad"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.GET_ANOMALIES_INVALID.name()));
    }

    @Test
    @WithMockUser(username = "alice")
    void getAnomaliesReturns502OnUpstreamFailure() throws Exception {
        when(this.service.getAnomalies(eq(TENANT), isNull(), isNull(), isNull()))
                .thenThrow(new ApplicationException(
                        ErrorCodes.GET_ANOMALIES_UPSTREAM_FAILED, "remediation unreachable"));

        this.mockMvc.perform(get("/api/v1/anomalies").header("X-Tenant-Id", TENANT))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.GET_ANOMALIES_UPSTREAM_FAILED.name()));
    }
}
