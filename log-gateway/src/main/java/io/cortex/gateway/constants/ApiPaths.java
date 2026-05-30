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

    /**
     * Private constructor; constants holder.
     *
     * @throws AssertionError always, to enforce that this class is never instantiated
     */
    private ApiPaths() {
        throw new AssertionError("ApiPaths is a constants holder");
    }
}
