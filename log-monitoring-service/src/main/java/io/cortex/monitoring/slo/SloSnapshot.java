package io.cortex.monitoring.slo;

/**
 * Immutable verdict returned by a {@link SloBudgetEngine} for a
 * single {@link SloDefinition} evaluation (P8.2 / ADR-0046 D2).
 *
 * <p>Carries the six pieces of information the
 * {@link io.cortex.monitoring.metrics.MonitoringMetrics} gauge
 * surface needs: {@code backend} (which engine produced it --
 * {@code micrometer-derivation}, or {@code noop} default),
 * {@code serviceId} + {@code sloName} (compose the gauge tag
 * pair), {@code outcome} (coarse-grained classification band --
 * {@code healthy / at_risk / exhausted / unknown / noop /
 * transient_failure / permanent_failure}),
 * {@code budgetRemainingRatio} (the value of
 * {@code cortex.monitoring.slo_budget_remaining}; in
 * {@code [-1.0, 1.0]}; {@code 1.0} = full budget, {@code 0.0} =
 * exhausted, negative = burned beyond budget), {@code burnRate}
 * (the value of {@code cortex.monitoring.slo_burn_rate};
 * {@code >= 0.0}; {@code 1.0} means burning exactly at the SLO
 * target rate, {@code > 1.0} faster), and a short {@code reason}
 * string that surfaces only on failure / unknown outcomes (always
 * empty on the three happy-path bands per LD133).</p>
 *
 * @param backend              one of {@link #BACKEND_NOOP} or
 *                             {@link #BACKEND_MICROMETER_DERIVATION};
 *                             bounded enum-like string drives the
 *                             metric tag cardinality
 * @param serviceId            target Eureka service id; matches
 *                             the underlying probe counter tag
 * @param sloName              SLO label (mirrors the
 *                             {@link SloDefinition#sloName()})
 * @param outcome              one of the {@code OUTCOME_*}
 *                             constants
 * @param budgetRemainingRatio gauge value of
 *                             {@code cortex.monitoring.slo_budget_remaining}
 * @param burnRate             gauge value of
 *                             {@code cortex.monitoring.slo_burn_rate}
 * @param reason               short categorical explanation on
 *                             failure / unknown outcomes (e.g.
 *                             {@code micrometer-derivation:no-data}
 *                             or {@code micrometer-derivation:exception:NPE});
 *                             always empty on happy-path bands per LD133
 */
