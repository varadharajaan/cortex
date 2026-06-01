package io.cortex.ingest;

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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test for the P4.1 persist-raw path
 * (controller -> service -> Spring Data JDBC -> Postgres).
 *
 * <p>Drives the live HTTP endpoint via {@link MockMvc} against a
 * real Postgres provided by Testcontainers and asserts the row
 * count of {@code raw_logs} reflects the inserts. Also exercises
 * the idempotent-batch contract: re-posting the same batch returns
 * 202 with the same {@code receivedCount} but the row count stays
 * constant (the UNIQUE constraint silently absorbs the duplicate).</p>
 *
 * <p>Named {@code *IT} so Failsafe runs it; Surefire ignores it.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IngestPersistenceIT {

    /** Shared Postgres 16 container; reused across all test methods. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** MockMvc driven by the running Spring context. */
    @Autowired private MockMvc mvc;

    /** Repository used to assert physical row deltas in {@code raw_logs}. */
    @Autowired private RawLogRepository repository;

    /** Default constructor used by JUnit. */
    IngestPersistenceIT() {
        // no state
    }

    /**
     * Posts a 2-entry batch and verifies both rows land in
     * {@code raw_logs} (delta = 2).
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postBatchPersistsRows() throws Exception {
        final long before = this.repository.count();
        final String body = """
                {
                  "entries": [
                    {
                      "timestamp": "2026-06-01T10:00:00Z",
                      "level": "INFO",
                      "service": "cortex-it",
                      "message": "persist-1",
                      "labels": {"env": "it"}
                    },
                    {
                      "timestamp": "2026-06-01T10:00:01Z",
                      "level": "INFO",
                      "service": "cortex-it",
                      "message": "persist-2",
                      "labels": {"env": "it"}
                    }
                  ]
                }
                """;
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .header("X-Tenant-Id", "cortex-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receivedCount").value(2));
        assertThat(this.repository.count()).isEqualTo(before + 2);
    }

    /**
     * Posts the same batch twice; verifies the second post returns
     * 202 with the same {@code receivedCount} and the row count
     * does not advance (cold-path dedupe via the UNIQUE constraint).
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void duplicateBatchAbsorbedByUniqueConstraint() throws Exception {
        final String body = """
                {
                  "entries": [
                    {
                      "timestamp": "2026-06-01T11:00:00Z",
                      "level": "INFO",
                      "service": "cortex-it",
                      "message": "dedupe-target",
                      "labels": {"env": "it"}
                    }
                  ]
                }
                """;
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .header("X-Tenant-Id", "cortex-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receivedCount").value(1));
        final long afterFirst = this.repository.count();

        this.mvc.perform(post("/api/v1/ingest/batch")
                        .header("X-Tenant-Id", "cortex-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.receivedCount").value(1));
        assertThat(this.repository.count()).isEqualTo(afterFirst);
    }

    /**
     * Posts a batch without the {@code X-Tenant-Id} header and
     * asserts {@code 400 VALIDATION_FAILED} surfaces; verifies no
     * rows were written.
     *
     * @throws Exception MockMvc may surface request-handling exceptions
     */
    @Test
    void postWithoutTenantHeaderPersistsNothing() throws Exception {
        final long before = this.repository.count();
        final String body = """
                {
                  "entries": [
                    {
                      "timestamp": "2026-06-01T12:00:00Z",
                      "level": "INFO",
                      "service": "cortex-it",
                      "message": "should-not-persist",
                      "labels": {}
                    }
                  ]
                }
                """;
        this.mvc.perform(post("/api/v1/ingest/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        assertThat(this.repository.count()).isEqualTo(before);
    }
}
