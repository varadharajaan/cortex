package io.cortex.remediation.engine;

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
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Fix-first remediation orchestrator for valid anomaly events.
 */
@Service
@Slf4j
public class RemediationEngine {

    private final Optional<RemediationDedupeService> dedupeService;
    private final RemediationPolicyService policyService;
    private final RemediationPlaybookRegistry playbookRegistry;
    private final RemediationOutcomePublisher outcomePublisher;
    private final RemediationDispatcher dispatcher;
    private final RemediationDispatcherGuard dispatcherGuard;
    private final RemediationMetrics metrics;
    private final Clock clock = Clock.systemUTC();

    /**
     * Spring constructor.
     *
     * @param dedupeService optional Redis dedupe
     * @param policyService policy lookup
     * @param playbookRegistry playbook registry
     * @param outcomePublisher audit publisher
     * @param dispatcher fallback dispatcher
     * @param dispatcherGuard dispatcher guard
     * @param metrics metrics facade
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public RemediationEngine(
            final Optional<RemediationDedupeService> dedupeService,
            final RemediationPolicyService policyService,
            final RemediationPlaybookRegistry playbookRegistry,
            final RemediationOutcomePublisher outcomePublisher,
            final RemediationDispatcher dispatcher,
            final RemediationDispatcherGuard dispatcherGuard,
            final RemediationMetrics metrics) {
        this.dedupeService = dedupeService;
        this.policyService = policyService;
        this.playbookRegistry = playbookRegistry;
        this.outcomePublisher = outcomePublisher;
        this.dispatcher = dispatcher;
        this.dispatcherGuard = dispatcherGuard;
        this.metrics = metrics;
    }

    /**
     * Handle a parsed anomaly.
     *
     * @param event parsed anomaly
     */
    public void handle(final AnomalyEvent event) {
        if (this.dedupeService.isPresent() && !this.dedupeService.get().claim(event)) {
            log.info("Skipping duplicate remediation eventId={} tenantId={}",
                    event.eventId(), event.tenantId());
            return;
        }
        final RemediationOutcome outcome = decide(event);
        publishOutcome(outcome);
        if (RemediationOutcome.OUTCOME_FIXED.equals(outcome.outcome())) {
            log.info("Auto-remediation fixed eventId={} tenantId={} playbook={}",
                    event.eventId(), event.tenantId(), outcome.playbookKey());
            return;
        }
        dispatchFallback(event);
    }

    private RemediationOutcome decide(final AnomalyEvent event) {
        final RemediationPolicy policy = this.policyService.policyFor(event);
        final Instant now = this.clock.instant();
        if (!policy.enabled()) {
            return RemediationOutcome.skipped(event, "policy:disabled", "none",
                    false, now);
        }
        if (!policy.severityAllows(event)) {
            return RemediationOutcome.skipped(event, "policy:severity-below-min",
                    "none", false, now);
        }
        if (!policy.keyAllows(event)) {
            return RemediationOutcome.skipped(event, "policy:key-not-allowed",
                    event.remediationKey(), false, now);
        }
        final Optional<RemediationPlaybook> playbook =
                this.playbookRegistry.find(event.remediationKey());
        if (playbook.isEmpty()) {
            return RemediationOutcome.skipped(event, "playbook:not-found",
                    event.remediationKey(), false, now);
        }
        return runPlaybook(event, policy, playbook.get(), now);
    }

    private RemediationOutcome runPlaybook(final AnomalyEvent event,
                                           final RemediationPolicy policy,
                                           final RemediationPlaybook playbook,
                                           final Instant now) {
        final RemediationPlaybookResult dryRun = safeDryRun(playbook, event);
        if (RemediationPlaybookResult.OUTCOME_FAILED.equals(dryRun.outcome())) {
            return RemediationOutcome.failed(event, dryRun.reason(), playbook.key(), now);
        }
        if (RemediationPlaybookResult.OUTCOME_SKIPPED.equals(dryRun.outcome())) {
            return RemediationOutcome.skipped(event, dryRun.reason(), playbook.key(),
                    false, now);
        }
        if (policy.dryRunOnly() || !policy.autoApply()) {
            return RemediationOutcome.skipped(event, "policy:dry-run-only",
                    playbook.key(), true, now);
        }
        final RemediationPlaybookResult applied = safeApply(playbook, event);
        if (RemediationPlaybookResult.OUTCOME_FIXED.equals(applied.outcome())) {
            return RemediationOutcome.fixed(event, applied.reason(), playbook.key(), now);
        }
        if (RemediationPlaybookResult.OUTCOME_SKIPPED.equals(applied.outcome())) {
            return RemediationOutcome.skipped(event, applied.reason(), playbook.key(),
                    false, now);
        }
        return RemediationOutcome.failed(event, applied.reason(), playbook.key(), now);
    }

    private RemediationPlaybookResult safeDryRun(final RemediationPlaybook playbook,
                                                 final AnomalyEvent event) {
        try {
            final RemediationPlaybookResult result = playbook.dryRun(event);
            return result == null
                    ? RemediationPlaybookResult.failed("playbook:dry-run-null")
                    : result;
        } catch (RuntimeException ex) {
            log.warn("Playbook dry-run failed key={} eventId={}",
                    playbook.key(), event.eventId(), ex);
            return RemediationPlaybookResult.failed("playbook:dry-run-exception");
        }
    }

    private RemediationPlaybookResult safeApply(final RemediationPlaybook playbook,
                                                final AnomalyEvent event) {
        try {
            final RemediationPlaybookResult result = playbook.apply(event);
            return result == null
                    ? RemediationPlaybookResult.failed("playbook:apply-null")
                    : result;
        } catch (RuntimeException ex) {
            log.warn("Playbook apply failed key={} eventId={}",
                    playbook.key(), event.eventId(), ex);
            return RemediationPlaybookResult.failed("playbook:apply-exception");
        }
    }

    private void publishOutcome(final RemediationOutcome outcome) {
        try {
            this.outcomePublisher.publish(outcome);
        } catch (RuntimeException ex) {
            log.error("Failed to publish remediation outcome eventId={} outcome={}",
                    outcome.eventId(), outcome.outcome(), ex);
        }
    }

    private void dispatchFallback(final AnomalyEvent event) {
        DispatchResult result = this.dispatcherGuard.dispatch(this.dispatcher, event);
        if (result == null) {
            result = DispatchResult.skipped("dispatcher returned null");
        }
        this.metrics.incDispatched(result.channel(), result.outcome(), event.tenantId());
        log.info("Fallback dispatched eventId={} tenantId={} channel={} outcome={} reason={}",
                event.eventId(), event.tenantId(), result.channel(), result.outcome(),
                result.reason());
    }
}
