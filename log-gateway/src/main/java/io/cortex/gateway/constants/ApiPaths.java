package io.cortex.gateway.constants;

/**
 * Canonical HTTP path constants for the CORTEX log-gateway public API.
 *
 * <p>All controllers reference these constants instead of hard-coded
 * strings (rule A7.1).</p>
 */
public final class ApiPaths {

    /** Base path for every gateway-owned REST endpoint. */
    public static final String API_V1 = "/api/v1";

    /** Service health endpoint (public). */
    public static final String HEALTH = API_V1 + "/health";

    /** Base path for authentication endpoints (public). */
    public static final String AUTH_BASE = API_V1 + "/auth";

    /** Login endpoint accepting {@code POST} with a {@code LoginRequest} body. */
    public static final String AUTH_LOGIN = AUTH_BASE + "/login";

    /** Refresh endpoint accepting {@code POST} with a {@code RefreshRequest} body. */
    public static final String AUTH_REFRESH = AUTH_BASE + "/refresh";

    /** Base path for natural-language query endpoints (B20.1, P3.3 / ADR-0018). */
    public static final String QUERY_BASE = API_V1 + "/query";

    /** NL-to-LogQL translation endpoint accepting {@code POST} with an {@code NlQueryRequest} body. */
    public static final String QUERY_NL = QUERY_BASE + "/nl";

    /**
     * Tenant-scoped log search endpoint accepting {@code GET} with
     * {@code index}, {@code q}, and optional {@code maxHits} query
     * parameters plus an {@code X-Tenant-Id} header (P9.1b / ADR-0004 /
     * ADR-0049).
     *
     * <p>This is a gateway-owned endpoint, not a proxy route. It sits
     * under the {@code /api/v1/logs/**} prefix that
     * {@link io.cortex.gateway.config.GatewayRoutesConfig} proxies to
     * log-ingest-service, so that proxy predicate explicitly excludes
     * this exact path (ADR-0049 Amendment 3 D-A3.5); the gateway router
     * function would otherwise win over the annotated controller.</p>
     */
    public static final String LOGS_SEARCH = API_V1 + "/logs/search";

    /**
     * Tenant-scoped get-log-by-id endpoint accepting {@code GET} with an
     * {@code eventId} path variable plus an {@code X-Tenant-Id} header
     * (P9.2b / ADR-0004 / ADR-0049).
     *
     * <p>Gateway-owned, not a proxy route. The {@code /api/v1/logs/**}
     * ingest proxy matches only {@code POST} (P9.1c / ADR-0049
     * Amendment 4), so this {@code GET} reaches the controller. The
     * literal {@link #LOGS_SEARCH} mapping is more specific than this
     * {@code {eventId}} pattern, so Spring routes {@code search} there
     * and every other single segment here.</p>
     */
    public static final String LOGS_BY_ID = API_V1 + "/logs/{eventId}";

    /**
     * Tenant-scoped anomalies-read endpoint accepting {@code GET} with
     * optional {@code since}, {@code until}, and {@code limit} query
     * parameters plus an {@code X-Tenant-Id} header (P9.3b / ADR-0004 /
     * ADR-0049).
     *
     * <p>Gateway-owned, not a proxy route -- the gateway does not proxy
     * {@code /api/v1/anomalies} to any service, so the request reaches
     * {@link io.cortex.gateway.controller.GetAnomaliesController}
     * directly. The resolved tenant is forwarded downstream to
     * log-remediation-service (P9.3a) as the {@code tenantId} query
     * parameter, the backer's single source of truth for tenant
     * scoping.</p>
     */
    public static final String ANOMALIES = API_V1 + "/anomalies";

    /**
     * Spring for GraphQL HTTP endpoint (P9.0 / ADR-0049).
     *
     * <p>Accepts {@code POST /graphql} with a standard GraphQL request
     * body ({@code {"query": "...", "variables": {...}}}). The path is
     * not under {@link #API_V1} because the GraphQL convention is a
     * single root endpoint and Spring for GraphQL's auto-config binds
     * to {@code /graphql} by default; aligning with the convention
     * keeps client tooling (graphql-codegen, Apollo, etc.) plug-and-
     * play. Authentication is enforced by {@code SecurityConfig}'s
     * {@code anyRequest().authenticated()} rule (mirrors REST).</p>
     */
    public static final String GRAPHQL = "/graphql";

    /**
     * Private constructor; constants holder.
     *
     * @throws AssertionError always, to enforce that this class is never instantiated
     */
    private ApiPaths() {
        throw new AssertionError("ApiPaths is a constants holder");
    }
}
