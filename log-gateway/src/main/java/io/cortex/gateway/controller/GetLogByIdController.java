package io.cortex.gateway.controller;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.dto.response.LogEntry;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.GetLogByIdService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped get-log-by-id endpoint (P9.2b / ADR-0004 / ADR-0049).
 *
 * <p>{@code GET /api/v1/logs/{eventId}} delegates to the shared
 * {@link GetLogByIdService}, which forwards the lookup to
 * log-ingest-service (P9.2a). The resolved tenant is read from the
 * {@code X-Tenant-Id} header (ADR-0009) and forwarded downstream as the
 * single source of truth, so a path variable can never read another
 * tenant's row.</p>
 *
 * <p>This is a gateway-owned endpoint, not a proxy route. It sits under
 * the {@code /api/v1/logs/**} prefix that
 * {@link io.cortex.gateway.config.GatewayRoutesConfig} proxies to
 * log-ingest-service, but that proxy matches only {@code POST} (the
 * ingest write surface) as of P9.1c (ADR-0049 Amendment 4), so this
 * {@code GET} falls through to the controller. The literal
 * {@code /api/v1/logs/search} mapping
 * ({@link SearchLogsController}) is more specific than this
 * {@code {eventId}} pattern, so Spring routes {@code search} there and
 * every other single segment here.</p>
 *
 * <p>Conditional on {@code cortex.gateway.get-log-by-id.enabled=true};
 * when disabled the bean is not registered and the path returns 404. The
 * {@link RateLimitFeature @RateLimitFeature} sub-bucket is shared with
 * the GraphQL surface (same annotation members) so both resolve to one
 * Bucket4j bucket per JWT subject (P9.0a parity).</p>
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.get-log-by-id", name = "enabled", havingValue = "true")
public class GetLogByIdController {

    /** Shared get-by-id logic (also used by the GraphQL resolver). */
    private final GetLogByIdService service;

    /**
     * Fetches one log row by id.
     *
     * @param eventId  the event id from the path
     * @param tenantId resolved tenant id from the required
     *                 {@code X-Tenant-Id} header
     * @return HTTP 200 with the {@link LogEntry} body
     * @throws ApplicationException carrying {@link ErrorCodes#VALIDATION_FAILED}
     *         when the tenant header is missing/blank
     */
    @GetMapping(ApiPaths.LOGS_BY_ID)
    @PreAuthorize("isAuthenticated()")
    @RateLimitFeature(
            name = "get-log-by-id",
            capacity = "${cortex.gateway.get-log-by-id.sub-bucket-capacity:60}",
            refill = "${cortex.gateway.get-log-by-id.sub-bucket-refill-period:PT1M}",
            errorCode = "GET_LOG_BY_ID_RATE_LIMITED",
            keyPrefix = "${cortex.gateway.get-log-by-id.sub-bucket-key-prefix:cortex:rl:getlog:}")
    public ResponseEntity<LogEntry> getLogById(
            @PathVariable("eventId") final String eventId,
            @RequestHeader(value = HeaderNames.X_TENANT_ID, required = false) final String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id header is required");
        }
        return ResponseEntity.ok(this.service.getLogById(eventId, tenantId));
    }
}
