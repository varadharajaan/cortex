package io.cortex.ingest.constants;

/**
 * HTTP header names used by log-ingest-service inbound + outbound
 * exchanges.
 *
 * <p>Centralised so the controller, filters, tests, Postman, and
 * smoke scripts all reference the same literals (rule A6.5).</p>
 */
public final class HeaderNames {

    /**
     * Per-request correlation header set by log-gateway's
     * {@code CorrelationIdFilter} and propagated downstream. Read by
     * the ingest service's own {@code CorrelationIdFilter} into
     * {@link LogFields#TRACE_ID} for every log line (rule 17.5, A8.2).
     */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /** Correlation id propagated from log-gateway and any upstream caller. */
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** Tenant identifier header (fallback for service-to-service callers
     *  that have not minted a JWT yet). JWT claim {@code tenant_id} takes
     *  precedence when both are present (D5). */
    public static final String TENANT_ID = "X-Tenant-Id";

    /** Idempotency key for batch dedupe (used in P4.2). */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** Service-to-service JWT carried in the s2s mTLS scaffold (O8). */
    public static final String SERVICE_JWT = "X-Cortex-Service-JWT";

    /** Utility holder; not intended to be instantiated. */
    private HeaderNames() {
        // utility holder; no instances
    }
}
