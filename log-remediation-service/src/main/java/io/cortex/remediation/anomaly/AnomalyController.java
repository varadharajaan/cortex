package io.cortex.remediation.anomaly;

import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct remediation-service read API for persisted anomalies (P9.3a).
 *
 * <p>This endpoint backs the future gateway REST + GraphQL parity
 * phase, but remains tenant-scoped through the explicit {@code tenantId}
 * query parameter until the gateway forwards an authenticated tenant
 * context in P9.3b.</p>
 */
@RestController
@RequestMapping(path = "/api/v1/anomalies", produces = MediaType.APPLICATION_JSON_VALUE)
public class AnomalyController {

    private final AnomalyQueryService queryService;

    /**
     * Constructor injection.
     *
     * @param queryService anomaly query service
     */
    public AnomalyController(final AnomalyQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Lists anomaly rows for one tenant.
     *
     * @param tenantId tenant scope query parameter
     * @param since    optional inclusive lower timestamp bound
     * @param until    optional inclusive upper timestamp bound
     * @param limit    optional row limit
     * @return matching anomalies ordered newest first
     */
    @GetMapping
    public ResponseEntity<List<AnomalyResponse>> list(
            @RequestParam("tenantId") final String tenantId,
            @RequestParam(value = "since", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final Instant since,
            @RequestParam(value = "until", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) final Instant until,
            @RequestParam(value = "limit", required = false) final Integer limit) {
        final List<AnomalyResponse> body = this.queryService
                .find(tenantId, since, until, limit)
                .stream()
                .map(AnomalyResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }
}
