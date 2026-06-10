package io.cortex.monitoring.slo;

import io.cortex.monitoring.metrics.MonitoringMetrics;
import io.cortex.monitoring.probe.HealthSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link SloBudgetEngine} that derives budget remaining + burn
 * rate from the in-process {@code MeterRegistry} snapshot of
 * {@code cortex.monitoring.probe_total} counters (P8.2 /
 * ADR-0046 D3).
 *
 * <p>For a given {@link SloDefinition} the engine sums all
 * counter series with matching {@code service_id} tag, classifies
 * them as "successes" or "failures" by their {@code outcome} tag,
 * and computes:</p>
 *
 * <pre>
 *   errorRate            = failures / (successes + failures)
 *   errorBudget          = 1 - targetSuccessRatio
 *   budgetRemainingRatio = (errorBudget - errorRate) / errorBudget
 *   burnRate             = errorRate / errorBudget
 * </pre>
 *
 * <p>Both values are clamped to {@code [-1.0, 1.0]} and
 * {@code [0.0, +Infinity)} respectively so the gauges never emit
 * {@code NaN} (Micrometer drops {@code NaN} samples silently --
 * dashboards then show a flatline that operators misread as "all
 * clear", which is the opposite of the truth).</p>
 *
 * <p>Honest about its evaluation horizon: this engine reads the
 * since-boot counter total, NOT a true rate over
 * {@link SloDefinition#window()}. Operators get the "true window
 * rate" by switching the dashboard query to
 * {@code rate(cortex_monitoring_probe_total[1h])} or by
 * recycling the service. ADR-0046 D5 documents this trade-off
 * (the alternative -- maintaining a sliding window in-process --
 * was rejected to keep the engine boring + observable; Prometheus
 * is the source of truth for window-based queries).</p>
 *
 * <p>Gated by {@code cortex.monitoring.slo.backend=micrometer-derivation}.
 * MUST NOT throw -- every error path funnels into
 * {@link SloSnapshot#transientFailure(String, SloDefinition, String)}
 * or {@link SloSnapshot#permanentFailure(String, SloDefinition, String)}.</p>
 */
@Component
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'micrometer-derivation'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
public final class MicrometerSloBudgetEngine implements SloBudgetEngine {

    /** Tag key used by {@link MonitoringMetrics} on the probe counter. */
    private static final String TAG_SERVICE_ID = "service_id";

    /** Tag key used by {@link MonitoringMetrics} on the probe counter. */
    private static final String TAG_OUTCOME = "outcome";

    /**
     * Outcomes that count as a "success" sample for the SLO
     * derivation (the probe ran AND the target reported usable
     * health).
     */
    private static final Set<String> SUCCESS_OUTCOMES = Set.of(
            HealthSnapshot.OUTCOME_HEALTHY,
            HealthSnapshot.OUTCOME_DEGRADED);

    /**
     * Outcomes that count as a "failure" sample for the SLO
     * derivation (any non-healthy verdict the operator should be
     * paged on).
     */
    private static final Set<String> FAILURE_OUTCOMES = Set.of(
            HealthSnapshot.OUTCOME_UNHEALTHY,
            HealthSnapshot.OUTCOME_UNREACHABLE,
            HealthSnapshot.OUTCOME_TRANSIENT_FAILURE,
            HealthSnapshot.OUTCOME_PERMANENT_FAILURE);

    private final MeterRegistry registry;

    /**
     * Sole ctor. Spring injects the singleton
     * {@link MeterRegistry} bean exposed by Spring Boot's
     * actuator autoconfig.
     *
     * @param meterRegistry the registry to read counters from;
     *                      never null
     */
    public MicrometerSloBudgetEngine(final MeterRegistry meterRegistry) {
        this.registry = meterRegistry;
    }

    @Override
    public String backendId() {
        return SloSnapshot.BACKEND_MICROMETER_DERIVATION;
    }

    @Override
    public boolean supports(final SloDefinition def) {
        return def != null
                && def.counterFamily() == null
                && def.timer() == null
                && def.promQl() == null
                && def.composite() == null
                && def.otel() == null;
    }

    @Override
    public SloSnapshot evaluate(final SloDefinition def) {
        try {
            final Search base = this.registry
                    .find(MonitoringMetrics.METRIC_PROBE_TOTAL)
                    .tag(TAG_SERVICE_ID, def.serviceId());

            double successes = 0.0d;
            double failures = 0.0d;
            for (final Counter c : base.counters()) {
                final String outcome = c.getId().getTag(TAG_OUTCOME);
                final double count = c.count();
                if (SUCCESS_OUTCOMES.contains(outcome)) {
                    successes += count;
                } else if (FAILURE_OUTCOMES.contains(outcome)) {
                    failures += count;
                }
            }

            final double total = successes + failures;
            if (total <= 0.0d) {
                return SloSnapshot.unknown(backendId(), def,
                        backendId() + ":no-data");
            }

            final double errorBudget = 1.0d - def.targetSuccessRatio();
            // Guarded by SloDefinition compact ctor: target is in (0,1)
            // so errorBudget is in (0,1) -- no divide-by-zero possible.
            final double errorRate = failures / total;
            final double burnRate = errorRate / errorBudget;
            final double budgetRemaining =
                    SloBudgetMath.clamp((errorBudget - errorRate)
                            / errorBudget);
            return SloSnapshot.banded(backendId(), def,
                    budgetRemaining, burnRate);
        } catch (final RuntimeException ex) {
            return SloSnapshot.transientFailure(backendId(), def,
                    backendId() + ":exception:" + ex.getClass().getSimpleName());
        }
    }

}
