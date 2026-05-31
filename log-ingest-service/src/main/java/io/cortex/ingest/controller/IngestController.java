package io.cortex.ingest.controller;

import io.cortex.ingest.constants.ApiPaths;
import io.cortex.ingest.dto.request.IngestBatchRequest;
import io.cortex.ingest.dto.response.IngestAcceptedResponse;
import io.cortex.ingest.service.IngestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter for log-ingest-service.
 *
 * <p>P4.0 ships the single endpoint
 * {@code POST /api/v1/ingest/batch} (per D6 ratified 2026-05-31)
 * which validates the inbound {@link IngestBatchRequest} and returns
 * {@code 202 Accepted} with an {@link IngestAcceptedResponse} body.
 * P4.1..P4.4 layer persistence, dedupe, enrichment, and queue
 * publish behind the same surface.</p>
 */
@RestController
public class IngestController {

    /** Inbound ingestion port; injected via constructor (rule 14.1). */
    private final IngestService ingestService;

    /**
     * Constructor injection of the ingestion service port.
     *
     * @param ingestService inbound ingestion service; must not be
     *                      {@code null}
     */
    public IngestController(final IngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * Accepts a batch of log entries for asynchronous processing.
     *
     * @param request validated batch request payload
     * @return 202 Accepted with an {@link IngestAcceptedResponse}
     *         body describing the inbound entry count and the
     *         server-side acceptance timestamp
     */
    @PostMapping(
            value = ApiPaths.INGEST_BATCH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestAcceptedResponse> ingestBatch(
            @Valid @RequestBody final IngestBatchRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(this.ingestService.acceptBatch(request));
    }
}
