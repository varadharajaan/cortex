package io.cortex.gateway.constants;

/**
 * Canonical HTTP header name constants used by the gateway.
 *
 * <p>All filters and controllers reference these constants instead of
 * raw strings (rule A7.1, A8.2).</p>
 */
public final class HeaderNames {

    /** Inbound and outbound correlation header (rule A8.2). */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /** Inbound tenant identifier (rule 17.5, populated when B6 active). */
    public static final String X_TENANT_ID = "X-Tenant-Id";

    /** Inbound API key for ingest endpoints. */
    public static final String X_API_KEY = "X-Api-Key";

    /** Outbound rate-limit capacity (B5.2). */
    public static final String X_RATELIMIT_LIMIT = "X-RateLimit-Limit";

    /** Outbound tokens remaining in the current refill window (B5.2). */
    public static final String X_RATELIMIT_REMAINING = "X-RateLimit-Remaining";

    /** Outbound seconds until the bucket is fully refilled (B5.2). */
    public static final String X_RATELIMIT_RESET = "X-RateLimit-Reset";

    /** RFC 7231 section 7.1.3 retry-after header, mirrors {@link #X_RATELIMIT_RESET}. */
    public static final String RETRY_AFTER = "Retry-After";

    /**
     * Private constructor; constants holder.
     *
     * @throws AssertionError always, to enforce that this class is never instantiated
     */
    private HeaderNames() {
        throw new AssertionError("HeaderNames is a constants holder");
    }
}
