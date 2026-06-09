package io.cortex.ingest.constants;

/**
 * REST API path constants for log-ingest-service.
 *
 * <p>Centralised here so controllers, tests, Postman, and smoke
 * scripts all reference the same URI literals (rule A6.5).</p>
 */
public final class ApiPaths {

    /** Base path for all v1 ingest resources. */
    public static final String INGEST_V1 = "/api/v1/ingest";

    /** Batch-ingest endpoint per D6 (ratified 2026-05-31). */
    public static final String INGEST_BATCH = INGEST_V1 + "/batch";

    /** Base path for the tenant-scoped log read surface (P9.2a). */
    public static final String LOGS_V1 = "/api/v1/logs";

    /**
     * Get-log-by-id endpoint (P9.2a / ADR-0022 Amendment 1). Backs the
     * gateway {@code getLogById} query (P9.2b); the gateway forwards
     * the resolved {@code X-Tenant-Id} and the {@code eventId} path
     * variable to this surface via {@code lb://log-ingest-service}.
     */
    public static final String LOGS_BY_ID = LOGS_V1 + "/{eventId}";

    /** Actuator base path (matches Spring Boot default). */
    public static final String ACTUATOR = "/actuator";

    /** Utility holder; not intended to be instantiated. */
    private ApiPaths() {
        // utility holder; no instances
    }
}