public record SloSnapshot(String backend, String serviceId, String sloName,
                          String outcome, double budgetRemainingRatio,
                          double burnRate, String reason) {

    /** Backend value emitted by {@link NoopSloBudgetEngine}. */
    public static final String BACKEND_NOOP = "noop";

    /** Backend value emitted by {@link MicrometerSloBudgetEngine}. */
    public static final String BACKEND_MICROMETER_DERIVATION =
            "micrometer-derivation";

    /** Outcome value: noop engine returned without evaluation. */
    public static final String OUTCOME_NOOP = "noop";

    /**
     * Outcome value: budget remaining strictly greater than
     * {@link #BAND_AT_RISK_UPPER_BOUND} of the allowed error
     * budget (default {@code > 50%}).
     */
    public static final String OUTCOME_HEALTHY = "healthy";

    /**
     * Outcome value: budget remaining in
     * {@code (BAND_EXHAUSTED_UPPER_BOUND,
     * BAND_AT_RISK_UPPER_BOUND]} (default {@code (10%, 50%]}).
     */
    public static final String OUTCOME_AT_RISK = "at_risk";

    /**
     * Outcome value: budget remaining less than or equal to
     * {@link #BAND_EXHAUSTED_UPPER_BOUND} (default
     * {@code <= 10%}); includes negative (over-burned) values.
     */
    public static final String OUTCOME_EXHAUSTED = "exhausted";

    /**
     * Outcome value: no counter data available yet (target service
     * has been probed zero times during the window); gauges
     * default to {@code budgetRemainingRatio=1.0} / {@code burnRate=0.0}
     * so dashboards don't show a worst-case spike on cold start.
     */
    public static final String OUTCOME_UNKNOWN = "unknown";

    /**
     * Outcome value: engine encountered a retriable internal
     * failure during evaluation (e.g. transient
     * {@code MeterRegistry} lookup hiccup); operator alerts on a
     * recurring count.
     */
    public static final String OUTCOME_TRANSIENT_FAILURE = "transient_failure";

    /**
     * Outcome value: engine encountered a non-retriable internal
     * failure (e.g. misconfigured backend); the gauges retain
     * their last-known values; operator alerts immediately.
     */
    public static final String OUTCOME_PERMANENT_FAILURE = "permanent_failure";

    /** Upper bound of the "at risk" band (default 50%). */
    public static final double BAND_AT_RISK_UPPER_BOUND = 0.5d;

    /** Upper bound of the "exhausted" band (default 10%). */
    public static final double BAND_EXHAUSTED_UPPER_BOUND = 0.1d;

    /**
     * Default gauge values for the {@link #OUTCOME_UNKNOWN}
     * verdict. Picked so cold-start dashboards show
     * "no data, all clear" rather than "max burn".
     */
    public static final double UNKNOWN_BUDGET_REMAINING = 1.0d;

    /** Default {@code burnRate} for the {@link #OUTCOME_UNKNOWN} verdict. */
    public static final double UNKNOWN_BURN_RATE = 0.0d;

    /**
     * Classify the {@code budgetRemainingRatio} into one of the
     * three happy-path bands per {@link #BAND_AT_RISK_UPPER_BOUND}
     * and {@link #BAND_EXHAUSTED_UPPER_BOUND}.
     *
     * @param budgetRemaining ratio in {@code [-1.0, 1.0]}
     * @return one of {@link #OUTCOME_HEALTHY}, {@link #OUTCOME_AT_RISK},
     *         {@link #OUTCOME_EXHAUSTED}
     */
    public static String classifyBand(final double budgetRemaining) {
        if (budgetRemaining > BAND_AT_RISK_UPPER_BOUND) {
            return OUTCOME_HEALTHY;
        }
        if (budgetRemaining > BAND_EXHAUSTED_UPPER_BOUND) {
            return OUTCOME_AT_RISK;
        }
        return OUTCOME_EXHAUSTED;
    }

    /**
     * Convenience factory for the "no action taken" verdict
     * returned by {@link NoopSloBudgetEngine}.
     *
     * @param def the definition the engine was asked to evaluate;
     *            must not be null
     * @return a {@link SloSnapshot} with
     *         {@code backend=noop}, {@code outcome=noop}, gauges
     *         defaulted to {@code 1.0} / {@code 0.0} so dashboards
     *         don't show worst-case on the noop backend
     */
    public static SloSnapshot noop(final SloDefinition def) {
        return new SloSnapshot(BACKEND_NOOP,
                def == null ? "" : def.serviceId(),
                def == null ? "" : def.sloName(),
                OUTCOME_NOOP,
                UNKNOWN_BUDGET_REMAINING, UNKNOWN_BURN_RATE,
                "noop engine (P8.2 default); set"
                        + " cortex.monitoring.slo.backend=micrometer-derivation"
                        + " to enable real evaluation");
    }

    /**
     * Convenience factory for a "no data available" verdict
     * (target service has been probed zero times). Gauges default
     * to {@link #UNKNOWN_BUDGET_REMAINING} and
     * {@link #UNKNOWN_BURN_RATE} so cold-start dashboards show
     * "all clear, no data" instead of worst-case.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param def     the definition the engine was asked to
     *                evaluate
     * @param reason  short categorical explanation (e.g.
     *                {@code micrometer-derivation:no-data})
     * @return a {@link SloSnapshot} with
     *         {@code outcome=unknown}
     */
    public static SloSnapshot unknown(final String backend,
                                      final SloDefinition def,
                                      final String reason) {
        return new SloSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                def == null ? "" : def.serviceId(),
                def == null ? "" : def.sloName(),
                OUTCOME_UNKNOWN,
                UNKNOWN_BUDGET_REMAINING, UNKNOWN_BURN_RATE,
                reason == null ? "" : reason);
    }

    /**
     * Convenience factory for the band-classified happy-path
     * verdict. Outcome is derived from {@code budgetRemaining} via
     * {@link #classifyBand(double)}.
     *
     * @param backend         one of the {@code BACKEND_*} constants
     * @param def             the definition the engine evaluated
     * @param budgetRemaining gauge value in {@code [-1.0, 1.0]}
     * @param burn            gauge value, {@code >= 0.0}
     * @return a {@link SloSnapshot} with {@code outcome} set to
     *         healthy / at_risk / exhausted per the band; reason
     *         empty per LD133 happy-path convention
     */
    public static SloSnapshot banded(final String backend,
                                     final SloDefinition def,
                                     final double budgetRemaining,
                                     final double burn) {
        return new SloSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                def == null ? "" : def.serviceId(),
                def == null ? "" : def.sloName(),
                classifyBand(budgetRemaining),
                budgetRemaining, burn, "");
    }

    /**
     * Convenience factory for a retriable internal-engine failure
     * verdict (engine code threw or registry lookup failed
     * transiently).
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param def     the definition the engine was asked to evaluate
     * @param reason  short categorical explanation (e.g.
     *                {@code micrometer-derivation:exception:NPE})
     * @return a {@link SloSnapshot} with
     *         {@code outcome=transient_failure}; gauges default to
     *         the {@code unknown} pair so dashboards don't dip
     */
    public static SloSnapshot transientFailure(final String backend,
                                               final SloDefinition def,
                                               final String reason) {
        return new SloSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                def == null ? "" : def.serviceId(),
                def == null ? "" : def.sloName(),
                OUTCOME_TRANSIENT_FAILURE,
                UNKNOWN_BUDGET_REMAINING, UNKNOWN_BURN_RATE,
                reason == null ? "" : reason);
    }

    /**
     * Convenience factory for a non-retriable internal-engine
     * failure verdict (engine mis-configured, dependency missing).
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param def     the definition the engine was asked to evaluate
     * @param reason  short categorical explanation (e.g.
     *                {@code micrometer-derivation:misconfigured:no-registry})
     * @return a {@link SloSnapshot} with
     *         {@code outcome=permanent_failure}
     */
    public static SloSnapshot permanentFailure(final String backend,
                                               final SloDefinition def,
                                               final String reason) {
        return new SloSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                def == null ? "" : def.serviceId(),
                def == null ? "" : def.sloName(),
                OUTCOME_PERMANENT_FAILURE,
                UNKNOWN_BUDGET_REMAINING, UNKNOWN_BURN_RATE,
                reason == null ? "" : reason);
    }
}
