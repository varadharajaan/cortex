package io.cortex.monitoring.constants;

/**
 * Shared HTTP constants used by every
 * {@link io.cortex.monitoring.probe.ServiceHealthProbe} adapter
 * (P8.1 / ADR-0045).
 *
 * <p>Centralised here per Rule A7 so the magic numbers {@code 429}
 * (Too Many Requests) and {@code 500} (the {@code 5xx} server-error
 * floor) live in exactly one place rather than scattered across
 * adapters and tests. Symmetric to
 * {@code io.cortex.indexer.constants.IndexerHttp} introduced in
 * P7.1 (ADR-0039 D3).</p>
 */
public final class MonitoringHttp {

    /**
     * HTTP {@code 429} (Too Many Requests). Treated as a
     * {@code transient_failure} outcome by every probe adapter so
     * the rate-limited call can be retried by the caller (e.g.
     * the future P8.2 SLO sweeper).
     */
    public static final int TOO_MANY_REQUESTS = 429;

    /**
     * HTTP {@code 5xx} server-error floor (inclusive). Any status
     * {@code >= 500} maps to a {@code transient_failure} outcome
     * per ADR-0045 D3.
     */
    public static final int SERVER_ERROR_FLOOR = 500;

    /**
     * HTTP {@code 404} (Not Found). For the
     * {@link io.cortex.monitoring.probe.eureka.EurekaActuatorHealthProbe}
     * a 404 on {@code /actuator/health} means the target service
     * has not exposed the endpoint and is treated as
     * {@code permanent_failure eureka-actuator:4xx:404} so the
     * operator gets a non-retriable alert (config error, not
     * downstream outage).
     */
    public static final int NOT_FOUND = 404;

    private MonitoringHttp() {
        throw new UnsupportedOperationException(
                "constants holder; do not instantiate");
    }
}
