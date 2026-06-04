package io.cortex.remediation.dispatch;

import io.cortex.remediation.parse.AnomalyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link RemediationDispatcher} implementation that returns
 * {@link DispatchResult#skipped(String)} for every event (P6.0 /
 * ADR-0032 D1).
 *
 * <p>The scaffold runs end-to-end without any downstream HTTP
 * dependency: the consumer parses the envelope, hands the typed
 * {@link AnomalyEvent} to this no-op, and the
 * {@code outcome=skipped, channel=noop} verdict ticks the
 * {@code cortex.remediation.dispatched_total} counter with bounded
 * tag values. P6.1..P6.3 swap the bean implementation behind
 * {@code cortex.remediation.dispatcher.provider=slack|pagerduty|jira}.</p>
 *
 * <p>Gated by {@code cortex.remediation.dispatcher.provider=noop}
 * ({@code matchIfMissing=true}), so it's the default in every
 * profile until P6.1 introduces the first real adapter.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "noop",
        matchIfMissing = true)
public class NoopRemediationDispatcher implements RemediationDispatcher {

    /** Reason stamped on every skipped verdict from this scaffold dispatcher. */
    private static final String SKIP_REASON =
            "noop dispatcher (P6.0 scaffold); real adapters land in P6.1..P6.3";

    @Override
    public DispatchResult dispatch(final AnomalyEvent event) {
        return DispatchResult.skipped(SKIP_REASON);
    }
}
