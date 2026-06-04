package io.cortex.remediation.dispatch;

import io.cortex.remediation.parse.AnomalyEvent;

/**
 * Service Provider Interface for dispatching a remediation playbook
 * in response to an anomaly verdict consumed off
 * {@code cortex.anomalies.v1} (P6.0 / ADR-0032 D1).
 *
 * <p>Implementations decide whether + how to escalate an anomaly:
 * the default {@link NoopRemediationDispatcher} returns
 * {@link DispatchResult#skipped(String)} so the P6.0 scaffold runs
 * end-to-end without any downstream HTTP dependency (no Slack
 * webhook, no PagerDuty Events API, no Jira REST). P6.1..P6.3 will
 * land real adapters gated by
 * {@code cortex.remediation.dispatcher.provider=slack|pagerduty|jira}.</p>
 *
 * <p>Selection at runtime is driven by the {@code cortex.remediation
 * .dispatcher.provider} property + {@code @ConditionalOnProperty} on
 * each implementation. Only one dispatcher bean is active in a
 * given profile.</p>
 *
 * <p>Implementations MUST be thread-safe: the
 * {@code KafkaListenerContainerFactory} default concurrency is 1 in
 * P6.0 but will raise when fan-out lands; the dispatcher is called
 * from multiple consumer threads concurrently.</p>
 *
 * <p>Implementations MUST NOT throw on transient downstream
 * failures (e.g. Slack 429 / PagerDuty 5xx). Returning a
 * {@link DispatchResult} with {@code dispatched=false} +
 * {@code outcome="transient_failure"} ticks the failed-outcome
 * counter and lets the consumer's catch-all leave the offset moving;
 * P6.4 will add retry budgets + DLQ semantics.</p>
 */
public interface RemediationDispatcher {

    /**
     * Dispatch a remediation playbook for the supplied anomaly.
     *
     * @param event the typed anomaly event decoded from the
     *              CloudEvent envelope on {@code cortex.anomalies.v1};
     *              never {@code null} and guaranteed to have passed
     *              the {@code AnomalyEnvelopeParser} contract checks
     * @return the {@link DispatchResult} verdict for this dispatch.
     *         Implementations MUST return a non-{@code null} value;
     *         use {@link DispatchResult#skipped(String)} to signal
     *         "no action taken".
     */
    DispatchResult dispatch(AnomalyEvent event);
}
