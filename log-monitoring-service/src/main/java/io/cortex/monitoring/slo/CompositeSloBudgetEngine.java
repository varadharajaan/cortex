package io.cortex.monitoring.slo;

import io.cortex.monitoring.slo.SloDefinition.ComponentRef;
import io.cortex.monitoring.slo.SloDefinition.CompositeSource;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * P8.6 backend that composes already-evaluated child SLO snapshots.
 */
@Component
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'composite'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
public final class CompositeSloBudgetEngine implements SloBudgetEngine {

    private final SloSnapshotStore snapshotStore;

    /**
     * Spring constructor.
     *
     * @param store latest snapshot store populated by
     *              {@link SloEvaluator}
     */
    public CompositeSloBudgetEngine(final SloSnapshotStore store) {
        this.snapshotStore = store;
    }

    @Override
    public String backendId() {
        return SloSnapshot.BACKEND_COMPOSITE;
    }

    @Override
    public boolean supports(final SloDefinition def) {
        return def != null && def.composite() != null;
    }

    @Override
    public SloSnapshot evaluate(final SloDefinition def) {
        try {
            if (def == null || def.composite() == null) {
                return SloSnapshot.permanentFailure(backendId(), def,
                        backendId() + ":missing-composite-source");
            }
            if (CompositeSource.MODE_WEIGHTED_AVERAGE.equals(
                    def.composite().mode())) {
                return weightedAverage(def);
            }
            return worstOf(def);
        } catch (final RuntimeException ex) {
            return SloSnapshot.transientFailure(backendId(), def,
                    backendId() + ":exception:" + ex.getClass().getSimpleName());
        }
    }

    private SloSnapshot worstOf(final SloDefinition def) {
        SloSnapshot worst = null;
        for (final ComponentRef component : def.composite().components()) {
            final SloSnapshot child = usableChild(def, component);
            if (isFailure(child)) {
                return child;
            }
            if (worst == null || child.budgetRemainingRatio()
                    < worst.budgetRemainingRatio()) {
                worst = child;
            }
        }
        if (worst == null) {
            return SloSnapshot.unknown(backendId(), def,
                    backendId() + ":no-data");
        }
        return SloSnapshot.banded(backendId(), def,
                worst.budgetRemainingRatio(), worst.burnRate());
    }

    private SloSnapshot weightedAverage(final SloDefinition def) {
        double totalWeight = 0.0d;
        double budget = 0.0d;
        double burn = 0.0d;
        for (final ComponentRef component : def.composite().components()) {
            final SloSnapshot child = usableChild(def, component);
            if (isFailure(child)) {
                return child;
            }
            totalWeight += component.weight();
            budget += child.budgetRemainingRatio() * component.weight();
            burn += child.burnRate() * component.weight();
        }
        if (totalWeight <= 0.0d) {
            return SloSnapshot.unknown(backendId(), def,
                    backendId() + ":no-data");
        }
        return SloSnapshot.banded(backendId(), def,
                SloBudgetMath.clamp(budget / totalWeight),
                Math.max(0.0d, burn / totalWeight));
    }

    private SloSnapshot usableChild(final SloDefinition def,
                                    final ComponentRef component) {
        final Optional<SloSnapshot> maybeChild = this.snapshotStore.find(
                component.serviceId(), component.sloName());
        if (maybeChild.isEmpty()) {
            return SloSnapshot.unknown(backendId(), def,
                    backendId() + ":child-missing:"
                            + component.serviceId() + "/" + component.sloName());
        }
        final SloSnapshot child = maybeChild.get();
        if (SloSnapshot.OUTCOME_PERMANENT_FAILURE.equals(child.outcome())) {
            return SloSnapshot.permanentFailure(backendId(), def,
                    backendId() + ":child-permanent-failure:"
                            + component.serviceId() + "/" + component.sloName());
        }
        if (SloSnapshot.OUTCOME_TRANSIENT_FAILURE.equals(child.outcome())) {
            return SloSnapshot.transientFailure(backendId(), def,
                    backendId() + ":child-transient-failure:"
                            + component.serviceId() + "/" + component.sloName());
        }
        if (SloSnapshot.OUTCOME_UNKNOWN.equals(child.outcome())
                || SloSnapshot.OUTCOME_NOOP.equals(child.outcome())) {
            return SloSnapshot.unknown(backendId(), def,
                    backendId() + ":child-no-data:"
                            + component.serviceId() + "/" + component.sloName());
        }
        return child;
    }

    private static boolean isFailure(final SloSnapshot snapshot) {
        return !snapshot.reason().isEmpty();
    }
}
