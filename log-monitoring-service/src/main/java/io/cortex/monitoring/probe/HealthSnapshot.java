package io.cortex.monitoring.probe;

/**
 * Immutable verdict returned by a {@link ServiceHealthProbe} for a
 * single probe call (P8.0 / ADR-0044 D1).
 *
 * <p>Carries the four pieces of information the caller needs in
 * order to bump the
 * {@code cortex.monitoring.probe_total} counter with the right
 * tag values: {@code backend} (which probe backend handled it --
 * {@code eureka-actuator}, or {@code noop} in P8.0),
 * {@code outcome} (the coarse-grained result -- {@code healthy},
 * {@code degraded}, {@code unhealthy}, {@code unreachable},
 * {@code noop}, {@code transient_failure},
 * {@code permanent_failure}), {@code reason} (short
 * human-readable explanation), {@code detail} (free-form
 * context, e.g. the raw {@code status} value from the upstream
 * {@code /actuator/health} JSON).</p>
 *
 * @param backend one of {@code eureka-actuator} or {@code noop};
 *                bounded enum-like string drives the metric tag
 *                cardinality cap per Part 17
 * @param outcome one of {@code healthy}, {@code degraded},
 *                {@code unhealthy}, {@code unreachable},
 *                {@code noop}, {@code transient_failure},
 *                {@code permanent_failure}; bounded enum-like
 *                string for the same reason
 * @param reason  short categorical explanation, e.g.
 *                {@code actuator:UP} or
 *                {@code eureka-actuator:connect-refused};
 *                surfaces in the caller log line
 * @param detail  free-form context (raw upstream status, latency
 *                ms, etc.); never used as a Micrometer tag value
 */
public record HealthSnapshot(String backend, String outcome,
                             String reason, String detail) {

    /** Backend value emitted by the no-op probe in P8.0. */
    public static final String BACKEND_NOOP = "noop";

    /**
     * Backend value emitted by the Eureka-discovery REST adapter in
     * P8.1+.
     */
    public static final String BACKEND_EUREKA_ACTUATOR = "eureka-actuator";

    /** Outcome value: noop probe (P8.0 default) returned without action. */
    public static final String OUTCOME_NOOP = "noop";

    /**
     * Outcome value: target service reported {@code UP} on
     * {@code /actuator/health}.
     */
    public static final String OUTCOME_HEALTHY = "healthy";

    /**
     * Outcome value: target service reported a partially-degraded
     * status (e.g. one indicator {@code DOWN} but the aggregate
     * surface still serves traffic).
     */
    public static final String OUTCOME_DEGRADED = "degraded";

    /**
     * Outcome value: target service reported {@code DOWN} on
     * {@code /actuator/health}.
     */
    public static final String OUTCOME_UNHEALTHY = "unhealthy";

    /**
     * Outcome value: target instance could not be reached at all
     * (connect-refused, DNS failure, instance missing from the
     * Eureka registry).
     */
    public static final String OUTCOME_UNREACHABLE = "unreachable";

    /**
     * Outcome value: downstream returned a 4xx / unrecoverable
     * error (e.g. {@code /actuator/health} not exposed, 401).
     */
    public static final String OUTCOME_PERMANENT_FAILURE = "permanent_failure";

    /**
     * Outcome value: downstream timed out or returned a 5xx /
     * retriable error.
     */
    public static final String OUTCOME_TRANSIENT_FAILURE = "transient_failure";

    /**
     * Convenience factory for the "no action taken" verdict returned
     * by the default {@link NoopServiceHealthProbe}.
     *
     * @param reason short human-readable explanation
     * @return a {@link HealthSnapshot} with
     *         {@code backend=noop}, {@code outcome=noop}
     */
    public static HealthSnapshot noop(final String reason) {
        return new HealthSnapshot(BACKEND_NOOP, OUTCOME_NOOP,
                reason == null ? "" : reason, "");
    }

    /**
     * Convenience factory for the "target service healthy" verdict.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param detail  raw upstream context (e.g. {@code UP})
     * @return a {@link HealthSnapshot} with
     *         {@code outcome=healthy}, blank reason
     */
    public static HealthSnapshot healthy(final String backend,
                                         final String detail) {
        return new HealthSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_HEALTHY, "",
                detail == null ? "" : detail);
    }

    /**
     * Convenience factory for the "target service degraded"
     * verdict.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param detail  raw upstream context (e.g.
     *                {@code OUT_OF_SERVICE})
     * @return a {@link HealthSnapshot} with
     *         {@code outcome=degraded}
     */
    public static HealthSnapshot degraded(final String backend,
                                          final String detail) {
        return new HealthSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_DEGRADED, "",
                detail == null ? "" : detail);
    }

    /**
     * Convenience factory for the "target service unhealthy"
     * verdict.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param detail  raw upstream context (e.g. {@code DOWN})
     * @return a {@link HealthSnapshot} with
     *         {@code outcome=unhealthy}
     */
    public static HealthSnapshot unhealthy(final String backend,
                                           final String detail) {
        return new HealthSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_UNHEALTHY, "",
                detail == null ? "" : detail);
    }

    /**
     * Convenience factory for the "target instance unreachable"
     * verdict (connect-refused, DNS failure, instance missing).
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param reason  short categorical explanation, e.g.
     *                {@code eureka-actuator:connect-refused}
     * @return a {@link HealthSnapshot} with
     *         {@code outcome=unreachable}
     */
    public static HealthSnapshot unreachable(final String backend,
                                             final String reason) {
        return new HealthSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_UNREACHABLE,
                reason == null ? "" : reason, "");
    }

    /**
     * Convenience factory for a retriable downstream failure verdict
     * (5xx / 429 / IOException / timeout). Per ADR-0044 D6 the
     * probe MUST NOT throw on these; the caller decides retry policy
     * and the operator alerts on the failed-outcome metric.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param reason  short categorical explanation, e.g.
     *                {@code eureka-actuator:5xx:503} or
     *                {@code eureka-actuator:timeout}
     * @return a {@link HealthSnapshot} with
     *         {@code outcome=transient_failure}
     */
    public static HealthSnapshot transientFailure(final String backend,
                                                  final String reason) {
        return new HealthSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_TRANSIENT_FAILURE,
                reason == null ? "" : reason, "");
    }

    /**
     * Convenience factory for a non-retriable downstream failure
     * verdict (4xx other than 429, e.g. 401 unauthorised, 404
     * actuator not exposed).
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param reason  short categorical explanation, e.g.
     *                {@code eureka-actuator:4xx:404}
     * @return a {@link HealthSnapshot} with
     *         {@code outcome=permanent_failure}
     */
    public static HealthSnapshot permanentFailure(final String backend,
                                                  final String reason) {
        return new HealthSnapshot(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_PERMANENT_FAILURE,
                reason == null ? "" : reason, "");
    }
}
