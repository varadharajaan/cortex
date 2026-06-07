package io.cortex.monitoring.slo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link SloBudgetEngine} implementation that returns
 * {@link SloSnapshot#noop(SloDefinition)} for every evaluation
 * (P8.2 / ADR-0046 D1).
 *
 * <p>The scaffold runs end-to-end without any counter introspection:
 * the {@link SloEvaluator} scheduler still triggers, each call
 * here returns the noop verdict, and the
 * {@link io.cortex.monitoring.metrics.MonitoringMetrics} gauges
 * register at their {@code unknown} defaults
 * ({@code budget_remaining=1.0}, {@code burn_rate=0.0}).
 * P8.2 onwards swaps the bean implementation behind
 * {@code cortex.monitoring.slo.backend=micrometer-derivation}.</p>
 *
 * <p>Gated by {@code cortex.monitoring.slo.backend=noop}
 * ({@code matchIfMissing=true}) so this is the default in every
 * profile until the operator opts in to the real derivation.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.monitoring.slo",
        name = "backend",
        havingValue = "noop",
        matchIfMissing = true)
public final class NoopSloBudgetEngine implements SloBudgetEngine {

    @Override
    public String backendId() {
        return SloSnapshot.BACKEND_NOOP;
    }

    @Override
    public SloSnapshot evaluate(final SloDefinition def) {
        return SloSnapshot.noop(def);
    }
}
