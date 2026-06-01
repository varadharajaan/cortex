package io.cortex.ingest.service;

import io.cortex.ingest.dto.request.IngestBatchRequest;
import io.cortex.ingest.dto.response.IngestAcceptedResponse;

/**
 * Inbound ingestion port for log-ingest-service.
 *
 * <p>P4.1 persists every accepted batch entry to the
 * {@code raw_logs} table behind this interface. Subsequent
 * sub-phases progressively layer behaviour BEHIND the same surface
 * so the controller contract stays stable:</p>
 * <ul>
 *   <li>P4.1 (this commit) - persist raw to Postgres
 *       {@code raw_logs} via Spring Data JDBC with a server-
 *       computed dedupe key.</li>
 *   <li>P4.2 - dedupe via Redis SETNX + PII mask via
 *       {@code log-agent-lib}.</li>
 *   <li>P4.3 - enrich with correlation id, tenant id, label
 *       normalisation, {@code received_at}.</li>
 *   <li>P4.4 - publish enriched event to the queue (Service Bus
 *       in {@code prod}, Kafka in {@code dev}/{@code ci}) using
 *       the transactional outbox pattern.</li>
 * </ul>
 */
public interface IngestService {

    /**
     * Accepts a batch of log entries for asynchronous processing.
     *
     * @param request        validated batch request payload; must
     *                       not be {@code null}
     * @param tenantId       resolved tenant id; must not be
     *                       {@code null} or blank
     * @param idempotencyKey verbatim {@code Idempotency-Key} header
     *                       from the inbound request; may be
     *                       {@code null} when absent
     * @return acknowledgement carrying the persisted entry count and
     *         the server-side acceptance timestamp; never
     *         {@code null}
     */
    IngestAcceptedResponse acceptBatch(
            IngestBatchRequest request,
            String tenantId,
            String idempotencyKey);
}
