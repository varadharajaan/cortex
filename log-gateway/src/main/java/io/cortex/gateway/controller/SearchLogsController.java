package io.cortex.gateway.controller;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.dto.request.LogSearchRequest;
import io.cortex.gateway.dto.response.LogSearchResult;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.SearchLogsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped log search endpoint (P9.1b / ADR-0004 / ADR-0049).
 *
 * <p>{@code GET /api/v1/logs/search?index=&q=&maxHits=} delegates to the
 * shared {@link SearchLogsService}, which forwards the query to
 * log-indexer-service (P9.1a). The resolved tenant is read from the
 * {@code X-Tenant-Id} header (ADR-0009) and forwarded downstream as the
 * single source of truth for tenant scoping, so a query parameter can
 * never spoof another tenant.</p>
 *
 * <p>This is a gateway-owned endpoint, not a proxy route. It sits under
 * the {@code /api/v1/logs/**} prefix that
 * {@link io.cortex.gateway.config.GatewayRoutesConfig} proxies to
 * log-ingest-service; that proxy's predicate explicitly excludes this
 * exact path so the request reaches this controller rather than being
 * forwarded to log-ingest-service (ADR-0049 Amendment 3 D-A3.5 -- the
 * gateway {@code RouterFunctionMapping} would otherwise win over the
 * annotated handler in this servlet wiring).</p>
 *
 * <p>Conditional on {@code cortex.gateway.search-logs.enabled=true}; when
 * disabled the bean is not registered and the path returns 404. The
 * {@link RateLimitFeature @RateLimitFeature} sub-bucket is shared with
 * the GraphQL surface (same annotation members) so both resolve to one
 * Bucket4j bucket per JWT subject (P9.0a parity).</p>
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.search-logs", name = "enabled", havingValue = "true")
public class SearchLogsController {

    /** Shared search logic (also used by the GraphQL resolver). */
    private final SearchLogsService service;

    /**
     * Executes a tenant-scoped search.
     *
     * @param tenantId resolved tenant id from the required
     *                 {@code X-Tenant-Id} header
     * @param index    Quickwit index id ({@code index} query parameter)
     * @param query    Quickwit query string ({@code q} query parameter)
     * @param maxHits  optional hit ceiling ({@code maxHits} query
     *                 parameter); strictly positive when present
     * @return HTTP 200 with the {@link LogSearchResult} body
     * @throws ApplicationException carrying {@link ErrorCodes#VALIDATION_FAILED}
     *         when the tenant header or required parameters are missing
     */
    @GetMapping(ApiPaths.LOGS_SEARCH)
    @PreAuthorize("isAuthenticated()")
    @RateLimitFeature(
            name = "search-logs",
            capacity = "${cortex.gateway.search-logs.sub-bucket-capacity:30}",
            refill = "${cortex.gateway.search-logs.sub-bucket-refill-period:PT1M}",
            errorCode = "SEARCH_LOGS_RATE_LIMITED",
            keyPrefix = "${cortex.gateway.search-logs.sub-bucket-key-prefix:cortex:rl:search:}")
    public ResponseEntity<LogSearchResult> searchLogs(
            @RequestHeader(value = HeaderNames.X_TENANT_ID, required = false) final String tenantId,
            @RequestParam(value = "index", required = false) final String index,
            @RequestParam(value = "q", required = false) final String query,
            @RequestParam(value = "maxHits", required = false) final Integer maxHits) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id header is required");
        }
        if (index == null || index.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "index query parameter is required");
        }
        if (query == null || query.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "q query parameter is required");
        }
        if (maxHits != null && maxHits <= 0) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "maxHits must be strictly positive");
        }
        final LogSearchRequest request = new LogSearchRequest(index, query, maxHits);
        return ResponseEntity.ok(this.service.search(request, tenantId));
    }
}
