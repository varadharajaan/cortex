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
