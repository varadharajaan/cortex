package io.cortex.remediation.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counter published by the log-remediation-service
 * (P6.0 / Part 17 / ADR-0032 D3).
 *
 * <p>One counter establishes the stable metric surface that
 * Grafana dashboards subscribe to from P6.0 onwards:
 * {@code cortex.remediation.dispatched_total{channel, outcome,
 * tenant_id}}. Bootstrap-registered at construct-time with all-three
 * placeholder values per LD106 + LD112 so the
 * {@code /actuator/prometheus} scrape sees the counter family before
 * the first anomaly ticks. The actual ticks during the consumer
 * pipeline use the {@code channel} + {@code outcome} from the
 * dispatcher's {@link io.cortex.remediation.dispatch.DispatchResult}
 * (e.g. {@code channel=noop, outcome=skipped} in P6.0;
 * {@code channel=slack, outcome=dispatched} when the Slack adapter
 * lands in P6.1) and the {@code tenantId} from the parsed
 * {@link io.cortex.remediation.parse.AnomalyEvent}.</p>
 *
 * <p>Tag-key allowlist enforced per Part 17 rule: only {@code
 * channel}, {@code outcome}, and {@code tenant_id} are emitted on
 * this counter. Free-form values are bounded by the
 * {@link io.cortex.remediation.dispatch.DispatchResult} constants
 * ({@code CHANNEL_NOOP}, {@code OUTCOME_SKIPPED}, ...).</p>
 *
 * <p>Bean name is the default {@code remediationMetrics} (no
 * collision risk with Spring Boot autoconfig in this module).</p>
 */
@Component
public class RemediationMetrics {

    private static final String TAG_CHANNEL = "channel";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_TENANT = "tenant_id";

    /** The dispatched counter metric name (kept public so tests can reference it). */
    public static final String METRIC_DISPATCHED_TOTAL =
            "cortex.remediation.dispatched_total";

    /** Placeholder tag value used for the bootstrap-registration counter (LD106). */
    public static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    /**
     * Spring constructor.
     *
     * @param registry the autoconfigured Micrometer registry (Prometheus)
     */
    public RemediationMetrics(final MeterRegistry registry) {
        this.registry = registry;
        // Bootstrap the counter family per LD106 + LD112 so the
        // /actuator/prometheus scrape sees a stable surface even
        // before the first anomaly verdict ticks. All three tag
        // values are "unknown" so the bootstrap series stays
        // unambiguously distinct from real tag combinations.
        bootstrap(UNKNOWN, UNKNOWN);
        // P6.1: bootstrap the three Slack outcome series so the
        // scrape sees every Slack outcome row even before the
        // first anomaly hits the Slack adapter. tenant_id stays
        // "unknown" -- the real series for a given tenant lazy-
        // registers on first tick per LD106 cardinality rules.
        bootstrap("slack", "dispatched");
        bootstrap("slack", "transient_failure");
        bootstrap("slack", "permanent_failure");
    }

    /**
     * Register a single bootstrap counter series (count = 0) for
     * the supplied {@code channel} + {@code outcome} pair with
     * {@code tenant_id=unknown}. Counters are idempotent in
     * Micrometer -- the production {@code incDispatched(...)} ticks
     * re-use the same meter when the tag tuple matches.
     *
     * @param channel one of the {@code DispatchResult.CHANNEL_*} constants
     * @param outcome one of the {@code DispatchResult.OUTCOME_*} constants
     */
    private void bootstrap(final String channel, final String outcome) {
        Counter.builder(METRIC_DISPATCHED_TOTAL)
                .description("Anomaly events handled by RemediationDispatcher"
                        + " (P6.0 / ADR-0032)")
                .tag(TAG_CHANNEL, channel)
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_TENANT, UNKNOWN)
                .register(this.registry);
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
                .description("Anomaly events handled by RemediationDispatcher"
                        + " (P6.0 / ADR-0032)")
                .tag(TAG_CHANNEL, channel == null ? UNKNOWN : channel)
                .tag(TAG_OUTCOME, outcome == null ? UNKNOWN : outcome)
                .tag(TAG_TENANT, tenantId == null ? UNKNOWN : tenantId)
                .register(this.registry)
                .increment();
    }
}
