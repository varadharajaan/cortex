package io.cortex.ingest.service;

import io.cortex.ingest.dto.request.IngestBatchRequest;
import io.cortex.ingest.dto.response.IngestAcceptedResponse;

/**
 * Inbound ingestion port for log-ingest-service.
 *
 * <p>P4.0 ships the interface + a no-op implementation
 * ({@link io.cortex.ingest.service.impl.IngestServiceImpl}) that
 * simply acknowledges the batch with {@code receivedCount} +
 * {@code receivedAt}. Subsequent sub-phases progressively layer
 * behaviour BEHIND this interface so the controller surface stays
 * stable:</p>
 * <ul>
 *   <li>P4.1 - persist raw to Postgres {@code raw_logs} via Spring
 *       Data JDBC.</li>
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
     * @param request validated batch request payload; must not be
     *                {@code null}
     * @return acknowledgement carrying the inbound entry count and
     *         the server-side acceptance timestamp; never {@code null}
     */
    IngestAcceptedResponse acceptBatch(IngestBatchRequest request);
}
