package io.cortex.echo.controller;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice tests for {@link EchoController}.
 *
 * <p>Verifies the echo controller mirrors the inbound request back to
 * the caller with the upstream service identifier, request path,
 * method, and (lower-cased) header map.</p>
 */
@WebMvcTest(EchoController.class)
class EchoControllerTest {

    /** MockMvc injected by Spring Boot's slice-test bootstrap. */
    @Autowired private MockMvc mvc;

    /**
     * GET /echo/ping returns the request metadata as JSON.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void getReturnsEchoBody() throws Exception {
        this.mvc.perform(get("/echo/ping")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer fake-token")
                        .header("X-Tenant-Id", "acme")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.upstream", equalTo("log-echo-service")))
                .andExpect(jsonPath("$.path", equalTo("/echo/ping")))
                .andExpect(jsonPath("$.method", equalTo("GET")))
                .andExpect(jsonPath("$.headers.authorization", equalTo("Bearer fake-token")))
                .andExpect(jsonPath("$.headers['x-tenant-id']", equalTo("acme")));
    }

    /**
     * POST /echo (no sub-path) returns the request metadata as JSON.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postRootEchoReturnsEchoBody() throws Exception {
        this.mvc.perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hello\":\"world\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstream", equalTo("log-echo-service")))
                .andExpect(jsonPath("$.path", equalTo("/echo")))
                .andExpect(jsonPath("$.method", equalTo("POST")));
    }

    /**
     * GET /api/v1/logs/foo (P3.4 placeholder path) returns the request
     * metadata as JSON. log-echo-service stands in for the future
     * log-ingest-service so the gateway smoke can prove end-to-end
     * routing via {@code lb://log-echo-service}.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void getLogsPathReturnsEchoBody() throws Exception {
        this.mvc.perform(get("/api/v1/logs/echo")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstream", equalTo("log-echo-service")))
                .andExpect(jsonPath("$.path", equalTo("/api/v1/logs/echo")))
                .andExpect(jsonPath("$.method", equalTo("GET")));
    }

    /**
     * GET /api/v1/search/foo (P3.4 placeholder path) returns the
     * request metadata as JSON. Same rationale as
     * {@link #getLogsPathReturnsEchoBody()} for the search route.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void getSearchPathReturnsEchoBody() throws Exception {
        this.mvc.perform(get("/api/v1/search/echo")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upstream", equalTo("log-echo-service")))
                .andExpect(jsonPath("$.path", equalTo("/api/v1/search/echo")))
                .andExpect(jsonPath("$.method", equalTo("GET")));
    }
}
