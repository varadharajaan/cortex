package io.cortex.monitoring.metrics;

import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.ServiceHealthProbe;
import io.cortex.monitoring.slo.SloSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
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

    /**
     * The SLO budget-remaining gauge metric name (P8.2 / ADR-0046
     * D3). Tag keys per Part 17 allowlist:
     * {@code service_id, slo_name}.
     */
    public static final String METRIC_SLO_BUDGET_REMAINING =
            "cortex.monitoring.slo_budget_remaining";

    /**
     * The SLO burn-rate gauge metric name (P8.2 / ADR-0046 D3).
     * Tag keys per Part 17 allowlist: {@code service_id, slo_name}.
     */
    public static final String METRIC_SLO_BURN_RATE =
            "cortex.monitoring.slo_burn_rate";

    /** Placeholder tag value used for the bootstrap-registration counter (LD106). */
    public static final String UNKNOWN = "unknown";

    private static final String TAG_BACKEND = "backend";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_SERVICE = "service_id";
    private static final String TAG_SLO_NAME = "slo_name";

    private static final String COUNTER_DESCRIPTION =
            "Service health probes handled by ServiceHealthProbe (P8.0 / ADR-0044)";

    private static final String GAUGE_BUDGET_DESCRIPTION =
            "Fraction of SLO error budget remaining (P8.2 / ADR-0046);"
                    + " 1.0=full budget, 0.0=exhausted, negative=over-burned";

    private static final String GAUGE_BURN_DESCRIPTION =
            "Burn rate against the SLO target (P8.2 / ADR-0046);"
                    + " 0.0=no errors, 1.0=burning at target, >1.0 faster";

    private final MeterRegistry registry;
    private final List<ServiceHealthProbe> probes;

    /**
     * Lazy registry of per-{@code (service_id, slo_name)} gauges.
     * Each entry holds a single {@link AtomicReference} whose
     * value is the latest {@link SloSnapshot} for the key; the
     * registered {@link Gauge} reads through this reference so
     * subsequent {@code recordSlo} calls update both gauge values
     * atomically without re-registering.
     */
    private final ConcurrentMap<SloKey, AtomicReference<SloSnapshot>> sloCache =
            new ConcurrentHashMap<>();

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
     * Record the latest {@link SloSnapshot} for a
     * {@code (service_id, slo_name)} key, lazy-registering the
     * pair of gauges
     * ({@link #METRIC_SLO_BUDGET_REMAINING} +
     * {@link #METRIC_SLO_BURN_RATE}) on first contact and
     * updating the holder atomically on every subsequent call
     * (P8.2 / ADR-0046 D3).
     *
     * <p>Idempotent: repeated calls for the same key reuse the
     * existing gauges -- Micrometer registers each gauge exactly
     * once per (name, tagSet); we use an
     * {@link AtomicReference} holder so subsequent samples are
     * picked up automatically without re-registration.</p>
     *
     * @param snapshot the latest verdict for this key; non-null
     *                 (caller's responsibility -- the
     *                 {@link io.cortex.monitoring.slo.SloEvaluator}
     *                 ignores null snapshots upstream)
     */
    public void recordSlo(final SloSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        final String svc = coerce(snapshot.serviceId());
        final String name = coerce(snapshot.sloName());
        final SloKey key = new SloKey(svc, name);
        final AtomicReference<SloSnapshot> holder = this.sloCache
                .computeIfAbsent(key, k -> registerGaugePair(svc, name,
                        snapshot));
        holder.set(snapshot);
    }

    /**
     * Build the {@link AtomicReference} holder, register the
     * budget + burn gauges that read through it, and return the
     * holder for the cache. Called at most once per key (via
     * {@code computeIfAbsent}).
     */
    private AtomicReference<SloSnapshot> registerGaugePair(
            final String serviceId, final String sloName,
            final SloSnapshot seed) {
        final AtomicReference<SloSnapshot> holder =
                new AtomicReference<>(seed);
        Gauge.builder(METRIC_SLO_BUDGET_REMAINING, holder,
                        ref -> snapshotOrZero(ref).budgetRemainingRatio())
                .description(GAUGE_BUDGET_DESCRIPTION)
                .tag(TAG_SERVICE, serviceId)
                .tag(TAG_SLO_NAME, sloName)
                .register(this.registry);
        Gauge.builder(METRIC_SLO_BURN_RATE, holder,
                        ref -> snapshotOrZero(ref).burnRate())
                .description(GAUGE_BURN_DESCRIPTION)
                .tag(TAG_SERVICE, serviceId)
                .tag(TAG_SLO_NAME, sloName)
                .register(this.registry);
        return holder;
    }

    /**
     * Defensive read against the {@link AtomicReference} so the
     * gauge supplier never returns {@code null} (Micrometer drops
     * null samples silently, which would flatline the dashboard).
     */
    private static SloSnapshot snapshotOrZero(
            final AtomicReference<SloSnapshot> ref) {
        final SloSnapshot snap = ref.get();
        return snap == null
                ? new SloSnapshot(SloSnapshot.BACKEND_NOOP, "", "",
                        SloSnapshot.OUTCOME_UNKNOWN,
                        SloSnapshot.UNKNOWN_BUDGET_REMAINING,
                        SloSnapshot.UNKNOWN_BURN_RATE, "")
                : snap;
    }

    /**
     * Coerce a null or blank tag value to the
     * {@link #UNKNOWN} placeholder so Micrometer never sees a null
     * tag value (which would NPE at counter registration).
     */
    private static String coerce(final String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }

    /**
     * Composite key for the {@link #sloCache} keyed by
     * {@code (serviceId, sloName)} -- both already coerced to
     * the {@link #UNKNOWN} placeholder if blank, so equality
     * matches the underlying gauge tag pair exactly.
     */
    private record SloKey(String serviceId, String sloName) {
    }
}
