package io.cortex.monitoring.slo;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link SloSnapshot} (P8.2 / ADR-0046 D2).
 *
 * <p>Locks the factory shape per LD133: happy-path factories
 * ({@link SloSnapshot#banded(String, SloDefinition, double, double)})
 * leave {@code reason} empty and put the descriptive context in
 * the dedicated numeric fields; failure / unknown / noop
 * factories put context in {@code reason}. Also locks
 * {@link SloSnapshot#classifyBand(double)} thresholds against
 * regressions.</p>
 */
class SloSnapshotTest {

    private static final SloDefinition DEF = new SloDefinition(
            "log-indexer-service", "availability",
            0.99d, Duration.ofHours(1));

    @Test
    void noopFactoryProducesNoopOutcomeWithFullBudgetDefaults() {
        final SloSnapshot snap = SloSnapshot.noop(DEF);
        assertThat(snap.backend()).isEqualTo(SloSnapshot.BACKEND_NOOP);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_NOOP);
        assertThat(snap.serviceId()).isEqualTo("log-indexer-service");
        assertThat(snap.sloName()).isEqualTo("availability");
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(SloSnapshot.UNKNOWN_BUDGET_REMAINING);
        assertThat(snap.burnRate()).isEqualTo(SloSnapshot.UNKNOWN_BURN_RATE);
        assertThat(snap.reason()).contains("noop engine");
    }

    @Test
    void noopFactoryToleratesNullDefinition() {
        final SloSnapshot snap = SloSnapshot.noop(null);
        assertThat(snap.serviceId()).isEmpty();
        assertThat(snap.sloName()).isEmpty();
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_NOOP);
    }

    @Test
    void unknownFactoryCarriesReasonInReasonField() {
        final SloSnapshot snap = SloSnapshot.unknown(
                SloSnapshot.BACKEND_MICROMETER_DERIVATION, DEF,
                "micrometer-derivation:no-data");
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_UNKNOWN);
        assertThat(snap.reason()).isEqualTo("micrometer-derivation:no-data");
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(SloSnapshot.UNKNOWN_BUDGET_REMAINING);
        assertThat(snap.burnRate()).isEqualTo(SloSnapshot.UNKNOWN_BURN_RATE);
    }

    @Test
    void bandedFactoryHealthyAbovePointFive() {
        final SloSnapshot snap = SloSnapshot.banded(
                SloSnapshot.BACKEND_MICROMETER_DERIVATION, DEF,
                0.9d, 0.1d);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_HEALTHY);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.9d, within(1e-9d));
        assertThat(snap.burnRate()).isEqualTo(0.1d, within(1e-9d));
        // LD133: happy-path factories leave reason empty.
        assertThat(snap.reason()).isEmpty();
    }

    @Test
    void bandedFactoryAtRiskBetweenPointOneAndPointFive() {
        final SloSnapshot snap = SloSnapshot.banded(
                SloSnapshot.BACKEND_MICROMETER_DERIVATION, DEF,
                0.3d, 0.7d);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        assertThat(snap.reason()).isEmpty();
    }

    @Test
    void bandedFactoryExhaustedAtAndBelowPointOne() {
        final SloSnapshot snap = SloSnapshot.banded(
                SloSnapshot.BACKEND_MICROMETER_DERIVATION, DEF,
                0.1d, 0.9d);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_EXHAUSTED);
    }

    @Test
    void bandedFactoryExhaustedOnNegative() {
        final SloSnapshot snap = SloSnapshot.banded(
                SloSnapshot.BACKEND_MICROMETER_DERIVATION, DEF,
                -0.5d, 1.5d);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_EXHAUSTED);
    }

    @Test
    void classifyBandBoundaries() {
        // > 0.5 -> healthy
        assertThat(SloSnapshot.classifyBand(0.501d))
                .isEqualTo(SloSnapshot.OUTCOME_HEALTHY);
        // exactly 0.5 -> at_risk (upper bound exclusive on healthy)
        assertThat(SloSnapshot.classifyBand(0.5d))
                .isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        // > 0.1 and <= 0.5 -> at_risk
        assertThat(SloSnapshot.classifyBand(0.11d))
                .isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        // exactly 0.1 -> exhausted (upper bound exclusive on at_risk)
        assertThat(SloSnapshot.classifyBand(0.1d))
                .isEqualTo(SloSnapshot.OUTCOME_EXHAUSTED);
    }

    @Test
    void transientFailureFactoryCarriesReasonInReasonField() {
        final SloSnapshot snap = SloSnapshot.transientFailure(
                SloSnapshot.BACKEND_MICROMETER_DERIVATION, DEF,
                "micrometer-derivation:exception:NullPointerException");
        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(snap.reason())
                .isEqualTo("micrometer-derivation:exception:NullPointerException");
    }

    @Test
    void permanentFailureFactoryCarriesReasonInReasonField() {
        final SloSnapshot snap = SloSnapshot.permanentFailure(
                SloSnapshot.BACKEND_MICROMETER_DERIVATION, DEF,
                "micrometer-derivation:misconfigured:no-registry");
        assertThat(snap.outcome())
                .isEqualTo(SloSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(snap.reason())
                .isEqualTo("micrometer-derivation:misconfigured:no-registry");
    }

    @Test
    void nullBackendCoercesToNoop() {
        final SloSnapshot snap = SloSnapshot.banded(null, DEF, 0.9d, 0.1d);
        assertThat(snap.backend()).isEqualTo(SloSnapshot.BACKEND_NOOP);
    }

    @Test
    void nullReasonCoercesToEmpty() {
        final SloSnapshot snap = SloSnapshot.transientFailure(
                SloSnapshot.BACKEND_MICROMETER_DERIVATION, DEF, null);
        assertThat(snap.reason()).isEmpty();
    }

    @Test
    void nullDefinitionFactoriesDoNotThrow() {
        // Every non-noop factory must tolerate null def so callers
        // never have to null-check before delegating.
        assertThat(SloSnapshot.unknown("b", null, "r").serviceId()).isEmpty();
        assertThat(SloSnapshot.banded("b", null, 0.5d, 0.5d).sloName()).isEmpty();
        assertThat(SloSnapshot.transientFailure("b", null, "r").serviceId())
                .isEmpty();
        assertThat(SloSnapshot.permanentFailure("b", null, "r").sloName())
                .isEmpty();
    }
}
