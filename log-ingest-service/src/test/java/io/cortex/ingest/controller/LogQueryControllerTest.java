package io.cortex.ingest.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.ingest.constants.ErrorCodes;
import io.cortex.ingest.exception.GlobalExceptionHandler;
import io.cortex.ingest.persistence.RawLog;
import io.cortex.ingest.persistence.RawLogRepository;
import io.cortex.ingest.security.ServiceJwtProperties;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice tests for {@link LogQueryController}
 * (P9.2a / ADR-0022 Amendment 1).
 *
 * <p>Verifies the HTTP contract of {@code GET /api/v1/logs/{eventId}}:
 * a hit returns {@code 200} with the projected {@link
 * io.cortex.ingest.dto.response.LogResponse} body; a miss returns
 * {@code 404 NOT_FOUND}; a missing/blank {@code X-Tenant-Id} header
 * returns {@code 400 VALIDATION_FAILED}. The {@link RawLogRepository}
 * is mocked so the slice needs neither Postgres nor Testcontainers.</p>
 */
@WebMvcTest(LogQueryController.class)
@Import({LogQueryControllerTest.TestBeans.class, GlobalExceptionHandler.class})
class LogQueryControllerTest {

    /** Canonical event id used across the hit/miss cases. */
    private static final String EVENT_ID = "evt-slice-1";

    /** Tenant forwarded via the X-Tenant-Id header. */
    private static final String TENANT = "cortex-dev";

    /** MockMvc injected by Spring Boot's slice-test bootstrap. */
    @Autowired private MockMvc mvc;

    /** Mocked repository so persistence is not exercised in the slice. */
    @MockBean private RawLogRepository repository;

    @Test
    void getLogByIdReturns200OnHit() throws Exception {
        final RawLog row = new RawLog(
                42L,
                TENANT,
                EVENT_ID,
                Instant.parse("2026-06-09T10:00:00Z"),
                "ERROR",
                "payment-svc",
                "boom",
                Map.of("env", "prod"),
                "idem-key-ignored",
                Instant.parse("2026-06-09T10:00:01Z"));
        when(this.repository.findByTenantIdAndEventId(eq(TENANT), eq(EVENT_ID)))
                .thenReturn(Optional.of(row));

        this.mvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID)
                        .header("X-Tenant-Id", TENANT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(EVENT_ID))
                .andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(jsonPath("$.level").value("ERROR"))
                .andExpect(jsonPath("$.service").value("payment-svc"))
                .andExpect(jsonPath("$.message").value("boom"))
                .andExpect(jsonPath("$.labels.env").value("prod"))
                // Internal fields must NOT leak onto the wire.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
    }

    @Test
    void getLogByIdReturns404OnMiss() throws Exception {
        when(this.repository.findByTenantIdAndEventId(eq(TENANT), eq(EVENT_ID)))
                .thenReturn(Optional.empty());

        this.mvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID)
                        .header("X-Tenant-Id", TENANT)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.NOT_FOUND.name()));
    }

    @Test
    void getLogByIdReturns400WhenTenantHeaderMissing() throws Exception {
        this.mvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    @Test
    void getLogByIdReturns400WhenTenantHeaderBlank() throws Exception {
        this.mvc.perform(get("/api/v1/logs/{eventId}", EVENT_ID)
                        .header("X-Tenant-Id", "   ")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value(ErrorCodes.VALIDATION_FAILED.name()));
    }

    /**
     * Provides the disabled {@link ServiceJwtProperties} the
     * auto-imported {@code ServiceJwtFilter} component needs so the
     * {@code @WebMvcTest} slice context can be constructed (mirrors
     * {@code IngestControllerTest.TestBeans}).
     */
    @TestConfiguration
    static class TestBeans {

        /** Default constructor used by Spring. */
        TestBeans() {
            // no state
        }

        /**
         * Disabled {@link ServiceJwtProperties} so the auto-imported
         * {@code ServiceJwtFilter} can be constructed in the slice.
         *
         * @return disabled service-JWT configuration
         */
        @Bean
        ServiceJwtProperties serviceJwtProperties() {
            return new ServiceJwtProperties(false, "");
        }
    }
}
