package io.cortex.gateway.graphql;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.GraphQlContextKeys;
import io.cortex.gateway.dto.response.LogEntry;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.GetLogByIdService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL resolver for the {@code getLogById} root query (P9.2b /
 * ADR-0004 / ADR-0049).
 *
 * <p>Mirrors the REST endpoint {@link ApiPaths#LOGS_BY_ID} by delegating
 * to the same {@link GetLogByIdService} implementation, so the two
 * surfaces share downstream-failure mapping; the only divergence is the
 * transport layer. The result is returned payload-identical to the REST
 * surface (the {@code labels} map is exposed through the {@code JSON}
 * scalar shared with P9.1b).</p>
 *
 * <p>The resolved tenant is read from the GraphQL execution context
 * (key {@link GraphQlContextKeys#TENANT_ID}), which the P9.1b
 * {@link io.cortex.gateway.interceptor.TenantHeaderGraphQlInterceptor}
 * populates from the {@code X-Tenant-Id} HTTP header, keeping tenant
 * resolution identical to the REST surface and preventing an argument
 * from spoofing another tenant.</p>
 *
 * <p>Conditional on {@code cortex.gateway.get-log-by-id.enabled=true}.
 * The {@link RateLimitFeature @RateLimitFeature} members are identical
 * to the REST controller so both surfaces share one Bucket4j sub-bucket
 * per JWT subject (P9.0a parity); the over-limit rejection becomes an
 * RFC 7807 429 via the P9.0b resolver.</p>
 */
@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.get-log-by-id", name = "enabled", havingValue = "true")
public class GetLogByIdGraphQlController {

    /** Shared get-by-id logic (also used by the REST controller). */
    private final GetLogByIdService service;

    /**
     * Resolves the {@code getLogById} query.
     *
     * @param id       the event id argument ({@code ID!})
     * @param tenantId resolved tenant id lifted from the
     *                 {@code X-Tenant-Id} header into the execution
     *                 context by the tenant interceptor; may be absent
     *                 if the header was not supplied
     * @return the log entry, payload-identical to the REST surface
     * @throws ApplicationException carrying {@link ErrorCodes#VALIDATION_FAILED}
     *         when no tenant is present in the context
     */
    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    @RateLimitFeature(
            name = "get-log-by-id",
            capacity = "${cortex.gateway.get-log-by-id.sub-bucket-capacity:60}",
            refill = "${cortex.gateway.get-log-by-id.sub-bucket-refill-period:PT1M}",
            errorCode = "GET_LOG_BY_ID_RATE_LIMITED",
            keyPrefix = "${cortex.gateway.get-log-by-id.sub-bucket-key-prefix:cortex:rl:getlog:}")
    public LogEntry getLogById(
            @Argument final String id,
            @ContextValue(name = GraphQlContextKeys.TENANT_ID, required = false) final String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id header is required");
        }
        return this.service.getLogById(id, tenantId);
    }
}
