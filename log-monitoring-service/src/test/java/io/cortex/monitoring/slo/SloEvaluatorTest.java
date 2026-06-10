package io.cortex.monitoring.slo;

import io.cortex.monitoring.metrics.MonitoringMetrics;
import io.cortex.monitoring.probe.NoopServiceHealthProbe;
import io.cortex.monitoring.probe.ServiceHealthProbe;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SloEvaluator} (P8.2 / ADR-0046 D2 / D4).
 *
 * <p>Exercises the scheduled loop body via the
 * {@link SloEvaluator#evaluateOnce()} hook so the tests don't
 * need to wait on the Spring scheduler. Verifies: (a) empty
 * definitions list is a no-op (b) every definition is evaluated
 * against every active engine on each tick (c) snapshots are
 * recorded via the metrics surface (d) a throwing engine is
 * caught + the next definition still runs (e) a null snapshot
 * from a misbehaving engine is logged + skipped without throwing.</p>
 */
class SloEvaluatorTest {

    @Test
    void emptyDefinitionsListIsNoOp() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final List<ServiceHealthProbe> probes =
                List.of(new NoopServiceHealthProbe());
        final MonitoringMetrics metrics =
                new MonitoringMetrics(registry, probes);
        final SloEvaluator evaluator = new SloEvaluator(
                new SloProperties(true, "noop", Duration.ofSeconds(30),
                        List.of()),
                List.<SloBudgetEngine>of(new NoopSloBudgetEngine()),
                metrics,
                new SloSnapshotStore());

        evaluator.evaluateOnce();

        // No gauges should have been registered (cache empty).
        assertThat(registry.find(MonitoringMetrics.METRIC_SLO_BUDGET_REMAINING)
                .gauges()).isEmpty();
    }

    @Test
    void everyDefinitionIsEvaluatedAgainstEveryEngine() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final MonitoringMetrics metrics = new MonitoringMetrics(registry,
                List.of(new NoopServiceHealthProbe()));
        final SloDefinition def1 = new SloDefinition(
                "svc-a", "availability", 0.99d, Duration.ofHours(1), null);
        final SloDefinition def2 = new SloDefinition(
                "svc-b", "availability", 0.99d, Duration.ofHours(1), null);
        final SloEvaluator evaluator = new SloEvaluator(
                new SloProperties(true, "noop", Duration.ofSeconds(30),
                        List.of(def1, def2)),
                List.<SloBudgetEngine>of(new NoopSloBudgetEngine()),
                metrics,
                new SloSnapshotStore());

        evaluator.evaluateOnce();

        // Both keys should have gauges registered with the noop
        // default values.
        assertThat(registry.find(MonitoringMetrics.METRIC_SLO_BUDGET_REMAINING)
                .tag("service_id", "svc-a").gauge()).isNotNull();
        assertThat(registry.find(MonitoringMetrics.METRIC_SLO_BUDGET_REMAINING)
                .tag("service_id", "svc-b").gauge()).isNotNull();
    }

    @Test
    void throwingEngineDoesNotStallTheLoop() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final MonitoringMetrics metrics = new MonitoringMetrics(registry,
                List.of(new NoopServiceHealthProbe()));
        final SloDefinition defGood = new SloDefinition(
                "svc-good", "availability", 0.99d, Duration.ofHours(1), null);
        final SloDefinition defBad = new SloDefinition(
                "svc-bad", "availability", 0.99d, Duration.ofHours(1), null);
        final SloBudgetEngine throwing = new SloBudgetEngine() {
            @Override
            public String backendId() {
                return "throwing-engine";
            }

            @Override
            public SloSnapshot evaluate(final SloDefinition def) {
                if ("svc-bad".equals(def.serviceId())) {
                    throw new RuntimeException("boom");
                }
                return SloSnapshot.banded(backendId(), def, 0.9d, 0.1d);
            }
        };
        final SloEvaluator evaluator = new SloEvaluator(
                new SloProperties(true, "noop", Duration.ofSeconds(30),
                        List.of(defGood, defBad)),
                List.<SloBudgetEngine>of(throwing),
                metrics,
                new SloSnapshotStore());

        // Must not throw -- and svc-good must still have its gauge.
        evaluator.evaluateOnce();

        assertThat(registry.find(MonitoringMetrics.METRIC_SLO_BUDGET_REMAINING)
                .tag("service_id", "svc-good").gauge()).isNotNull();
        // svc-bad never got a gauge registered because the engine
        // threw before recordSlo was called.
        assertThat(registry.find(MonitoringMetrics.METRIC_SLO_BUDGET_REMAINING)
                .tag("service_id", "svc-bad").gauge()).isNull();
    }

    @Test
    void nullSnapshotFromEngineIsSkippedWithoutThrowing() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final MonitoringMetrics metrics = new MonitoringMetrics(registry,
                List.of(new NoopServiceHealthProbe()));
        final SloDefinition def = new SloDefinition(
                "svc-a", "availability", 0.99d, Duration.ofHours(1), null);
        final SloBudgetEngine returningNull = new SloBudgetEngine() {
            @Override
            public String backendId() {
                return "null-engine";
            }

            @Override
            public SloSnapshot evaluate(final SloDefinition d) {
                return null;
            }
        };
        final SloEvaluator evaluator = new SloEvaluator(
                new SloProperties(true, "noop", Duration.ofSeconds(30),
                        List.of(def)),
                List.<SloBudgetEngine>of(returningNull),
                metrics,
                new SloSnapshotStore());

        // Must not throw and must not register the gauge for this key.
        evaluator.evaluateOnce();

        assertThat(registry.find(MonitoringMetrics.METRIC_SLO_BUDGET_REMAINING)
                .gauges()).isEmpty();
    }

    @Test
    void engineSnapshotIsRecordedThroughMetricsSurface() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final MonitoringMetrics metrics = new MonitoringMetrics(registry,
                List.of(new NoopServiceHealthProbe()));
        final SloDefinition def = new SloDefinition(
                "svc-a", "availability", 0.99d, Duration.ofHours(1), null);
        final SloBudgetEngine engine = new SloBudgetEngine() {
            @Override
            public String backendId() {
                return "stub";
            }

            @Override
            public SloSnapshot evaluate(final SloDefinition d) {
                return SloSnapshot.banded(backendId(), d, 0.42d, 0.58d);
            }
        };
        final SloEvaluator evaluator = new SloEvaluator(
                new SloProperties(true, "noop", Duration.ofSeconds(30),
                        List.of(def)),
                List.<SloBudgetEngine>of(engine),
                metrics,
                new SloSnapshotStore());

        evaluator.evaluateOnce();

        assertThat(registry.find(MonitoringMetrics.METRIC_SLO_BUDGET_REMAINING)
                .tag("service_id", "svc-a")
                .tag("slo_name", "availability")
                .gauge().value()).isEqualTo(0.42d);
        assertThat(registry.find(MonitoringMetrics.METRIC_SLO_BURN_RATE)
                .tag("service_id", "svc-a")
                .tag("slo_name", "availability")
                .gauge().value()).isEqualTo(0.58d);
    }
}
