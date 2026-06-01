package io.cortex.ingest.controller;

import io.cortex.ingest.constants.ApiPaths;
import io.cortex.ingest.constants.HeaderNames;
import io.cortex.ingest.dto.request.IngestBatchRequest;
import io.cortex.ingest.dto.response.IngestAcceptedResponse;
import io.cortex.ingest.service.IngestService;
import io.cortex.ingest.tenant.TenantResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound HTTP adapter for log-ingest-service.
 *
 * <p>P4.0 shipped the single endpoint
 * {@code POST /api/v1/ingest/batch} (per D6 ratified 2026-05-31)
 * which validates the inbound {@link IngestBatchRequest} and
 * returns {@code 202 Accepted} with an {@link IngestAcceptedResponse}
 * body. P4.1 (this commit) keeps the same surface and now requires
 * the {@code X-Tenant-Id} header (resolved by
 * {@link TenantResolver}) so the service can persist each row
 * against the correct {@code tenant_id} foreign key. The optional
 * {@code Idempotency-Key} header is recorded with each row for
 * P4.2 hot-path dedupe.</p>
 */
@RestController
public class IngestController {

    /** Inbound ingestion port; injected via constructor (rule 14.1). */
    private final IngestService ingestService;

    /** Resolves the active tenant id from inbound headers (P4.1 / D5). */
    private final TenantResolver tenantResolver;

    /**
     * Constructor injection of the ingestion service port and
     * tenant resolver.
     *
     * @param ingestService  inbound ingestion service; must not be
     *                       {@code null}
     * @param tenantResolver active-tenant resolver; must not be
     *                       {@code null}
     */
    public IngestController(final IngestService ingestService,
                            final TenantResolver tenantResolver) {
        this.ingestService = ingestService;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Accepts a batch of log entries for asynchronous processing.
     *
     * @param request         validated batch request payload
     * @param tenantHeader    raw {@code X-Tenant-Id} header value;
     *                        rejected with 400 VALIDATION_FAILED via
     *                        {@link TenantResolver} when missing or
     *                        blank
     * @param idempotencyKey  optional {@code Idempotency-Key} header
     *                        recorded against every row in the
     *                        batch; ignored by P4.1 (Redis SETNX
     *                        lands in P4.2)
     * @return 202 Accepted with an {@link IngestAcceptedResponse}
     *         body describing the inbound entry count and the
     *         server-side acceptance timestamp
     */
    @PostMapping(
            value = ApiPaths.INGEST_BATCH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<IngestAcceptedResponse> ingestBatch(
            @Valid @RequestBody final IngestBatchRequest request,
            @RequestHeader(value = HeaderNames.TENANT_ID, required = false)
            final String tenantHeader,
            @RequestHeader(value = HeaderNames.IDEMPOTENCY_KEY, required = false)
            final String idempotencyKey) {
        final String tenantId = this.tenantResolver.resolve(tenantHeader);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(this.ingestService.acceptBatch(request, tenantId, idempotencyKey));
    }
}
