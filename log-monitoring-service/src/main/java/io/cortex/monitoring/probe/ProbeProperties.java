package io.cortex.monitoring.probe;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the probe surface
 * (P8.2b / ADR-0046 Amendment 3 2026-06-08).
 *
 * <p>Bound to prefix {@code cortex.monitoring.probe}. Captures
 * the existing {@code backend} binder gate (already consumed by
 * {@code @ConditionalOnProperty} on each
 * {@link ServiceHealthProbe} implementation) plus three new
 * fields that drive the {@code ScheduledProbeEvaluator} multi-
 * target probe pump shipped in P8.2b:</p>
 *
 * <ul>
 *   <li>{@code enabled} -- gates whether the scheduled pump
 *       fires; OFF by default so the engine stays a structural
 *       no-op until operators opt in per profile (mirrors the
 *       {@code cortex.monitoring.slo.enabled} contract).</li>
 *   <li>{@code evaluationInterval} -- cadence at which the pump
 *       fires; defaults to {@link #DEFAULT_EVALUATION_INTERVAL}
 *       (30 s, same as the Prometheus scrape interval used by
 *       infra/local).</li>
 *   <li>{@code targets} -- operator-declared list of Eureka
 *       service ids the pump iterates each tick; defaults to
 *       the empty list (pump becomes a no-op rather than
 *       NPE). The shipped {@code application.yml} declares all
 *       six cortex services as the default value so flipping
 *       {@code enabled=true} gives availability monitoring for
 *       free.</li>
 * </ul>
 *
 * <p>{@code backend} is intentionally NOT changed by this
 * record -- the {@code @ConditionalOnProperty} on each
 * {@link ServiceHealthProbe} implementation still reads
 * {@code cortex.monitoring.probe.backend} directly (binder
 * gates fire during context bootstrap, before
 * {@code @ConfigurationProperties} binding completes). The
 * field is mirrored here so a single source of truth describes
 * the property surface.</p>
 *
 * <p>Compact-ctor defensive defaults keep partially-filled yml
 * usable: a missing {@code backend} coerces to
 * {@link #DEFAULT_BACKEND}; a missing
 * {@code evaluation-interval} coerces to
 * {@link #DEFAULT_EVALUATION_INTERVAL}; a missing
 * {@code targets} coerces to an empty immutable list.</p>
 *
 * @param backend            binder gate value selecting which
 *                           {@link ServiceHealthProbe} bean
 *                           wires; one of {@code noop} or
 *                           {@code eureka-actuator}; defaults to
 *                           {@link #DEFAULT_BACKEND}
 * @param enabled            whether the scheduled multi-target
 *                           probe pump fires; false default so
 *                           the engine is OFF until the operator
 *                           opts in
 * @param evaluationInterval cadence at which the pump fires;
 *                           defaults to
 *                           {@link #DEFAULT_EVALUATION_INTERVAL}
 * @param targets            operator-declared list of Eureka
 *                           service ids the pump probes each
 *                           tick; may be empty (pump becomes a
 *                           no-op) but is never null
 */
@Validated
@ConfigurationProperties(prefix = "cortex.monitoring.probe")
public record ProbeProperties(String backend, boolean enabled,
                              Duration evaluationInterval,
                              List<String> targets) {

    /**
     * Default value of {@code backend}; mirrors
     * {@link HealthSnapshot#BACKEND_NOOP}. Keeps the engine
     * surface OFF unless the operator opts in.
     */
    public static final String DEFAULT_BACKEND = HealthSnapshot.BACKEND_NOOP;

    /** Default cadence of the scheduled multi-target probe pump. */
    public static final Duration DEFAULT_EVALUATION_INTERVAL =
            Duration.ofSeconds(30);

    /**
     * Defensive defaults so a single missing env-var doesn't
     * crash the boot -- the noop backend is still the production
     * default, so the only consumers of this class are tests +
     * dev-mode boots that opted into
     * {@code backend=eureka-actuator} and
     * {@code enabled=true}.
     */
    public ProbeProperties {
        if (StringUtils.isBlank(backend)) {
            backend = DEFAULT_BACKEND;
        }
        if (evaluationInterval == null || evaluationInterval.isZero()
                || evaluationInterval.isNegative()) {
            evaluationInterval = DEFAULT_EVALUATION_INTERVAL;
        }
        targets = targets == null
                ? Collections.emptyList()
                : List.copyOf(targets);
    }
}
