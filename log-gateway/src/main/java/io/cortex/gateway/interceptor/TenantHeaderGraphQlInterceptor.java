package io.cortex.gateway.interceptor;

import io.cortex.gateway.constants.GraphQlContextKeys;
import io.cortex.gateway.constants.HeaderNames;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Lifts the {@code X-Tenant-Id} HTTP header into the GraphQL execution
 * context so resolvers can read it via {@code @ContextValue}
 * (P9.1b / ADR-0049 / ADR-0009).
 *
 * <p>GraphQL resolvers do not see the underlying HTTP request, so the
 * gateway-resolved tenant header is surfaced through the execution
 * context under {@link GraphQlContextKeys#TENANT_ID}. This is the
 * GraphQL counterpart to the REST controller reading the same header
 * directly via {@code @RequestHeader}, keeping the two surfaces' tenant
 * resolution identical (the parity contract). The header itself stays
 * the single source of truth for tenant scoping (the indexer trusts
 * only {@code X-Tenant-Id}, ADR-0042 D3), so a GraphQL {@code input}
 * field can never spoof another tenant.</p>
 *
 * <p>Gated on {@code cortex.gateway.search-logs.enabled=true}; when the
 * search feature is disabled the interceptor is absent and adds no
 * per-request work.</p>
 */
@Component
@ConditionalOnProperty(prefix = "cortex.gateway.search-logs", name = "enabled", havingValue = "true")
public class TenantHeaderGraphQlInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(final WebGraphQlRequest request, final Chain chain) {
        final String tenantId = request.getHeaders().getFirst(HeaderNames.X_TENANT_ID);
        if (tenantId != null && !tenantId.isBlank()) {
            request.configureExecutionInput((executionInput, builder) ->
                    builder.graphQLContext(
                            contextBuilder -> contextBuilder.put(
                                    GraphQlContextKeys.TENANT_ID, tenantId)).build());
        }
        return chain.next(request);
    }
}
