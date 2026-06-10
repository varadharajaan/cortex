package io.cortex.monitoring.slo;

/**
 * Shared success/failure ratio math for SLO engines that reduce
 * their input source to two counts.
 */
final class SloBudgetMath {

    /** Lower clamp for {@code budgetRemainingRatio}. */
    private static final double BUDGET_FLOOR = -1.0d;

    /** Upper clamp for {@code budgetRemainingRatio}. */
    private static final double BUDGET_CEILING = 1.0d;

    private SloBudgetMath() {
    }

    static SloSnapshot snapshotFromCounts(final String backend,
                                          final SloDefinition def,
                                          final double successes,
                                          final double failures) {
        final double total = successes + failures;
        if (total <= 0.0d) {
            return SloSnapshot.unknown(backend, def, backend + ":no-data");
        }
        final double errorBudget = 1.0d - def.targetSuccessRatio();
        final double errorRate = failures / total;
        final double burnRate = errorRate / errorBudget;
        final double budgetRemaining =
                clamp((errorBudget - errorRate) / errorBudget);
        return SloSnapshot.banded(backend, def, budgetRemaining, burnRate);
    }

    static double clamp(final double value) {
        if (value < BUDGET_FLOOR) {
            return BUDGET_FLOOR;
        }
        if (value > BUDGET_CEILING) {
            return BUDGET_CEILING;
        }
        return value;
    }
}
