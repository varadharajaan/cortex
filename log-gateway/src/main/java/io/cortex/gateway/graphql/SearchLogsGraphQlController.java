package io.cortex.gateway.graphql;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.GraphQlContextKeys;
import io.cortex.gateway.dto.request.LogSearchRequest;
import io.cortex.gateway.dto.response.LogSearchResult;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.SearchLogsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL resolver for the {@code searchLogs} root query (P9.1b /
 * ADR-0004 / ADR-0049).
 *
 * <p>Mirrors the REST endpoint {@link ApiPaths#LOGS_SEARCH} by
 * delegating to the same {@link SearchLogsService} implementation, so
 * the two surfaces share validation and downstream-failure mapping; the
 * only divergence is the transport layer. The result is returned
 * payload-identical to the REST surface (the {@code hits} list is
 * exposed through the {@code JSON} scalar so both surfaces carry the
 * same opaque Quickwit documents).</p>
 *
 * <p>The resolved tenant is read from the GraphQL execution context
 * (key {@link GraphQlContextKeys#TENANT_ID}), which
 * {@link io.cortex.gateway.interceptor.TenantHeaderGraphQlInterceptor}
 * populates from the {@code X-Tenant-Id} HTTP header. This keeps tenant
 * resolution identical to the REST surface (which reads the same header)
 * and prevents a GraphQL {@code input} field from spoofing another
 * tenant.</p>
 *
 * <p>Conditional on {@code cortex.gateway.search-logs.enabled=true}; when
 * disabled the resolver bean is not registered and the schema-validation
 * layer rejects {@code searchLogs} queries. The
 * {@link RateLimitFeature @RateLimitFeature} annotation members are
 * intentionally identical to the REST controller so both surfaces share
 * a single Bucket4j sub-bucket per JWT subject (P9.0a parity); the
 * {@code RateLimitGraphQlInterceptor} enforces it before this resolver
 * runs and the {@code RateLimitProblemExceptionResolver} (P9.0b) maps an
 * over-limit rejection to an RFC 7807 429.</p>
 */
@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.search-logs", name = "enabled", havingValue = "true")
public class SearchLogsGraphQlController {

    /** Shared search logic (also used by the REST controller). */
    private final SearchLogsService service;

    /**
     * Resolves the {@code searchLogs} query.
     *
     * @param input    validated query inputs bound from the
     *                 {@code LogSearchInput} GraphQL input type
     * @param tenantId resolved tenant id lifted from the
     *                 {@code X-Tenant-Id} header into the execution
     *                 context by the tenant interceptor; may be absent
     *                 if the header was not supplied
     * @return the search result, payload-identical to the REST surface
     * @throws ApplicationException carrying {@link ErrorCodes#VALIDATION_FAILED}
     *         when no tenant is present in the context
     */
    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    @RateLimitFeature(
            name = "search-logs",
            capacity = "${cortex.gateway.search-logs.sub-bucket-capacity:30}",
            refill = "${cortex.gateway.search-logs.sub-bucket-refill-period:PT1M}",
            errorCode = "SEARCH_LOGS_RATE_LIMITED",
            keyPrefix = "${cortex.gateway.search-logs.sub-bucket-key-prefix:cortex:rl:search:}")
    public LogSearchResult searchLogs(
            @Argument @Valid final LogSearchRequest input,
            @ContextValue(name = GraphQlContextKeys.TENANT_ID, required = false) final String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id header is required");
        }
        return this.service.search(input, tenantId);
    }
}
