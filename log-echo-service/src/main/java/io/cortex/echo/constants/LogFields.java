package io.cortex.echo.constants;

/**
 * Canonical MDC field name constants used in structured log output.
 *
 * <p>Rule 17.5 mandates a {@code traceId} key on every log line. MDC
 * keys are always referenced from this class, never as raw strings at
 * call sites (rule A8.3). {@code tenantId} and {@code userId} are
 * declared here so the logback-spring.xml template can include them
 * unconditionally even though echo never populates them itself; the
 * gateway sets them on the inbound JWT-authenticated requests it
 * proxies, but echo runs without the gateway's security stack.</p>
 */
public final class LogFields {

    /** Correlation / trace identifier (rule 17.5). */
    public static final String TRACE_ID = "traceId";

    /** Tenant identifier; reserved for future use (not populated by echo). */
    public static final String TENANT_ID = "tenantId";

    /** Authenticated subject identifier; reserved for future use (not populated by echo). */
    public static final String USER_ID = "userId";

    /**
     * Private constructor; constants holder.
     *
     * @throws AssertionError always, to enforce that this class is never instantiated
     */
    private LogFields() {
        throw new AssertionError("LogFields is a constants holder");
    }
}
