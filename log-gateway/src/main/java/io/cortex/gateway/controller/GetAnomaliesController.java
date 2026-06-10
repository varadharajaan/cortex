package io.cortex.gateway.controller;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.dto.response.Anomaly;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.GetAnomaliesService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped anomalies-read endpoint (P9.3b / ADR-0004 / ADR-0049).
 *
 * <p>{@code GET /api/v1/anomalies?since=&until=&limit=} delegates to the
 * shared {@link GetAnomaliesService}, which forwards the listing to
 * log-remediation-service (P9.3a). The resolved tenant is read from the
 * {@code X-Tenant-Id} header (ADR-0009) and forwarded downstream as the
 * {@code tenantId} query parameter (the remediation backer's single
 * source of truth), so a query parameter can never spoof another
 * tenant's anomalies.</p>
 *
 * <p>This is a gateway-owned endpoint, not a proxy route -- the gateway
 * does not proxy {@code /api/v1/anomalies} to any service, so the request
 * reaches this controller directly. Conditional on
 * {@code cortex.gateway.get-anomalies.enabled=true}; when disabled the
 * bean is not registered and the path returns 404. The
 * {@link RateLimitFeature @RateLimitFeature} sub-bucket is shared with
 * the GraphQL surface (same annotation members) so both resolve to one
 * Bucket4j bucket per JWT subject (P9.0a parity).</p>
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.get-anomalies", name = "enabled", havingValue = "true")
public class GetAnomaliesController {

    /** Shared anomalies-read logic (also used by the GraphQL resolver). */
    private final GetAnomaliesService service;

    /**
     * Lists anomalies for the resolved tenant.
     *
     * @param tenantId resolved tenant id from the required
     *                 {@code X-Tenant-Id} header
     * @param since    optional inclusive ISO-8601 lower bound ({@code since}
     *                 query parameter)
     * @param until    optional inclusive ISO-8601 upper bound ({@code until}
     *                 query parameter)
     * @param limit    optional row limit ({@code limit} query parameter);
     *                 strictly positive when present
     * @return HTTP 200 with the {@link Anomaly} list body
     * @throws ApplicationException carrying {@link ErrorCodes#VALIDATION_FAILED}
     *         when the tenant header is missing/blank
     */
    @GetMapping(ApiPaths.ANOMALIES)
    @PreAuthorize("isAuthenticated()")
    @RateLimitFeature(
            name = "get-anomalies",
            capacity = "${cortex.gateway.get-anomalies.sub-bucket-capacity:60}",
            refill = "${cortex.gateway.get-anomalies.sub-bucket-refill-period:PT1M}",
            errorCode = "GET_ANOMALIES_RATE_LIMITED",
            keyPrefix = "${cortex.gateway.get-anomalies.sub-bucket-key-prefix:cortex:rl:anom:}")
    public ResponseEntity<List<Anomaly>> getAnomalies(
            @RequestHeader(value = HeaderNames.X_TENANT_ID, required = false) final String tenantId,
            @RequestParam(value = "since", required = false) final String since,
            @RequestParam(value = "until", required = false) final String until,
            @RequestParam(value = "limit", required = false) final Integer limit) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id header is required");
        }
        return ResponseEntity.ok(this.service.getAnomalies(tenantId, since, until, limit));
    }
}
