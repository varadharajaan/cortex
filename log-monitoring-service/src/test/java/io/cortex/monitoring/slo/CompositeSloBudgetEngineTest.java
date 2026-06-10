package io.cortex.monitoring.slo;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link CompositeSloBudgetEngine} (P8.6).
 */
class CompositeSloBudgetEngineTest {

    @Test
    void worstOfUsesWorstChildBudget() {
        final SloSnapshotStore store = new SloSnapshotStore();
        store.record(child("svc-a", "availability", 0.8d, 0.2d));
        store.record(child("svc-b", "availability", 0.2d, 1.2d));

        final SloSnapshot snap = new CompositeSloBudgetEngine(store)
                .evaluate(def(SloDefinition.CompositeSource.MODE_WORST_OF));

        assertThat(snap.backend()).isEqualTo(SloSnapshot.BACKEND_COMPOSITE);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.2d, within(1e-9d));
        assertThat(snap.burnRate()).isEqualTo(1.2d, within(1e-9d));
    }

    @Test
    void weightedAverageUsesComponentWeights() {
        final SloSnapshotStore store = new SloSnapshotStore();
        store.record(child("svc-a", "availability", 0.8d, 0.2d));
        store.record(child("svc-b", "availability", 0.2d, 1.2d));

        final SloSnapshot snap = new CompositeSloBudgetEngine(store)
                .evaluate(def(
                        SloDefinition.CompositeSource.MODE_WEIGHTED_AVERAGE));

        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.35d, within(1e-9d));
        assertThat(snap.burnRate()).isEqualTo(0.95d, within(1e-9d));
    }

    @Test
    void missingChildReturnsUnknown() {
        final SloSnapshot snap = new CompositeSloBudgetEngine(
                new SloSnapshotStore()).evaluate(def(
                SloDefinition.CompositeSource.MODE_WORST_OF));

        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_UNKNOWN);
        assertThat(snap.reason())
                .isEqualTo("composite:child-missing:svc-a/availability");
    }

    @Test
    void childTransientFailurePropagates() {
        final SloSnapshotStore store = new SloSnapshotStore();
        store.record(SloSnapshot.transientFailure("stub",
                plainDef("svc-a"), "stub:timeout"));

        final SloSnapshot snap = new CompositeSloBudgetEngine(store)
                .evaluate(def(SloDefinition.CompositeSource.MODE_WORST_OF));

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(snap.reason())
                .isEqualTo("composite:child-transient-failure:"
                        + "svc-a/availability");
    }

    @Test
    void missingCompositeSourceIsPermanentFailure() {
        final SloSnapshot snap = new CompositeSloBudgetEngine(
                new SloSnapshotStore()).evaluate(plainDef("svc-a"));

        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(snap.reason())
                .isEqualTo("composite:missing-composite-source");
    }

    private static SloSnapshot child(final String serviceId,
                                     final String sloName,
                                     final double budget,
                                     final double burn) {
        return SloSnapshot.banded("stub",
                new SloDefinition(serviceId, sloName,
                        0.99d, Duration.ofHours(1)),
                budget, burn);
    }

    private static SloDefinition def(final String mode) {
        return new SloDefinition("cortex-system", "system-availability",
                0.99d, Duration.ofMinutes(5),
                null, null, null,
                new SloDefinition.CompositeSource(mode, List.of(
                        new SloDefinition.ComponentRef(
                                "svc-a", "availability", 1.0d),
                        new SloDefinition.ComponentRef(
                                "svc-b", "availability", 3.0d))),
                null);
    }

    private static SloDefinition plainDef(final String serviceId) {
        return new SloDefinition(serviceId, "availability",
                0.99d, Duration.ofHours(1));
    }
}
