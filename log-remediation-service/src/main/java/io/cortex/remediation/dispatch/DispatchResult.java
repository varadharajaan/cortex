package io.cortex.remediation.dispatch;

/**
 * Immutable verdict returned by a {@link RemediationDispatcher} for
 * a single {@code cortex.anomalies.v1} event (P6.0 / ADR-0032 D1).
 *
 * <p>Carries the three pieces of information the consumer needs in
 * order to bump the {@code cortex.remediation.dispatched_total}
 * counter with the right tag values: {@code dispatched} (the
 * dispatcher actually attempted a downstream call), {@code channel}
 * (which adapter handled it -- {@code slack}, {@code pagerduty},
 * {@code jira}, or {@code noop} in P6.0), {@code outcome} (the
 * coarse-grained result -- {@code dispatched}, {@code skipped},
 * {@code transient_failure}, {@code permanent_failure}).</p>
 *
 * @param dispatched {@code true} iff the dispatcher actually invoked
 *                   the downstream system (Slack / PagerDuty / Jira)
 * @param channel    one of {@code slack}, {@code pagerduty},
 *                   {@code jira}, or {@code noop}; bounded enum-like
 *                   string drives the metric tag cardinality cap
 *                   per Part 17
 * @param outcome    one of {@code dispatched}, {@code skipped},
 *                   {@code transient_failure}, {@code permanent_failure};
 *                   bounded enum-like string for the same reason
 * @param reason     short human-readable explanation, free-form;
 *                   surfaces in the consumer log line and on the
 *                   future P6.4 DLQ envelope
 */
public record DispatchResult(boolean dispatched, String channel,
                             String outcome, String reason) {

    /** Channel value emitted by the no-op dispatcher in P6.0. */
    public static final String CHANNEL_NOOP = "noop";

    /** Channel value emitted by the Slack adapter in P6.1. */
    public static final String CHANNEL_SLACK = "slack";

    /** Channel value emitted by the PagerDuty Events API v2 adapter in P6.2. */
    public static final String CHANNEL_PAGERDUTY = "pagerduty";

    /** Outcome value: dispatcher chose not to escalate this anomaly. */
    public static final String OUTCOME_SKIPPED = "skipped";

    /** Outcome value: dispatcher successfully invoked the downstream system. */
    public static final String OUTCOME_DISPATCHED = "dispatched";

    /** Outcome value: downstream returned a 4xx / unrecoverable error. */
    public static final String OUTCOME_PERMANENT_FAILURE = "permanent_failure";

    /** Outcome value: downstream timed out or returned a 5xx / retriable error. */
    public static final String OUTCOME_TRANSIENT_FAILURE = "transient_failure";

    /**
     * Convenience factory for the "no action taken" verdict returned
     * by the default {@link NoopRemediationDispatcher}.
     *
     * @param reason short human-readable explanation
     * @return a {@link DispatchResult} with
     *         {@code dispatched=false}, {@code channel=noop},
     *         {@code outcome=skipped}
     */
    public static DispatchResult skipped(final String reason) {
        return new DispatchResult(false, CHANNEL_NOOP, OUTCOME_SKIPPED,
                reason == null ? "" : reason);
    }

    /**
     * Convenience factory for the "downstream succeeded" verdict
     * (e.g. Slack returned 200 OK, PagerDuty returned 202 Accepted).
     *
     * @param channel one of the {@code CHANNEL_*} constants
     * @return a {@link DispatchResult} with {@code dispatched=true},
     *         {@code outcome=dispatched}, blank reason
     */
    public static DispatchResult dispatched(final String channel) {
        return new DispatchResult(true,
                channel == null ? CHANNEL_NOOP : channel,
                OUTCOME_DISPATCHED, "");
    }

    /**
     * Convenience factory for a retriable downstream failure verdict
     * (5xx / 429 / IOException / timeout). Per ADR-0032 D6 the
     * adapter MUST NOT throw on these; the consumer acks regardless
     * and the operator alerts on the failed-outcome metric.
     *
     * @param channel one of the {@code CHANNEL_*} constants
     * @param reason  short categorical explanation, e.g. {@code
     *                slack:500} or {@code slack:timeout}
     * @return a {@link DispatchResult} with {@code dispatched=false},
     *         {@code outcome=transient_failure}
     */
    public static DispatchResult transientFailure(final String channel,
                                                  final String reason) {
        return new DispatchResult(false,
                channel == null ? CHANNEL_NOOP : channel,
                OUTCOME_TRANSIENT_FAILURE, reason == null ? "" : reason);
    }

    /**
     * Convenience factory for a non-retriable downstream failure
     * verdict (4xx other than 429, e.g. 400 invalid body, 401
     * unauthorized, 404 webhook revoked). The consumer acks
     * regardless; the operator alerts on the failed-outcome metric.
     *
     * @param channel one of the {@code CHANNEL_*} constants
     * @param reason  short categorical explanation, e.g. {@code
     *                slack:400} or {@code slack:404}
     * @return a {@link DispatchResult} with {@code dispatched=false},
     *         {@code outcome=permanent_failure}
     */
    public static DispatchResult permanentFailure(final String channel,
                                                  final String reason) {
        return new DispatchResult(false,
                channel == null ? CHANNEL_NOOP : channel,
                OUTCOME_PERMANENT_FAILURE, reason == null ? "" : reason);
    }
}
