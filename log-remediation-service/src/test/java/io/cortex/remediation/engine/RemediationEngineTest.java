package io.cortex.remediation.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.remediation.dedupe.RemediationDedupeService;
import io.cortex.remediation.dispatch.DispatchResult;
import io.cortex.remediation.dispatch.RemediationDispatcher;
import io.cortex.remediation.metrics.RemediationMetrics;
import io.cortex.remediation.outcome.RemediationOutcome;
import io.cortex.remediation.outcome.RemediationOutcomePublisher;
import io.cortex.remediation.parse.AnomalyEvent;
import io.cortex.remediation.playbook.RemediationPlaybook;
import io.cortex.remediation.playbook.RemediationPlaybookRegistry;
import io.cortex.remediation.playbook.RemediationPlaybookResult;
import io.cortex.remediation.policy.RemediationPolicy;
import io.cortex.remediation.policy.RemediationPolicyService;
import io.cortex.remediation.resilience.RemediationDispatcherGuard;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for the fix-first remediation orchestrator.
 */
class RemediationEngineTest {

    /** Duplicate events must stop before policy, audit, or human fallback. */
    @Test
    void duplicateEventSkipsAllDownstreamWork() {
        final Fixture fixture = fixture();
        when(fixture.dedupe.claim(any())).thenReturn(false);

        fixture.engine.handle(event("HIGH", "restart-service"));

        verify(fixture.policyService, never()).policyFor(any());
        verify(fixture.outcomes, never()).publish(any());
        verify(fixture.guard, never()).dispatch(any(), any());
    }

    /** A successful auto-fix publishes only an audit outcome and does not page humans. */
    @Test
    void fixedPlaybookPublishesOutcomeWithoutFallback() {
        final Fixture fixture = fixture();
        final RemediationPlaybook playbook = playbook(
                RemediationPlaybookResult.fixed("dry-run-ok"),
                RemediationPlaybookResult.fixed("applied"));
        when(fixture.registry.find("restart-service")).thenReturn(Optional.of(playbook));
        when(fixture.policyService.policyFor(any())).thenReturn(
                new RemediationPolicy(true, false, true, "LOW",
                        java.util.Set.of("*")));

        fixture.engine.handle(event("HIGH", "restart-service"));

        final RemediationOutcome outcome = publishedOutcome(fixture);
        assertThat(outcome.outcome()).isEqualTo(RemediationOutcome.OUTCOME_FIXED);
        assertThat(outcome.reason()).isEqualTo("applied");
        assertThat(outcome.playbookKey()).isEqualTo("restart-service");
        verify(fixture.guard, never()).dispatch(any(), any());
        verify(fixture.metrics, never()).incDispatched(any(), any(), any());
    }

    /** Dry-run policy records a skipped dry-run and then escalates through dispatchers. */
    @Test
    void dryRunOnlyPolicyPublishesSkippedAndFallsBack() {
        final Fixture fixture = fixture();
        when(fixture.registry.find("restart-service")).thenReturn(Optional.of(playbook(
                RemediationPlaybookResult.fixed("dry-run-ok"),
                RemediationPlaybookResult.fixed("applied"))));
        when(fixture.policyService.policyFor(any())).thenReturn(
                new RemediationPolicy(true, true, false, "LOW",
                        java.util.Set.of("*")));
        when(fixture.guard.dispatch(any(), any()))
                .thenReturn(DispatchResult.dispatched("slack"));

        fixture.engine.handle(event("HIGH", "restart-service"));

        final RemediationOutcome outcome = publishedOutcome(fixture);
        assertThat(outcome.outcome()).isEqualTo(RemediationOutcome.OUTCOME_SKIPPED);
        assertThat(outcome.reason()).isEqualTo("policy:dry-run-only");
        assertThat(outcome.dryRun()).isTrue();
        verify(fixture.guard).dispatch(fixture.dispatcher,
                event("HIGH", "restart-service"));
        verify(fixture.metrics).incDispatched("slack",
                DispatchResult.OUTCOME_DISPATCHED, "tenant-a");
    }

    /** Missing playbook is a valid skipped outcome followed by fallback dispatch. */
    @Test
    void missingPlaybookPublishesSkippedAndFallsBack() {
        final Fixture fixture = fixture();
        when(fixture.registry.find("restart-service")).thenReturn(Optional.empty());
        when(fixture.guard.dispatch(any(), any()))
                .thenReturn(DispatchResult.skipped("noop"));

        fixture.engine.handle(event("HIGH", "restart-service"));

        final RemediationOutcome outcome = publishedOutcome(fixture);
        assertThat(outcome.outcome()).isEqualTo(RemediationOutcome.OUTCOME_SKIPPED);
        assertThat(outcome.reason()).isEqualTo("playbook:not-found");
        verify(fixture.metrics).incDispatched("noop",
                DispatchResult.OUTCOME_SKIPPED, "tenant-a");
    }

    /** Policy gates should stop playbook execution and still audit the decision. */
    @Test
    void severityBelowPolicyPublishesSkippedWithoutPlaybookLookup() {
        final Fixture fixture = fixture();
        when(fixture.policyService.policyFor(any())).thenReturn(
                new RemediationPolicy(true, false, true, "CRITICAL",
                        java.util.Set.of("*")));
        when(fixture.guard.dispatch(any(), any()))
                .thenReturn(DispatchResult.skipped("noop"));

        fixture.engine.handle(event("LOW", "restart-service"));

        final RemediationOutcome outcome = publishedOutcome(fixture);
        assertThat(outcome.reason()).isEqualTo("policy:severity-below-min");
        verify(fixture.registry, never()).find(any());
    }

