package io.cortex.monitoring.probe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link ServiceHealthProbe} implementation that returns
 * {@link HealthSnapshot#noop(String)} for every call (P8.0 /
 * ADR-0044 D1).
 *
 * <p>The scaffold runs end-to-end without any outbound HTTP:
 * callers invoke the SPI, this no-op returns the
 * {@code backend=noop, outcome=noop} verdict, and the bound
 * {@link io.cortex.monitoring.metrics.MonitoringMetrics} counter
 * ticks with bounded tag values. P8.1+ swaps the bean
 * implementation behind
 * {@code cortex.monitoring.probe.backend=eureka-actuator}.</p>
 *
 * <p>Gated by {@code cortex.monitoring.probe.backend=noop}
 * ({@code matchIfMissing=true}), so it's the default in every
 * profile until P8.1 introduces the real Eureka-discovery REST
 * probe.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.monitoring.probe",
        name = "backend",
        havingValue = "noop",
        matchIfMissing = true)
public final class NoopServiceHealthProbe implements ServiceHealthProbe {

    /** Reason stamped on every noop verdict from this scaffold backend. */
    private static final String NOOP_REASON =
            "noop probe (P8.0 scaffold); real Eureka-discovery probe lands in P8.1+";

    @Override
    public String backendId() {
        return HealthSnapshot.BACKEND_NOOP;
    }

    @Override
    public HealthSnapshot probe(final ProbeRequest request) {
        return HealthSnapshot.noop(NOOP_REASON);
    }
}
