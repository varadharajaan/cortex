package io.cortex.echo.constants;

/**
 * HTTP header names used by log-echo-service inbound + outbound
 * exchanges. Echo is a throwaway downstream stub (ADR-0016) so the
 * surface is intentionally tiny.
 *
 * <p>Centralised so the filter, controller, tests, and smoke scripts
 * all reference the same literals (rule A6.5, A7.1).</p>
 */
public final class HeaderNames {

    /**
     * Per-request correlation header set by log-gateway's
     * {@code CorrelationIdFilter} and propagated to this downstream
     * stub. Read by the echo service's own {@code CorrelationIdFilter}
     * into {@link LogFields#TRACE_ID} so every log line emitted while
     * handling the request carries the same id as the gateway hop
     * (rule 17.5, A8.2).
     */
    public static final String X_REQUEST_ID = "X-Request-Id";

    /**
     * Private constructor; constants holder.
     *
     * @throws AssertionError always, to enforce that this class is never instantiated
     */
    private HeaderNames() {
        throw new AssertionError("HeaderNames is a constants holder");
    }
}
