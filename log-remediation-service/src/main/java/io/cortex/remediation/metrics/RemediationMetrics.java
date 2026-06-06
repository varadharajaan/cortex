package io.cortex.remediation.metrics;

import io.cortex.remediation.dispatch.DispatchResult;
import io.cortex.remediation.dispatch.RemediationDispatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Micrometer counter published by the log-remediation-service
 * (P6.0 / Part 17 / ADR-0032 D3; OCP-flipped P6.0a / ADR-0036).
 *
 * <p>One counter establishes the stable metric surface that Grafana
 * dashboards subscribe to from P6.0 onwards:
 * {@code cortex.remediation.dispatched_total{channel, outcome,
 * tenant_id}}. Bootstrap-registered at construct-time with all-three
 * placeholder values per LD106 + LD112 so the
 * {@code /actuator/prometheus} scrape sees the counter family before
 * the first anomaly ticks.</p>
 *
 * <p>P6.0a OCP refactor: the bootstrap loop iterates over the list
 * of {@link RemediationDispatcher} beans active in the current
 * profile (Spring injects an autowired {@code List<T>} bound by the
 * conditionals on each adapter) and bootstraps the three failable
 * outcome series for each adapter's {@link
 * RemediationDispatcher#channelId()}. Adding a new channel adapter
 * therefore requires zero edits here -- the Open/Closed Principle
 * is honoured at the bootstrap boundary as well as at the dispatch
 * boundary.</p>
 *
 * <p>Tag-key allowlist enforced per Part 17 rule: only
 * {@code channel}, {@code outcome}, and {@code tenant_id} are
 * emitted on this counter. Free-form values are bounded by the
 * {@link DispatchResult} constants
 * ({@code CHANNEL_NOOP}, {@code OUTCOME_SKIPPED}, ...).</p>
 */
@Component
@RequiredArgsConstructor
public class RemediationMetrics {

    /** The dispatched counter metric name (kept public so tests can reference it). */
    public static final String METRIC_DISPATCHED_TOTAL =
            "cortex.remediation.dispatched_total";

    /** Placeholder tag value used for the bootstrap-registration counter (LD106). */
    public static final String UNKNOWN = "unknown";

    private static final String TAG_CHANNEL = "channel";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_TENANT = "tenant_id";

    private static final String COUNTER_DESCRIPTION =
            "Anomaly events handled by RemediationDispatcher (P6.0 / ADR-0032)";

    private final MeterRegistry registry;
    private final List<RemediationDispatcher> dispatchers;

    /**
     * Bootstrap the counter family per LD106 + LD112 so the
     * {@code /actuator/prometheus} scrape sees a stable surface even
     * before the first anomaly verdict ticks.
     *
     * <p>Loops over every active {@link RemediationDispatcher} bean
     * and bootstraps the three failable outcome series for its
     * channel id. The all-{@code unknown} placeholder series is
     * always registered so the counter family is visible even when
     * the dispatcher list is empty (test fixtures).</p>
     */
    @PostConstruct
    void bootstrapMeters() {
        bootstrap(UNKNOWN, UNKNOWN);
        for (final RemediationDispatcher dispatcher : this.dispatchers) {
            final String channel = dispatcher.channelId();
            bootstrap(channel, DispatchResult.OUTCOME_DISPATCHED);
            bootstrap(channel, DispatchResult.OUTCOME_TRANSIENT_FAILURE);
            bootstrap(channel, DispatchResult.OUTCOME_PERMANENT_FAILURE);
        }
    }

    /**
     * Increment the dispatched counter for the supplied tag triple.
     * Counter series are lazy-registered by Micrometer on first
     * call; the bootstrap series with all-unknown tags remains so
     * dashboards never flatline.
     *
     * @param channel  one of the {@code DispatchResult.CHANNEL_*}
     *                 constants (e.g. {@code noop} in P6.0;
     *                 {@code slack}, {@code pagerduty}, {@code jira}
     *                 in P6.1..P6.3)
     * @param outcome  one of the {@code DispatchResult.OUTCOME_*}
     *                 constants ({@code skipped}, {@code dispatched},
     *                 {@code transient_failure},
     *                 {@code permanent_failure})
     * @param tenantId UUID of the originating tenant from the
     *                 parsed {@link io.cortex.remediation.parse.AnomalyEvent}
     */
    public void incDispatched(final String channel, final String outcome,
                              final String tenantId) {
        Counter.builder(METRIC_DISPATCHED_TOTAL)
                .description(COUNTER_DESCRIPTION)
                .tag(TAG_CHANNEL, channel == null ? UNKNOWN : channel)
                .tag(TAG_OUTCOME, outcome == null ? UNKNOWN : outcome)
                .tag(TAG_TENANT, tenantId == null ? UNKNOWN : tenantId)
                .register(this.registry)
                .increment();
    }

    private void bootstrap(final String channel, final String outcome) {
        Counter.builder(METRIC_DISPATCHED_TOTAL)
                .description(COUNTER_DESCRIPTION)
                .tag(TAG_CHANNEL, channel)
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_TENANT, UNKNOWN)
                .register(this.registry);
    }
}