    /** Playbook exceptions are converted to failed outcomes, then humans are escalated. */
    @Test
    void playbookApplyExceptionPublishesFailedAndFallsBack() {
        final Fixture fixture = fixture();
        final RemediationPlaybook throwing = new RemediationPlaybook() {
            @Override
            public String key() {
                return "restart-service";
            }

            @Override
            public RemediationPlaybookResult dryRun(final AnomalyEvent event) {
                return RemediationPlaybookResult.fixed("dry-run-ok");
            }

            @Override
            public RemediationPlaybookResult apply(final AnomalyEvent event) {
                throw new IllegalStateException("boom");
            }
        };
        when(fixture.registry.find("restart-service")).thenReturn(Optional.of(throwing));
        when(fixture.policyService.policyFor(any())).thenReturn(
                new RemediationPolicy(true, false, true, "LOW",
                        java.util.Set.of("*")));
        when(fixture.guard.dispatch(any(), any()))
                .thenReturn(DispatchResult.transientFailure("jira", "jira:500"));

        fixture.engine.handle(event("HIGH", "restart-service"));

        final RemediationOutcome outcome = publishedOutcome(fixture);
        assertThat(outcome.outcome()).isEqualTo(RemediationOutcome.OUTCOME_FAILED);
        assertThat(outcome.reason()).isEqualTo("playbook:apply-exception");
        verify(fixture.metrics).incDispatched("jira",
                DispatchResult.OUTCOME_TRANSIENT_FAILURE, "tenant-a");
    }

    /** Outcome publisher failure must not prevent fallback escalation. */
    @Test
    void outcomePublishFailureStillFallsBack() {
        final Fixture fixture = fixture();
        Mockito.doThrow(new IllegalStateException("kafka down"))
                .when(fixture.outcomes).publish(any());
        when(fixture.registry.find("restart-service")).thenReturn(Optional.empty());
        when(fixture.guard.dispatch(any(), any()))
                .thenReturn(DispatchResult.skipped("noop"));

        fixture.engine.handle(event("HIGH", "restart-service"));

        verify(fixture.guard).dispatch(any(), any());
        verify(fixture.metrics).incDispatched("noop",
                DispatchResult.OUTCOME_SKIPPED, "tenant-a");
    }

    private static Fixture fixture() {
        final RemediationDedupeService dedupe =
                Mockito.mock(RemediationDedupeService.class);
        when(dedupe.claim(any())).thenReturn(true);
        final RemediationPolicyService policyService =
                Mockito.mock(RemediationPolicyService.class);
        when(policyService.policyFor(any())).thenReturn(
                new RemediationPolicy(true, false, true, "LOW",
                        java.util.Set.of("*")));
        final RemediationPlaybookRegistry registry =
                Mockito.mock(RemediationPlaybookRegistry.class);
        final RemediationOutcomePublisher outcomes =
                Mockito.mock(RemediationOutcomePublisher.class);
        final RemediationDispatcher dispatcher =
                Mockito.mock(RemediationDispatcher.class);
        final RemediationDispatcherGuard guard =
                Mockito.mock(RemediationDispatcherGuard.class);
        final RemediationMetrics metrics = Mockito.mock(RemediationMetrics.class);
        final RemediationEngine engine = new RemediationEngine(Optional.of(dedupe),
                policyService, registry, outcomes, dispatcher, guard, metrics);
        return new Fixture(engine, dedupe, policyService, registry, outcomes,
                dispatcher, guard, metrics);
    }

    private static RemediationOutcome publishedOutcome(final Fixture fixture) {
        final ArgumentCaptor<RemediationOutcome> captor =
                ArgumentCaptor.forClass(RemediationOutcome.class);
        verify(fixture.outcomes).publish(captor.capture());
        return captor.getValue();
    }

    private static RemediationPlaybook playbook(
            final RemediationPlaybookResult dryRun,
            final RemediationPlaybookResult apply) {
        return new RemediationPlaybook() {
            @Override
            public String key() {
                return "restart-service";
            }

            @Override
            public RemediationPlaybookResult dryRun(final AnomalyEvent event) {
                return dryRun;
            }

            @Override
            public RemediationPlaybookResult apply(final AnomalyEvent event) {
                return apply;
            }
        };
    }

    private static AnomalyEvent event(final String severity,
                                      final String remediationKey) {
        return new AnomalyEvent("evt-1", "tenant-a", severity, "reason",
                Instant.parse("2026-06-09T00:00:00Z"), "ERROR", "checkout",
                "boom", 0.9d, "BURST", remediationKey);
    }

    private record Fixture(
            RemediationEngine engine,
            RemediationDedupeService dedupe,
            RemediationPolicyService policyService,
            RemediationPlaybookRegistry registry,
            RemediationOutcomePublisher outcomes,
            RemediationDispatcher dispatcher,
            RemediationDispatcherGuard guard,
            RemediationMetrics metrics) {
    }
}
