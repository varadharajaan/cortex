package io.cortex.monitoring.metrics;

import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.ServiceHealthProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Micrometer counter published by the log-monitoring-service
 * (P8.0 / Part 17 / ADR-0044 D3).
 *
 * <p>One counter establishes the stable metric surface that
 * Grafana dashboards subscribe to from P8.0 onwards:
 * {@code cortex.monitoring.probe_total{backend, outcome,
 * service_id}}. Bootstrap-registered at construct-time with all-
 * three placeholder values per LD106 + LD112 so the
 * {@code /actuator/prometheus} scrape sees the counter family
 * before the first probe call ticks.</p>
 *
 * <p>OCP-flipped bootstrap loop (mirror P6.0a / ADR-0036 +
 * P7.0 / ADR-0038): iterates over the list of
 * {@link ServiceHealthProbe} beans active in the current profile
 * (Spring injects an autowired {@code List<T>} bound by the
 * conditionals on each probe impl) and bootstraps the failable
 * outcome series for each backend's
 * {@link ServiceHealthProbe#backendId()}. Adding a new probe
 * backend therefore requires zero edits here -- the Open/Closed
 * Principle is honoured at the bootstrap boundary as well as at
 * the SPI boundary.</p>
 *
 * <p>Tag-key allowlist enforced per Part 17 rule: only
 * {@code backend}, {@code outcome}, and {@code service_id} are
 * emitted on this counter. Free-form values are bounded by the
 * {@link HealthSnapshot} constants
 * ({@code BACKEND_NOOP}, {@code OUTCOME_NOOP}, ...).</p>
 */
@Component
@RequiredArgsConstructor
public class MonitoringMetrics {

    /**
     * The probe counter metric name (kept public so tests can
     * reference it).
     */
    public static final String METRIC_PROBE_TOTAL =
            "cortex.monitoring.probe_total";

    /** Placeholder tag value used for the bootstrap-registration counter (LD106). */
    public static final String UNKNOWN = "unknown";

    private static final String TAG_BACKEND = "backend";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_SERVICE = "service_id";

    private static final String COUNTER_DESCRIPTION =
            "Service health probes handled by ServiceHealthProbe (P8.0 / ADR-0044)";

    private final MeterRegistry registry;
    private final List<ServiceHealthProbe> probes;

    /**
     * Bootstrap the counter family per LD106 + LD112 so the
     * {@code /actuator/prometheus} scrape sees a stable surface
     * even before the first probe call ticks.
     *
     * <p>Loops over every active {@link ServiceHealthProbe} bean
     * and bootstraps the failable outcome series for its backend
     * id. The all-{@code unknown} placeholder series is always
     * registered so the counter family is visible even when the
     * probe list is empty (test fixtures).</p>
     */
    @PostConstruct
    void bootstrapMeters() {
        bootstrap(UNKNOWN, UNKNOWN);
        for (final ServiceHealthProbe probe : this.probes) {
            final String backend = probe.backendId();
            bootstrap(backend, HealthSnapshot.OUTCOME_HEALTHY);
            bootstrap(backend, HealthSnapshot.OUTCOME_DEGRADED);
            bootstrap(backend, HealthSnapshot.OUTCOME_UNHEALTHY);
            bootstrap(backend, HealthSnapshot.OUTCOME_UNREACHABLE);
            bootstrap(backend, HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
            bootstrap(backend, HealthSnapshot.OUTCOME_PERMANENT_FAILURE);
        }
    }

    /**
     * Increment the probe counter for the supplied tag triple.
     * Counter series are lazy-registered by Micrometer on first
     * call; the bootstrap series with all-unknown tags remains so
     * dashboards never flatline.
     *
     * @param backend   one of the {@code HealthSnapshot.BACKEND_*}
     *                  constants (e.g. {@code noop} in P8.0;
     *                  {@code eureka-actuator} in P8.1+)
     * @param outcome   one of the {@code HealthSnapshot.OUTCOME_*}
     *                  constants ({@code noop}, {@code healthy},
     *                  {@code degraded}, {@code unhealthy},
     *                  {@code unreachable},
     *                  {@code transient_failure},
     *                  {@code permanent_failure})
     * @param serviceId Eureka service id from the
     *                  {@link io.cortex.monitoring.probe.ProbeRequest}
     */
    public void incProbe(final String backend, final String outcome,
                         final String serviceId) {
        Counter.builder(METRIC_PROBE_TOTAL)
                .description(COUNTER_DESCRIPTION)
                .tag(TAG_BACKEND, coerce(backend))
                .tag(TAG_OUTCOME, coerce(outcome))
                .tag(TAG_SERVICE, coerce(serviceId))
                .register(this.registry)
                .increment();
    }

    /**
     * Registers a zero-valued counter for the {@code backend} /
     * {@code outcome} pair with a placeholder service tag so the
     * series is visible to {@code /actuator/prometheus} before any
     * probe call has incremented it.
     */
    private void bootstrap(final String backend, final String outcome) {
        Counter.builder(METRIC_PROBE_TOTAL)
                .description(COUNTER_DESCRIPTION)
                .tag(TAG_BACKEND, coerce(backend))
                .tag(TAG_OUTCOME, coerce(outcome))
                .tag(TAG_SERVICE, UNKNOWN)
                .register(this.registry);
    }

    /**
     * Coerce a null or blank tag value to the
     * {@link #UNKNOWN} placeholder so Micrometer never sees a null
     * tag value (which would NPE at counter registration).
     */
    private static String coerce(final String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }
}
