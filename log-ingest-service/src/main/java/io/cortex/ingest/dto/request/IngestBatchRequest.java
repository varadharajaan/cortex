package io.cortex.ingest.dto.request;

import io.cortex.agent.LogEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Batch-ingest request body for
 * {@code POST /api/v1/ingest/batch}.
 *
 * <p>P4.0 ships the contract + Bean Validation annotations. The
 * controller currently accepts and returns 202 with no further
 * processing. P4.1 will add a Spring Data JDBC writer that
 * persists {@code entries} to the {@code raw_logs} table; P4.2
 * layers dedupe + PII masking on top; P4.3 enriches with
 * tenant + correlation labels; P4.4 publishes the enriched event
 * to the queue.</p>
 *
 * <p>A batch is capped at 1000 entries to bound memory pressure
 * per request. Callers that exceed the cap must split the batch
 * client-side.</p>
 *
 * @param entries the log events to ingest; must be non-empty and
 *                contain at most 1000 entries; each element is
 *                cascade-validated via {@link Valid} so the
 *                {@link LogEntry} canonical constructor invariants
 *                surface as 400 problems
 */
public record IngestBatchRequest(
        @NotEmpty(message = "entries must not be empty")
        @Size(max = 1000, message = "entries must contain at most 1000 elements")
        @Valid List<LogEntry> entries) {
}
