package io.cortex.monitoring.slo;

import io.cortex.monitoring.metrics.MonitoringMetrics;
import io.cortex.monitoring.probe.HealthSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link MicrometerSloBudgetEngine}
 * (P8.2 / ADR-0046 D3).
 *
 * <p>Seeds a {@link SimpleMeterRegistry} with realistic
 * {@code cortex.monitoring.probe_total} counter values and
 * verifies the engine maps them through to the expected outcome
 * band + gauge values. Covers the entire band table:</p>
 *
 * <ul>
 *   <li>no counters at all -> {@code unknown}</li>
 *   <li>only success counters -> {@code healthy} (budget=1.0,
 *       burn=0.0)</li>
 *   <li>budget remaining > 50% -> {@code healthy}</li>
 *   <li>budget remaining in (10%, 50%] -> {@code at_risk}</li>
 *   <li>budget remaining <= 10% -> {@code exhausted}</li>
 *   <li>budget remaining clamps at -1.0 on severe over-burn</li>
 *   <li>other-service counters do NOT bleed into the target's
 *       computation</li>
 *   <li>backend tag is the bounded constant</li>
 * </ul>
 */
class MicrometerSloBudgetEngineTest {

    private static final String SERVICE_ID = "log-indexer-service";

    private MeterRegistry registry;
    private MicrometerSloBudgetEngine engine;

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        this.engine = new MicrometerSloBudgetEngine(this.registry);
    }

    @Test
    void backendIdIsBoundedConstant() {
        assertThat(this.engine.backendId())
                .isEqualTo(SloSnapshot.BACKEND_MICROMETER_DERIVATION);
    }

    @Test
    void evaluateNoCountersReturnsUnknown() {
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_UNKNOWN);
        assertThat(snap.reason())
                .isEqualTo("micrometer-derivation:no-data");
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(SloSnapshot.UNKNOWN_BUDGET_REMAINING);
        assertThat(snap.burnRate())
                .isEqualTo(SloSnapshot.UNKNOWN_BURN_RATE);
    }

    @Test
    void evaluateOnlySuccessesReturnsHealthyFullBudget() {
        seed(HealthSnapshot.OUTCOME_HEALTHY, 100.0d);
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_HEALTHY);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(1.0d, within(1e-9d));
        assertThat(snap.burnRate()).isEqualTo(0.0d, within(1e-9d));
    }

    @Test
    void evaluateMixedSuccessFailureHealthyBand() {
        // target=0.99 -> errorBudget=0.01.
        // 998 success + 2 failures -> errorRate = 2/1000 = 0.002
        // budgetRemaining = (0.01 - 0.002)/0.01 = 0.8 -> healthy
        // burn = 0.002 / 0.01 = 0.2
        seed(HealthSnapshot.OUTCOME_HEALTHY, 998.0d);
        seed(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE, 2.0d);
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_HEALTHY);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.8d, within(1e-9d));
        assertThat(snap.burnRate())
                .isEqualTo(0.2d, within(1e-9d));
    }

    @Test
    void evaluateMixedAtRiskBand() {
        // target=0.99 -> errorBudget=0.01.
        // 993 success + 7 failures -> errorRate=0.007
        // budgetRemaining=(0.01-0.007)/0.01=0.3 -> at_risk
        // burn=0.7
        seed(HealthSnapshot.OUTCOME_HEALTHY, 993.0d);
        seed(HealthSnapshot.OUTCOME_UNHEALTHY, 7.0d);
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_AT_RISK);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.3d, within(1e-9d));
        assertThat(snap.burnRate())
                .isEqualTo(0.7d, within(1e-9d));
    }

    @Test
    void evaluateMixedExhaustedBand() {
        // target=0.99 -> errorBudget=0.01.
        // 990 success + 10 failures -> errorRate=0.01
        // budgetRemaining=0 -> exhausted, burn=1.0
        seed(HealthSnapshot.OUTCOME_HEALTHY, 990.0d);
        seed(HealthSnapshot.OUTCOME_UNHEALTHY, 5.0d);
        seed(HealthSnapshot.OUTCOME_UNREACHABLE, 5.0d);
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_EXHAUSTED);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.0d, within(1e-9d));
        assertThat(snap.burnRate())
                .isEqualTo(1.0d, within(1e-9d));
    }

    @Test
    void evaluateOverBurnedClampsAtNegativeOne() {
        // 100% failures vs 99% target -> errorRate=1.0,
        // errorBudget=0.01, raw budgetRemaining=(0.01-1.0)/0.01=-99.0
        // clamped to -1.0
        seed(HealthSnapshot.OUTCOME_PERMANENT_FAILURE, 50.0d);
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_EXHAUSTED);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(-1.0d, within(1e-9d));
        // burn is uncapped (operators want to see how bad it is)
        assertThat(snap.burnRate()).isEqualTo(100.0d, within(1e-9d));
    }

    @Test
    void evaluateIgnoresCountersForOtherServiceIds() {
        // Seed the target service with a healthy band.
        seed(HealthSnapshot.OUTCOME_HEALTHY, 998.0d);
        seed(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE, 2.0d);
        // Bleed counters for a DIFFERENT service. These must
        // NOT influence the target's evaluation.
        Counter.builder(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", HealthSnapshot.BACKEND_EUREKA_ACTUATOR)
                .tag("outcome", HealthSnapshot.OUTCOME_PERMANENT_FAILURE)
                .tag("service_id", "some-other-service")
                .register(this.registry)
                .increment(10_000.0d);
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_HEALTHY);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(0.8d, within(1e-9d));
    }

    @Test
    void evaluateIgnoresNoopOutcomeCounters() {
        // OUTCOME_NOOP samples (e.g. from NoopServiceHealthProbe)
        // should NOT count as either success or failure -- the
        // probe didn't actually evaluate anything.
        seed(HealthSnapshot.OUTCOME_HEALTHY, 5.0d);
        seed(HealthSnapshot.OUTCOME_NOOP, 1000.0d);
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_HEALTHY);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(1.0d, within(1e-9d));
    }

    @Test
    void evaluateDegradedCountsAsSuccess() {
        // Per ADR-0044 D3 design: degraded means "still serving
        // traffic", so for SLO purposes the operator does NOT
        // burn budget. ADR-0046 D3 documents this.
        seed(HealthSnapshot.OUTCOME_DEGRADED, 100.0d);
        final SloSnapshot snap = this.engine.evaluate(def(0.99d));
        assertThat(snap.outcome()).isEqualTo(SloSnapshot.OUTCOME_HEALTHY);
        assertThat(snap.budgetRemainingRatio())
                .isEqualTo(1.0d, within(1e-9d));
    }

    private SloDefinition def(final double target) {
        return new SloDefinition(SERVICE_ID, "availability", target,
                Duration.ofHours(1), null);
    }

    private void seed(final String outcome, final double count) {
        Counter.builder(MonitoringMetrics.METRIC_PROBE_TOTAL)
                .tag("backend", HealthSnapshot.BACKEND_EUREKA_ACTUATOR)
                .tag("outcome", outcome)
                .tag("service_id", SERVICE_ID)
                .register(this.registry)
                .increment(count);
    }
}
