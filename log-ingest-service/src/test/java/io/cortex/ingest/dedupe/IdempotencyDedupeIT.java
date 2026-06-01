package io.cortex.ingest.dedupe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.ingest.persistence.RawLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test for the P4.2 hot-path dedupe
 * (controller -> service -> {@link IdempotencyDedupeService} ->
 * real Redis) plus the cold-path UNIQUE backstop on Postgres
 * (D3 / D4 / plan.md row P4.2 / spec Sec 5.3).
 *
 * <p>Stands up TWO Testcontainers: Postgres 16 (wired via
 * {@code @ServiceConnection}) and Redis 7 (wired via
 * {@code @DynamicPropertySource} which also flips
 * {@code cortex.ingest.dedupe.enabled} back to {@code true} since
 * the shared test classpath {@code application.yml} disables it).</p>
 *
 * <p>Named {@code *IT} so Failsafe runs it; Surefire ignores it.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IdempotencyDedupeIT {

    /** Default Redis TCP port; both exposed and mapped via Testcontainers. */
    private static final int REDIS_PORT = 6379;

    /** Single-entry batch body reused by the hot-path replay test. */
    private static final String BATCH_BODY = """
            {
              "entries": [
                {
                  "timestamp": "2026-06-02T10:00:00Z",
                  "level": "INFO",
                  "service": "cortex-it",
                  "message": "hot-dedupe-target",
                  "labels": {"env": "it"}
                }
              ]
            }
            """;

    /** Shared Postgres 16 container; reused across all test methods. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Shared Redis 7 container; reused across all test methods. */
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    /**
     * Wires the dynamic Redis host/port back into Spring
     * properties and re-enables the dedupe bean for this IT.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void redisProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(REDIS_PORT));
        registry.add("cortex.ingest.dedupe.enabled", () -> "true");
    }

    /** Production dedupe service under test (real Redis backed). */
    @Autowired private IdempotencyDedupeService dedupe;

    /** MockMvc driven by the running Spring context. */
    @Autowired private MockMvc mvc;

    /** Repository used to assert physical row deltas in {@code raw_logs}. */
    @Autowired private RawLogRepository repository;

    /** Default constructor used by JUnit. */
    IdempotencyDedupeIT() {
        // no state
    }

    /**
     * Confirms the SETNX contract end-to-end against a real Redis:
     * first claim returns {@code true}; the immediate replay with
     * the same {@code (tenant, key)} pair returns {@code false}.
     */
    @Test
    void claimReturnsTrueFirstFalseSecond() {
        final boolean first = this.dedupe.claim("cortex-dev", "idem-it-1");
        final boolean second = this.dedupe.claim("cortex-dev", "idem-it-1");
        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    /**
     * Different tenants sharing the same idempotency-key value MUST
     * each claim independently (the key includes the tenant prefix).
     */
    @Test
    void claimIsScopedPerTenant() {
        final boolean alphaFirst = this.dedupe.claim("tenant-alpha", "idem-shared");
        final boolean betaFirst = this.dedupe.claim("tenant-beta", "idem-shared");
        assertThat(alphaFirst).isTrue();
        assertThat(betaFirst).isTrue();
    }

    /**
     * Posts the same batch twice with the same
     * {@code Idempotency-Key} header and asserts that the second
     * post is absorbed by the hot path: 202 is still returned with
     * the same {@code receivedCount}, but the {@code raw_logs} row
     * count does NOT advance.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void duplicatePostWithIdempotencyKeyIsShortCircuited() throws Exception {
        final long before = this.repository.count();
        postBatchExpect202(BATCH_BODY, "idem-it-batch-1");
        final long afterFirst = this.repository.count();
        assertThat(afterFirst).isEqualTo(before + 1);

        postBatchExpect202(BATCH_BODY, "idem-it-batch-1");
        assertThat(this.repository.count()).isEqualTo(afterFirst);
    }

    /**
     * Helper: POSTs the supplied JSON body with the supplied
     * {@code Idempotency-Key} header and asserts 202 +
     * {@code receivedCount == 1}.
     *
     * @param body            JSON request body
     * @param idempotencyKey  value for the {@code Idempotency-Key}
     *                        header
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    private void postBatchExpect202(final String body, final String idempotencyKey)
            throws Exception {
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .header("X-Tenant-Id", "cortex-dev")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receivedCount").value(1));
    }
}
