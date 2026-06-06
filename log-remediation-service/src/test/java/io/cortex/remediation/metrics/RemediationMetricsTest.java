package io.cortex.remediation.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.remediation.dispatch.DispatchResult;
import io.cortex.remediation.dispatch.RemediationDispatcher;
import io.cortex.remediation.parse.AnomalyEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RemediationMetrics} (P6.0 / LD106 + LD112;
 * P6.0a / ADR-0036 OCP refactor).
 *
 * <p>Assertions:</p>
 * <ul>
 *   <li>The {@code cortex.remediation.dispatched_total} counter
 *       family is bootstrap-registered with the three-{@code unknown}
 *       tag placeholder.</li>
 *   <li>For every {@link RemediationDispatcher} bean active in the
 *       profile, the three failable outcome series
 *       ({@code dispatched}, {@code transient_failure},
 *       {@code permanent_failure}) bootstrap with
 *       {@code tenant_id=unknown}.</li>
 *   <li>{@link RemediationMetrics#incDispatched(String, String, String)}
 *       registers a new series for non-{@code unknown} tag values
 *       and increments it without overwriting the bootstrap
 *       series.</li>
 *   <li>Null tag values fall back to the bootstrap {@code unknown}
 *       placeholder so tag cardinality stays bounded.</li>
 * </ul>
 *
 * <p>The package-private {@link RemediationMetrics#bootstrapMeters()}
 * method is invoked explicitly here because these tests do not run
 * inside the Spring container and the {@code @PostConstruct} lifecycle
 * does not fire on a plain {@code new} construction.</p>
 */
class RemediationMetricsTest {

    /**
     * Construction must publish the counter family with all-three-{@code unknown}
     * tags so the scrape sees it before the first anomaly (LD106 + LD112).
     */
    @Test
    void bootstrapRegistersDispatchedCounterWithUnknownTags() {
        final MeterRegistry registry = new SimpleMeterRegistry();

        final RemediationMetrics metrics = new RemediationMetrics(registry, List.of());
        metrics.bootstrapMeters();

        final Counter bootstrap = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", RemediationMetrics.UNKNOWN)
                .tag("outcome", RemediationMetrics.UNKNOWN)
                .tag("tenant_id", RemediationMetrics.UNKNOWN)
                .counter();
        assertThat(bootstrap)
                .as("bootstrap counter with all-unknown tags must exist after construct")
                .isNotNull();
        assertThat(bootstrap.count()).isZero();
    }

    /** A real {@code incDispatched} call must register a new timeseries without disturbing the bootstrap one. */
    @Test
    void incDispatchedRegistersDistinctSeries() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry, List.of());
        metrics.bootstrapMeters();

        metrics.incDispatched("noop", "skipped", "tenant-a");

        final Counter dispatched = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", "noop")
                .tag("outcome", "skipped")
                .tag("tenant_id", "tenant-a")
                .counter();
        assertThat(dispatched).isNotNull();
        assertThat(dispatched.count()).isEqualTo(1.0d);

        // Bootstrap series untouched by the real tick.
        final Counter bootstrap = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", RemediationMetrics.UNKNOWN)
                .tag("outcome", RemediationMetrics.UNKNOWN)
                .tag("tenant_id", RemediationMetrics.UNKNOWN)
                .counter();
        assertThat(bootstrap).isNotNull();
        assertThat(bootstrap.count()).isZero();
    }

    /**
     * Null tag arguments must coerce to {@link RemediationMetrics#UNKNOWN}
     * so the timeseries cardinality stays bounded.
     */
    @Test
    void incDispatchedFallsBackToUnknownOnNullTags() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry, List.of());
        metrics.bootstrapMeters();

        metrics.incDispatched(null, null, null);

        final Counter bootstrap = registry.find(RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                .tag("channel", RemediationMetrics.UNKNOWN)
                .tag("outcome", RemediationMetrics.UNKNOWN)
                .tag("tenant_id", RemediationMetrics.UNKNOWN)
                .counter();
        assertThat(bootstrap).isNotNull();
        assertThat(bootstrap.count()).isEqualTo(1.0d);
    }

    /**
     * P6.1 / ADR-0033: bootstrap MUST register all three Slack outcome
     * series with {@code tenant_id=unknown} so the Prometheus scrape
     * sees the full Slack outcome surface even before the first
     * anomaly hits the Slack adapter.
     */
    @Test
    void bootstrapRegistersAllThreeSlackOutcomeSeries() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry,
                List.of(fakeDispatcher(DispatchResult.CHANNEL_SLACK)));
        metrics.bootstrapMeters();

        for (final String outcome : new String[] {
                DispatchResult.OUTCOME_DISPATCHED,
                DispatchResult.OUTCOME_TRANSIENT_FAILURE,
                DispatchResult.OUTCOME_PERMANENT_FAILURE}) {
            final Counter slack = registry.find(
                            RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                    .tag("channel", DispatchResult.CHANNEL_SLACK)
                    .tag("outcome", outcome)
                    .tag("tenant_id", RemediationMetrics.UNKNOWN)
                    .counter();
            assertThat(slack)
                    .as("Slack outcome=%s bootstrap counter must exist", outcome)
                    .isNotNull();
            assertThat(slack.count()).isZero();
        }
    }

    /**
     * P6.2 / ADR-0034: bootstrap MUST register all three PagerDuty
     * outcome series with {@code tenant_id=unknown} so the Prometheus
     * scrape sees the full PagerDuty outcome surface even before the
     * first anomaly hits the PagerDuty adapter.
     */
    @Test
    void bootstrapRegistersAllThreePagerDutyOutcomeSeries() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry,
                List.of(fakeDispatcher(DispatchResult.CHANNEL_PAGERDUTY)));
        metrics.bootstrapMeters();

        for (final String outcome : new String[] {
                DispatchResult.OUTCOME_DISPATCHED,
                DispatchResult.OUTCOME_TRANSIENT_FAILURE,
                DispatchResult.OUTCOME_PERMANENT_FAILURE}) {
            final Counter pd = registry.find(
                            RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                    .tag("channel", DispatchResult.CHANNEL_PAGERDUTY)
                    .tag("outcome", outcome)
                    .tag("tenant_id", RemediationMetrics.UNKNOWN)
                    .counter();
            assertThat(pd)
                    .as("PagerDuty outcome=%s bootstrap counter must exist", outcome)
                    .isNotNull();
            assertThat(pd.count()).isZero();
        }
    }

    /**
     * P6.3 / ADR-0035: bootstrap MUST register all three Jira
     * outcome series with {@code tenant_id=unknown} so the Prometheus
     * scrape sees the full Jira outcome surface even before the
     * first anomaly hits the Jira adapter.
     */
    @Test
    void bootstrapRegistersAllThreeJiraOutcomeSeries() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry,
                List.of(fakeDispatcher(DispatchResult.CHANNEL_JIRA)));
        metrics.bootstrapMeters();

        for (final String outcome : new String[] {
                DispatchResult.OUTCOME_DISPATCHED,
                DispatchResult.OUTCOME_TRANSIENT_FAILURE,
                DispatchResult.OUTCOME_PERMANENT_FAILURE}) {
            final Counter jira = registry.find(
                            RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                    .tag("channel", DispatchResult.CHANNEL_JIRA)
                    .tag("outcome", outcome)
                    .tag("tenant_id", RemediationMetrics.UNKNOWN)
                    .counter();
            assertThat(jira)
                    .as("Jira outcome=%s bootstrap counter must exist", outcome)
                    .isNotNull();
            assertThat(jira.count()).isZero();
        }
    }

    /**
     * P6.0a / ADR-0036: bootstrap MUST iterate over multiple
     * dispatchers in a single profile when a future fan-out
     * configuration is introduced -- this test pins the OCP-flipped
     * loop semantics so a regression to the old hand-coded bootstrap
     * is impossible.
     */
    @Test
    void bootstrapIteratesOverMultipleDispatchers() {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final RemediationMetrics metrics = new RemediationMetrics(registry,
                List.of(fakeDispatcher(DispatchResult.CHANNEL_SLACK),
                        fakeDispatcher(DispatchResult.CHANNEL_PAGERDUTY),
                        fakeDispatcher(DispatchResult.CHANNEL_JIRA)));
        metrics.bootstrapMeters();

        for (final String channel : new String[] {
                DispatchResult.CHANNEL_SLACK,
                DispatchResult.CHANNEL_PAGERDUTY,
                DispatchResult.CHANNEL_JIRA}) {
            for (final String outcome : new String[] {
                    DispatchResult.OUTCOME_DISPATCHED,
                    DispatchResult.OUTCOME_TRANSIENT_FAILURE,
                    DispatchResult.OUTCOME_PERMANENT_FAILURE}) {
                final Counter c = registry.find(
                                RemediationMetrics.METRIC_DISPATCHED_TOTAL)
                        .tag("channel", channel)
                        .tag("outcome", outcome)
                        .tag("tenant_id", RemediationMetrics.UNKNOWN)
                        .counter();
                assertThat(c)
                        .as("channel=%s outcome=%s bootstrap counter must exist",
                                channel, outcome)
                        .isNotNull();
            }
        }
    }

    private static RemediationDispatcher fakeDispatcher(final String channelId) {
        return new RemediationDispatcher() {
            @Override
            public String channelId() {
                return channelId;
            }

            @Override
            public DispatchResult dispatch(final AnomalyEvent event) {
                return DispatchResult.skipped("test");
            }
        };
    }
}
