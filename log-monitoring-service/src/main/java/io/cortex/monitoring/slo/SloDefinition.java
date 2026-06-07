package io.cortex.monitoring.slo;

import java.time.Duration;
import org.apache.commons.lang3.StringUtils;

/**
 * Immutable input to {@link SloBudgetEngine#evaluate(SloDefinition)}
 * (P8.2 / ADR-0046 D1).
 *
 * <p>Identifies the SLO target the engine should evaluate against
 * the current probe counter surface. The {@code serviceId} matches
 * the {@code service_id} tag emitted on
 * {@code cortex.monitoring.probe_total} counters by every
 * {@link io.cortex.monitoring.probe.ServiceHealthProbe} adapter;
 * the {@code sloName} is a short, free-form (but bounded by
 * configuration) label that distinguishes multiple SLOs targeting
 * the same service (e.g. {@code availability},
 * {@code success-ratio-99}).</p>
 *
 * <p>{@code targetSuccessRatio} is the operator-declared success
 * threshold, e.g. {@code 0.99} for "99% of probe attempts must
 * succeed". The complement
 * {@code (1 - targetSuccessRatio) = 0.01} is the allowed error
 * budget over the evaluation window.</p>
 *
 * <p>{@code window} is the evaluation window length and is
 * recorded for downstream operator context (alert annotations,
 * dashboard captions). The {@link MicrometerSloBudgetEngine}
 * derivation evaluates against the since-boot counter snapshot
 * regardless of the window value (operator's responsibility to
 * recycle the service or use Prometheus rate() in the dashboard
 * for true window-based rates); larger window values just signal
 * a "look at me over a longer time horizon" intent.</p>
 *
 * @param serviceId          Eureka service id the SLO targets;
 *                           must match the {@code service_id}
 *                           counter tag; never null/blank
 * @param sloName            short, bounded label distinguishing
 *                           this SLO from others on the same
 *                           service; never null/blank
 * @param targetSuccessRatio operator-declared success threshold in
 *                           {@code (0, 1)} exclusive (0.99 means
 *                           99% of probes must succeed); strictly
 *                           between 0 and 1
 * @param window             operator-declared evaluation window
 *                           (e.g. {@code PT1H}); never null,
 *                           positive
 */
public record SloDefinition(String serviceId, String sloName,
                            double targetSuccessRatio, Duration window) {

    /** Lower bound (exclusive) for {@link #targetSuccessRatio}. */
    public static final double TARGET_MIN_EXCLUSIVE = 0.0d;

    /** Upper bound (exclusive) for {@link #targetSuccessRatio}. */
    public static final double TARGET_MAX_EXCLUSIVE = 1.0d;

    /**
     * Compact ctor enforces the field contract above. SPI
     * implementations may rely on every field being non-null /
     * non-blank and {@code targetSuccessRatio} being in the open
     * interval {@code (0, 1)}.
     */
    public SloDefinition {
        if (StringUtils.isBlank(serviceId)) {
            throw new IllegalArgumentException(
                    "serviceId must not be null or blank");
        }
        if (StringUtils.isBlank(sloName)) {
            throw new IllegalArgumentException(
                    "sloName must not be null or blank");
        }
        if (!(targetSuccessRatio > TARGET_MIN_EXCLUSIVE
                && targetSuccessRatio < TARGET_MAX_EXCLUSIVE)) {
            throw new IllegalArgumentException(
                    "targetSuccessRatio must be in the open interval (0, 1)"
                            + " but was " + targetSuccessRatio);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "window must be a positive Duration");
        }
    }
}
