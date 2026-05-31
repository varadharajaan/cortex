package io.cortex.ingest.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.ingest.exception.GlobalExceptionHandler;
import io.cortex.ingest.security.ServiceJwtProperties;
import io.cortex.ingest.service.impl.IngestServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice tests for {@link IngestController}.
 *
 * <p>Verifies the P4.0 scaffold contract:
 * {@code POST /api/v1/ingest/batch} returns {@code 202 Accepted}
 * with an {@code IngestAcceptedResponse} body for a valid payload,
 * and returns {@code 400 Bad Request} with the RFC 7807 problem
 * shape for an empty batch.</p>
 */
@WebMvcTest(IngestController.class)
@Import({IngestControllerTest.TestBeans.class,
        IngestServiceImpl.class,
        GlobalExceptionHandler.class})
class IngestControllerTest {

    /** MockMvc injected by Spring Boot's slice-test bootstrap. */
    @Autowired private MockMvc mvc;

    /**
     * Posts a 1-entry valid batch and asserts 202 + acknowledgement
     * body shape with the pinned acceptance timestamp.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postBatchReturnsAccepted() throws Exception {
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receivedCount", equalTo(1)))
                .andExpect(jsonPath("$.receivedAt", notNullValue()));
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", equalTo("BAD_REQUEST")));
    }

    /**
     * Provides the {@link Clock} bean needed by
     * {@link IngestServiceImpl} so the slice test does not require
     * the full {@code @SpringBootTest} bootstrap.
     */
    @TestConfiguration
    static class TestBeans {

        /** Default constructor used by Spring. */
        TestBeans() {
            // no state
        }

        /**
         * Pinned UTC clock so the acceptance timestamp is deterministic.
         *
         * @return clock pinned to 2026-05-31T12:00:00Z
         */
        @Bean
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-05-31T12:00:00Z"), ZoneOffset.UTC);
        }

        /**
         * Disabled {@link ServiceJwtProperties} so the auto-imported
         * {@code ServiceJwtFilter} component can be constructed in
         * the WebMvcTest slice without pulling in the full
         * {@code @EnableConfigurationProperties} wiring.
         *
         * @return disabled service-JWT configuration
         */
        @Bean
        ServiceJwtProperties serviceJwtProperties() {
            return new ServiceJwtProperties(false, "");
        }
    }
}
