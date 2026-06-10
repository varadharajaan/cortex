package io.cortex.monitoring.slo;

import io.cortex.monitoring.metrics.MonitoringMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger that evaluates every configured
 * {@link SloDefinition} against every active
 * {@link SloBudgetEngine} and ticks the resulting gauges through
 * {@link MonitoringMetrics#recordSlo(SloSnapshot)}
 * (P8.2 / ADR-0046 D2 / D4).
 *
 * <p>Gated by {@code cortex.monitoring.slo.enabled=true}; OFF by
 * default so the scheduled task does not fire in profiles that
 * have not opted in. When enabled, the {@code @Scheduled} task
 * fires at the {@link SloProperties#evaluationInterval()}
 * cadence (defaults to 30 s; matches the Prometheus scrape
 * interval used by infra/local).</p>
 *
 * <p>The evaluator is engine-agnostic: it iterates over the
 * autowired {@code List<SloBudgetEngine>} bean (Spring binds it
 * to exactly one engine per profile via the {@code @ConditionalOnProperty}
 * gates on each implementation) and tracks every snapshot it
 * receives. Source-aware engines can opt out through
 * {@link SloBudgetEngine#supports(SloDefinition)}, which keeps
 * {@code mixed} mode from recording misleading failures for
 * definitions owned by another backend. A throwing engine (which
 * would violate the SPI contract) is caught at this layer so one
 * rogue engine cannot stall the scheduler loop -- the offending
 * def is logged and skipped; the next definition / next tick
 * continues.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.monitoring.slo",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class SloEvaluator {

    private static final Logger LOG =
            LoggerFactory.getLogger(SloEvaluator.class);

    private final SloProperties properties;
    private final List<SloBudgetEngine> engines;
    private final MonitoringMetrics metrics;
    private final SloSnapshotStore snapshotStore;

    /**
     * Sole ctor. Spring injects the operator-declared properties,
     * the active engines (one bean per profile per the
     * {@code @ConditionalOnProperty} gates), and the shared
     * metrics surface.
     *
     * @param sloProperties typed config bound from
     *                      {@code cortex.monitoring.slo.*}
     * @param sloEngines    autowired list of active engines (one
     *                      element under normal binder selection)
     * @param monitoringMetrics gauge surface to tick with each
     *                          {@link SloSnapshot}
     * @param store             latest snapshot store consumed by
     *                          composite SLOs
     */
    public SloEvaluator(final SloProperties sloProperties,
                        final List<SloBudgetEngine> sloEngines,
                        final MonitoringMetrics monitoringMetrics,
                        final SloSnapshotStore store) {
        this.properties = sloProperties;
        this.engines = sloEngines;
        this.metrics = monitoringMetrics;
        this.snapshotStore = store;
    }

    /**
     * Fire at the cadence declared by
     * {@code cortex.monitoring.slo.evaluation-interval}.
     *
     * <p>The cadence is read indirectly via the SpEL bean
     * reference {@code "#{@sloEvaluationIntervalMillis}"}
     * (resolved in {@link SloEngineConfig#sloEvaluationIntervalMillis(SloProperties)})
     * rather than directly through a
     * {@code "${cortex.monitoring.slo.evaluation-interval}"}
     * placeholder. Spring's
     * {@code ScheduledAnnotationBeanPostProcessor} resolves
     * {@code fixedRateString} via {@code Long.parseLong} with
     * no {@code Duration.parse} fallback in this Boot version,
     * so an operator-friendly value such as {@code 30s} or
     * {@code 1h} would fail bean creation with
     * {@code NumberFormatException}. Routing the value through
     * the adapter bean lets {@code application.yml} keep the
     * typed {@code Duration} contract while {@code @Scheduled}
     * reads the long-as-string form it actually accepts (issue
     * #120 / LD137 / ADR-0046 Amendment 2026-06-08).</p>
     */
    @Scheduled(fixedRateString = "#{@sloEvaluationIntervalMillis}")
    public void evaluateAll() {
        evaluateOnce();
    }

    /**
     * Single evaluation pass. Exposed for unit tests so we can
     * exercise the loop without standing up the Spring scheduler.
     */
    public void evaluateOnce() {
        if (this.properties.definitions().isEmpty()) {
            return;
        }
        for (final SloDefinition def : this.properties.definitions()) {
            for (final SloBudgetEngine engine : this.engines) {
                if (engine.supports(def)) {
                    tickOne(engine, def);
                }
            }
        }
    }

    /**
     * Evaluate one definition against one engine and tick the
     * gauges. Guards the engine call with a try-catch in case a
     * future engine violates the SPI's "MUST NOT throw" contract
     * (defensive belt-and-braces -- the contract is also
     * structurally enforced by every engine's own
     * try-catch in {@link MicrometerSloBudgetEngine#evaluate(SloDefinition)}).
     */
    private void tickOne(final SloBudgetEngine engine,
                         final SloDefinition def) {
        try {
            final SloSnapshot snapshot = engine.evaluate(def);
            if (snapshot != null) {
                this.snapshotStore.record(snapshot);
                this.metrics.recordSlo(snapshot);
            } else {
                LOG.warn("SloBudgetEngine {} returned null snapshot for"
                                + " serviceId={} sloName={}; ignoring",
                        engine.backendId(), def.serviceId(), def.sloName());
            }
        } catch (final RuntimeException ex) {
            LOG.warn("SloBudgetEngine {} threw {} for serviceId={}"
                            + " sloName={}; skipping this definition until"
                            + " the next tick",
                    engine.backendId(), ex.getClass().getSimpleName(),
                    def.serviceId(), def.sloName());
        }
    }
}
