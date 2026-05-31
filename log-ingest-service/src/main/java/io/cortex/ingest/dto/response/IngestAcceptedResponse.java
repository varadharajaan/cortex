package io.cortex.ingest.dto.response;

import java.time.OffsetDateTime;

/**
 * Response body returned by {@code POST /api/v1/ingest/batch} when
 * the batch is accepted for asynchronous processing (HTTP 202).
 *
 * <p>P4.0 returns a constant-shape acknowledgement so callers can
 * begin wiring contract tests. {@code receivedCount} echoes the
 * size of the inbound batch; {@code receivedAt} is the server's
 * UTC timestamp at acceptance. P4.4 will add a queue-receipt
 * identifier once the binder lands.</p>
 *
 * @param receivedCount number of {@code LogEntry} elements
 *                      accepted from the request
 * @param receivedAt    server-side UTC acceptance timestamp
 */
public record IngestAcceptedResponse(
        int receivedCount,
        OffsetDateTime receivedAt) {
}
