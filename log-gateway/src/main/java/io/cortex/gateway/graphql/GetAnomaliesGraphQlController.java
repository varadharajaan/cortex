package io.cortex.gateway.graphql;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.GraphQlContextKeys;
import io.cortex.gateway.dto.response.Anomaly;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.GetAnomaliesService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/**
 * GraphQL resolver for the {@code getAnomalies} root query (P9.3b /
 * ADR-0004 / ADR-0049).
 *
 * <p>Mirrors the REST endpoint {@code GET /api/v1/anomalies} by
 * delegating to the same {@link GetAnomaliesService} implementation, so
 * the two surfaces share validation + downstream-failure mapping; the
 * only divergence is the transport layer. The result is returned
 * payload-identical to the REST surface.</p>
 *
 * <p>The resolved tenant is read from the GraphQL execution context
 * (key {@link GraphQlContextKeys#TENANT_ID}), which the P9.1b
 * {@link io.cortex.gateway.interceptor.TenantHeaderGraphQlInterceptor}
 * populates from the {@code X-Tenant-Id} HTTP header, keeping tenant
 * resolution identical to the REST surface and preventing an argument
 * from spoofing another tenant.</p>
 *
 * <p>Conditional on {@code cortex.gateway.get-anomalies.enabled=true}.
 * The {@link RateLimitFeature @RateLimitFeature} members are identical to
 * the REST controller so both surfaces share one Bucket4j sub-bucket per
 * JWT subject (P9.0a parity); the over-limit rejection becomes an RFC
 * 7807 429 via the P9.0b resolver.</p>
 */
@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.get-anomalies", name = "enabled", havingValue = "true")
public class GetAnomaliesGraphQlController {

    /** Shared anomalies-read logic (also used by the REST controller). */
    private final GetAnomaliesService service;

    /**
     * Resolves the {@code getAnomalies} query.
     *
     * @param since    optional inclusive ISO-8601 lower bound argument
     * @param until    optional inclusive ISO-8601 upper bound argument
     * @param limit    optional row limit argument; strictly positive when present
     * @param tenantId resolved tenant id lifted from the
     *                 {@code X-Tenant-Id} header into the execution
     *                 context by the tenant interceptor; may be absent if
     *                 the header was not supplied
     * @return the anomaly list, payload-identical to the REST surface
     * @throws ApplicationException carrying {@link ErrorCodes#VALIDATION_FAILED}
     *         when no tenant is present in the context
     */
    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    @RateLimitFeature(
            name = "get-anomalies",
            capacity = "${cortex.gateway.get-anomalies.sub-bucket-capacity:60}",
            refill = "${cortex.gateway.get-anomalies.sub-bucket-refill-period:PT1M}",
            errorCode = "GET_ANOMALIES_RATE_LIMITED",
            keyPrefix = "${cortex.gateway.get-anomalies.sub-bucket-key-prefix:cortex:rl:anom:}")
    public List<Anomaly> getAnomalies(
            @Argument final String since,
            @Argument final String until,
            @Argument final Integer limit,
            @ContextValue(name = GraphQlContextKeys.TENANT_ID, required = false) final String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id header is required");
        }
        return this.service.getAnomalies(tenantId, since, until, limit);
    }
}
