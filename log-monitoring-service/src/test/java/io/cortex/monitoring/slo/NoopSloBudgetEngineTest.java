package io.cortex.monitoring.slo;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NoopSloBudgetEngine} (P8.2 / ADR-0046 D1).
 *
 * <p>The default backend must always return a
 * {@link SloSnapshot#noop(SloDefinition)} verdict regardless of
 * the definition, and its {@code backendId} must be the bounded
 * constant {@link SloSnapshot#BACKEND_NOOP}.</p>
 */
class NoopSloBudgetEngineTest {

    @Test
    void backendIdIsBoundedConstant() {
        final NoopSloBudgetEngine engine = new NoopSloBudgetEngine();
        assertThat(engine.backendId()).isEqualTo(SloSnapshot.BACKEND_NOOP);
    }

    @Test
    void evaluateAlwaysReturnsNoopVerdict() {
        final NoopSloBudgetEngine engine = new NoopSloBudgetEngine();
        final SloDefinition def = new SloDefinition(
                "log-indexer-service", "availability",
                0.99d, Duration.ofHours(1), null);
        final SloSnapshot snap = engine.evaluate(def);
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_NOOP);
        assertThat(snap.backend()).isEqualTo(SloSnapshot.BACKEND_NOOP);
        assertThat(snap.serviceId()).isEqualTo("log-indexer-service");
        assertThat(snap.sloName()).isEqualTo("availability");
    }

    @Test
    void noopVerdictDefaultsGaugesToFullBudget() {
        final NoopSloBudgetEngine engine = new NoopSloBudgetEngine();
        final SloDefinition def = new SloDefinition(
                "svc-a", "a", 0.5d, Duration.ofMinutes(5), null);
        final SloSnapshot snap = engine.evaluate(def);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(SloSnapshot.UNKNOWN_BUDGET_REMAINING);
        assertThat(snap.burnRate())
                .isEqualTo(SloSnapshot.UNKNOWN_BURN_RATE);
    }
}
