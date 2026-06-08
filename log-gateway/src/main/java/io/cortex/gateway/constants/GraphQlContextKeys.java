package io.cortex.gateway.constants;

/**
 * Keys under which request-scoped values are published into the
 * Spring for GraphQL execution context so resolvers can read them via
 * {@link org.springframework.graphql.data.method.annotation.ContextValue
 * &#64;ContextValue} (P9.1b / ADR-0049).
 *
 * <p>A {@code WebGraphQlInterceptor} lifts the value off the HTTP
 * request (which the resolver cannot see directly) into the GraphQL
 * context; the resolver then reads it by this key. Centralising the
 * key here keeps the producer (interceptor) and consumer (resolver) in
 * sync without the resolver depending on the interceptor (which the
 * ArchUnit layering rule forbids).</p>
 */
public final class GraphQlContextKeys {

    /**
     * Resolved tenant id, lifted from the {@code X-Tenant-Id} HTTP
     * header by
     * {@link io.cortex.gateway.interceptor.TenantHeaderGraphQlInterceptor}
     * and consumed by
     * {@link io.cortex.gateway.graphql.SearchLogsGraphQlController}.
     */
    public static final String TENANT_ID = "tenantId";

    /**
     * Private constructor; constants holder.
     *
     * @throws AssertionError always, to enforce that this class is never instantiated
     */
    private GraphQlContextKeys() {
        throw new AssertionError("GraphQlContextKeys is a constants holder");
    }
}
