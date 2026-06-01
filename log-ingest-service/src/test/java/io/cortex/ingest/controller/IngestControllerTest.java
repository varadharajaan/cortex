package io.cortex.ingest.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.ingest.dto.request.IngestBatchRequest;
import io.cortex.ingest.dto.response.IngestAcceptedResponse;
import io.cortex.ingest.exception.GlobalExceptionHandler;
import io.cortex.ingest.security.ServiceJwtProperties;
import io.cortex.ingest.service.IngestService;
import io.cortex.ingest.tenant.TenantResolver;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice tests for {@link IngestController} (P4.1).
 *
 * <p>Verifies the HTTP contract:
 * {@code POST /api/v1/ingest/batch} returns {@code 202 Accepted}
 * with an {@link IngestAcceptedResponse} body for a valid payload,
 * {@code 400 VALIDATION_FAILED} when the {@code X-Tenant-Id} header
 * is missing (rejected by {@link TenantResolver}), {@code 400
 * VALIDATION_FAILED} for an empty batch, and {@code 400 BAD_REQUEST}
 * for a malformed body. The {@link IngestService} is mocked so the
 * slice does not need a real Postgres or
 * {@link io.cortex.ingest.persistence.RawLogRepository}.</p>
 */
@WebMvcTest(IngestController.class)
@Import({IngestControllerTest.TestBeans.class,
        TenantResolver.class,
        GlobalExceptionHandler.class})
class IngestControllerTest {

    /** MockMvc injected by Spring Boot's slice-test bootstrap. */
    @Autowired private MockMvc mvc;

    /** Mocked ingest service so persistence is not exercised in the slice. */
    @MockBean private IngestService ingestService;

    /**
     * Posts a 1-entry valid batch with {@code X-Tenant-Id} and
     * asserts 202 + acknowledgement body shape with the pinned
     * acceptance timestamp returned by the mock.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postBatchReturnsAccepted() throws Exception {
        when(this.ingestService.acceptBatch(
                any(IngestBatchRequest.class), eq("cortex-dev"), isNull()))
                .thenReturn(new IngestAcceptedResponse(
                        1,
                        OffsetDateTime.of(2026, 5, 31, 12, 0, 0, 0, ZoneOffset.UTC)));

        final String body = """
                {
                  "entries": [
                    {
                      "timestamp": "2026-05-31T12:00:00Z",
                      "level": "INFO",
                      "service": "cortex-test",
                      "message": "hello",
                      "labels": {"tenant": "cortex-dev"}
                    }
                  ]
                }
                """;
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .header("X-Tenant-Id", "cortex-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receivedCount", equalTo(1)))
                .andExpect(jsonPath("$.receivedAt", notNullValue()));
    }

    /**
     * Posts a valid batch carrying {@code Idempotency-Key} and
     * verifies the service receives the verbatim header value.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postBatchForwardsIdempotencyKey() throws Exception {
        when(this.ingestService.acceptBatch(
                any(IngestBatchRequest.class), eq("cortex-dev"), eq("idem-42")))
                .thenReturn(new IngestAcceptedResponse(
                        1,
                        OffsetDateTime.of(2026, 5, 31, 12, 0, 0, 0, ZoneOffset.UTC)));

        final String body = """
                {
                  "entries": [
                    {
                      "timestamp": "2026-05-31T12:00:00Z",
                      "level": "INFO",
                      "service": "cortex-test",
                      "message": "hello",
                      "labels": {}
                    }
                  ]
                }
                """;
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .header("X-Tenant-Id", "cortex-dev")
                        .header("Idempotency-Key", "idem-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        verify(this.ingestService).acceptBatch(
                any(IngestBatchRequest.class), eq("cortex-dev"), eq("idem-42"));
    }

    /**
     * A request without {@code X-Tenant-Id} is rejected by
     * {@link TenantResolver} as {@code 400 VALIDATION_FAILED}.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postWithoutTenantHeaderReturns400() throws Exception {
        final String body = """
                {
                  "entries": [
                    {
                      "timestamp": "2026-05-31T12:00:00Z",
                      "level": "INFO",
                      "service": "cortex-test",
                      "message": "hello",
                      "labels": {}
                    }
                  ]
                }
                """;
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", equalTo("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.detail",
                        equalTo("X-Tenant-Id header is required")));
    }

    /**
     * Posts an empty batch and asserts 400 with the RFC 7807 problem
     * shape carrying {@code errorCode=VALIDATION_FAILED}.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postEmptyBatchReturns400() throws Exception {
        final String body = "{\"entries\": []}";
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .header("X-Tenant-Id", "cortex-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", equalTo("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.instance", equalTo("/api/v1/ingest/batch")));
    }

    /**
     * Posts a malformed body and asserts 400 with
     * {@code errorCode=BAD_REQUEST}.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postMalformedReturns400() throws Exception {
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .header("X-Tenant-Id", "cortex-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", equalTo("BAD_REQUEST")));
    }

    /**
     * Provides the disabled {@link ServiceJwtProperties} needed by
     * the auto-imported {@code ServiceJwtFilter} component so the
     * WebMvcTest slice can construct without the full
     * {@code @EnableConfigurationProperties} wiring.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestBeans {

        /** Default constructor used by Spring. */
        TestBeans() {
            // no state
        }

        /**
         * Disabled {@link ServiceJwtProperties} so the auto-imported
         * {@code ServiceJwtFilter} component can be constructed in
         * the WebMvcTest slice.
         *
         * @return disabled service-JWT configuration
         */
        @org.springframework.context.annotation.Bean
        ServiceJwtProperties serviceJwtProperties() {
            return new ServiceJwtProperties(false, "");
        }
    }
}
