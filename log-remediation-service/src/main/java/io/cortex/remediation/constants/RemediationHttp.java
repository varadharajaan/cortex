package io.cortex.remediation.constants;

/**
 * Shared HTTP constants used by every
 * {@link io.cortex.remediation.dispatch.RemediationDispatcher}
 * adapter (P6.0a / ADR-0036).
 *
 * <p>Centralised here per Rule A7 so the magic number {@code 429}
 * lives in exactly one place across the Slack, PagerDuty, and Jira
 * adapters rather than three.</p>
 */
public final class RemediationHttp {

    /**
     * HTTP 429 (Too Many Requests). Treated as a transient outcome by every
     * adapter so the rate-limited request can be retried by the operator.
     */
    public static final int TOO_MANY_REQUESTS = 429;

    private RemediationHttp() {
        throw new UnsupportedOperationException("constants holder; do not instantiate");
    }
}
