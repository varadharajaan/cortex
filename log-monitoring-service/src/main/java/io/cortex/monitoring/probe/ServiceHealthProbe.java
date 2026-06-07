package io.cortex.monitoring.probe;

/**
 * Service Provider Interface for probing the health of a single
 * cortex service instance from the CORTEX log-monitoring-service
 * (P8.0 / ADR-0044 D1).
 *
 * <p>Implementations decide whether + how to probe the per-instance
 * health: the default {@link NoopServiceHealthProbe} returns
 * {@link HealthSnapshot#noop(String)} so the P8.0 scaffold runs
 * end-to-end without any outbound HTTP. P8.1+ will land the real
 * Eureka-discovery REST adapter gated by
 * {@code cortex.monitoring.probe.backend=eureka-actuator}.</p>
 *
 * <p>Selection at runtime is driven by the {@code cortex.monitoring
 * .probe.backend} property + {@code @ConditionalOnProperty} on each
 * implementation. Only one probe bean is active in a given
 * profile.</p>
 *
 * <p>Implementations MUST be thread-safe. The probe surface will be
 * called from REST controllers (P8.1 aggregated-health endpoint) +
 * scheduled SLO evaluators (P8.2) concurrently.</p>
 *
 * <p>Implementations MUST NOT throw on transient downstream
 * failures (e.g. target service 5xx / connect-refused / read-
 * timeout). Returning a {@link HealthSnapshot} with
 * {@code outcome=transient_failure} or {@code outcome=unreachable}
 * ticks the failed-outcome counter and lets the caller carry on;
 * the contract is symmetric with the P6 {@code RemediationDispatcher}
 * SPI per ADR-0032 D6 + the P7 {@code QuickwitIndexAdmin} SPI per
 * ADR-0038 D6.</p>
 */
public interface ServiceHealthProbe {

    /**
     * Stable backend identifier used by {@link HealthSnapshot} +
     * {@link io.cortex.monitoring.metrics.MonitoringMetrics}
     * bootstrap to publish the per-backend outcome series before
     * the first probe call.
     *
     * @return the backend id; one of the
     *         {@link HealthSnapshot}{@code .BACKEND_*} constants
     *         ({@code noop}, {@code eureka-actuator}); never
     *         {@code null}, never blank
     */
    String backendId();

    /**
     * Probe the health of the cortex service instance identified by
     * the supplied {@link ProbeRequest}.
     *
     * <p>Implementations resolve the instance address (via the
     * Eureka discovery client in P8.1+), issue the request (HTTP GET
     * to {@code /actuator/health} in P8.1+), classify the response
     * into one of the {@link HealthSnapshot}{@code .OUTCOME_*}
     * constants, and return the verdict. Implementations MUST NOT
     * throw on transient downstream failures.</p>
     *
     * @param request the probe request; never {@code null}
     * @return the verdict; never {@code null}
     */
    HealthSnapshot probe(ProbeRequest request);
}
