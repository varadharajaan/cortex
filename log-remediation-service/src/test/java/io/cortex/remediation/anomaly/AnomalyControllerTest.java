package io.cortex.remediation.anomaly;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice tests for {@link AnomalyController} (P9.3a).
 */
@WebMvcTest(AnomalyController.class)
@Import(AnomalyControllerAdvice.class)
class AnomalyControllerTest {

    /** Tenant query parameter fixture. */
    private static final String TENANT = "tenant-api";

    /** MockMvc injected by the slice test. */
    @Autowired private MockMvc mvc;

    /** Mocked query service; persistence is covered by ITs. */
    @MockBean private AnomalyQueryService queryService;

    /**
     * Happy path returns a JSON array ordered by the service result.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    void listReturns200WithAnomalies() throws Exception {
        final Instant since = Instant.parse("2026-06-09T10:00:00Z");
        final Instant until = Instant.parse("2026-06-09T11:00:00Z");
        final AnomalyRecord row = new AnomalyRecord(
                7L,
                TENANT,
                "evt-api-1",
                "HIGH",
                "burst",
                Instant.parse("2026-06-09T10:30:00Z"),
                "ERROR",
                "checkout",
                "503",
                0.91d,
                "LATENCY",
                "restart-checkout",
                Instant.parse("2026-06-09T10:30:01Z"));
        when(this.queryService.find(eq(TENANT), eq(since), eq(until), eq(25)))
                .thenReturn(List.of(row));

        this.mvc.perform(get("/api/v1/anomalies")
                        .param("tenantId", TENANT)
                        .param("since", "2026-06-09T10:00:00Z")
                        .param("until", "2026-06-09T11:00:00Z")
                        .param("limit", "25")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(TENANT))
                .andExpect(jsonPath("$[0].eventId").value("evt-api-1"))
                .andExpect(jsonPath("$[0].severity").value("HIGH"))
                .andExpect(jsonPath("$[0].service").value("checkout"))
                .andExpect(jsonPath("$[0].confidence").value(0.91d))
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    /**
     * Missing tenant id returns the local validation envelope.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    void listReturns400WhenTenantMissing() throws Exception {
        this.mvc.perform(get("/api/v1/anomalies")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    /**
     * Invalid ISO timestamps are rejected by the controller advice.
     *
     * @throws Exception on MockMvc failure
     */
    @Test
    void listReturns400WhenTimestampInvalid() throws Exception {
        this.mvc.perform(get("/api/v1/anomalies")
                        .param("tenantId", TENANT)
                        .param("since", "not-an-instant")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }
}
