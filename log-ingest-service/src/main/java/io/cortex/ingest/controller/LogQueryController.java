package io.cortex.ingest.controller;

import io.cortex.ingest.constants.ApiPaths;
import io.cortex.ingest.constants.ErrorCodes;
import io.cortex.ingest.constants.HeaderNames;
import io.cortex.ingest.dto.response.LogResponse;
import io.cortex.ingest.exception.ApplicationException;
import io.cortex.ingest.persistence.RawLog;
import io.cortex.ingest.persistence.RawLogRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped read surface for a single persisted log row
 * (P9.2a / ADR-0022 Amendment 1).
 *
 * <p>{@code GET /api/v1/logs/{eventId}} backs the gateway
 * {@code getLogById} query (P9.2b). log-ingest-service is the
 * system-of-record for {@code raw_logs}, so the read lives here next
 * to the write ({@code POST /api/v1/ingest/batch}) rather than in the
 * gateway.</p>
 *
 * <p>The resolved tenant is taken from the required {@code X-Tenant-Id}
 * header -- the same single-source-of-truth posture as the P9.1a
 * indexer search surface -- and scopes the lookup so a caller can
 * never read another tenant's row by guessing an {@code eventId}.
 * There is no Spring Security on the ingest side: the gateway
 * authenticates the caller and forwards the resolved tenant header
 * over {@code lb://}.</p>
 *
 * <p>Verdict mapping (RFC 7807 via the existing
 * {@link io.cortex.ingest.exception.GlobalExceptionHandler}): hit
 * -&gt; 200 with a {@link LogResponse}; miss -&gt; 404 NOT_FOUND;
 * missing/blank tenant header -&gt; 400 VALIDATION_FAILED.</p>
 */
@RestController
public class LogQueryController {

    /** System-of-record repository for {@code raw_logs}. */
    private final RawLogRepository repository;

    /**
     * Constructor injection of the read repository (rule 14.1).
     *
     * @param repository the {@code raw_logs} repository; must not be
     *                   {@code null}
     */
    public LogQueryController(final RawLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Fetches one log row by tenant + {@code eventId}.
     *
     * @param eventId      server-computed dedupe key from the path
     * @param tenantHeader resolved tenant id from the required
     *                     {@code X-Tenant-Id} header; read as optional
     *                     so a missing value maps to 400 (not the
     *                     servlet's generic 500) -- the ingest
     *                     {@code GlobalExceptionHandler} has no
     *                     {@code MissingRequestHeaderException} arm
     * @return 200 with the {@link LogResponse} when the row exists
     * @throws ApplicationException {@code VALIDATION_FAILED} when the
     *                              tenant header is missing/blank;
     *                              {@code NOT_FOUND} when no row matches
     */
    @GetMapping(value = ApiPaths.LOGS_BY_ID, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LogResponse> getLogById(
            @PathVariable("eventId") final String eventId,
            @RequestHeader(value = HeaderNames.TENANT_ID, required = false)
            final String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id header is required");
        }
        final RawLog row = this.repository
                .findByTenantIdAndEventId(tenantHeader, eventId)
                .orElseThrow(() -> new ApplicationException(
                        ErrorCodes.NOT_FOUND, "no log found for the supplied tenant and eventId"));
        return ResponseEntity.ok(LogResponse.from(row));
    }
}
