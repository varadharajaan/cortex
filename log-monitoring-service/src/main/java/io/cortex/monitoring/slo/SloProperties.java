package io.cortex.monitoring.slo;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the SLO budget engine surface
 * (P8.2 / ADR-0046 D2 / D4).
 *
 * <p>Bound to prefix {@code cortex.monitoring.slo}. {@code enabled}
 * gates the scheduled {@link SloEvaluator} (defaults to
 * {@code false} so the engine is OFF by default; operators opt in
 * per profile). {@code backend} drives the
 * {@code @ConditionalOnProperty} on each {@link SloBudgetEngine}
 * implementation. {@code evaluationInterval} controls the
 * {@code @Scheduled} firing cadence on the evaluator.
 * {@code definitions} is the operator-declared list of SLO
 * targets the evaluator iterates.</p>
 *
 * <p>Compact-ctor defensive defaults keep partially-filled yml
 * usable: a missing {@code evaluation-interval} coerces to the
 * built-in default ({@link #DEFAULT_EVALUATION_INTERVAL}); a
 * missing {@code backend} coerces to {@link #DEFAULT_BACKEND}; a
 * missing {@code definitions} coerces to an empty immutable list
 * (so the evaluator's iteration is a no-op rather than NPE).</p>
 *
 * @param enabled            whether the {@link SloEvaluator}
 *                           scheduled task should fire; false
 *                           default so the engine is OFF until
 *                           the operator opts in
 * @param backend            binder gate value selecting which
 *                           {@link SloBudgetEngine} bean wires;
 *                           one of {@code noop} or
 *                           {@code micrometer-derivation} or
 *                           {@code counter-family};
 *                           defaults to {@link #DEFAULT_BACKEND}
 * @param evaluationInterval cadence at which the
 *                           {@link SloEvaluator} fires; defaults
 *                           to {@link #DEFAULT_EVALUATION_INTERVAL}
 * @param definitions        operator-declared list of SLO targets;
 *                           may be empty (evaluator becomes a
 *                           no-op) but is never null
 */
@Validated
@ConfigurationProperties(prefix = "cortex.monitoring.slo")
public record SloProperties(boolean enabled, String backend,
                            Duration evaluationInterval,
                            List<SloDefinition> definitions) {

    /** Default cadence of the {@link SloEvaluator} scheduled task. */
    public static final Duration DEFAULT_EVALUATION_INTERVAL =
            Duration.ofSeconds(30);

    /**
     * Default value of {@code backend}; mirrors
     * {@link SloSnapshot#BACKEND_NOOP}. Keeps the engine surface
     * OFF unless the operator opts in.
     */
    public static final String DEFAULT_BACKEND = SloSnapshot.BACKEND_NOOP;

    /**
     * Defensive defaults so a single missing env-var doesn't
     * crash the boot -- the noop engine is still the production
     * default, so the only consumers of this class are tests +
     * dev-mode boots that opted into
     * {@code backend=micrometer-derivation}.
     */
    public SloProperties {
        if (StringUtils.isBlank(backend)) {
            backend = DEFAULT_BACKEND;
        }
        if (evaluationInterval == null || evaluationInterval.isZero()
                || evaluationInterval.isNegative()) {
            evaluationInterval = DEFAULT_EVALUATION_INTERVAL;
        }
        definitions = definitions == null
                ? Collections.emptyList()
                : List.copyOf(definitions);
    }
}
