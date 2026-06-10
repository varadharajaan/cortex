package io.cortex.remediation.outcome;

import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Instant;

/**
 * Audit event emitted for every valid remediation decision.
 *
 * @param eventId source anomaly event id
 * @param tenantId source tenant id
 * @param severity anomaly severity
 * @param anomalyType classifier anomaly type
 * @param remediationKey requested remediation key
 * @param outcome fixed, skipped, or failed
 * @param reason short categorical reason
 * @param playbookKey playbook key used for the decision
 * @param dryRun whether the decision stopped after dry-run
 * @param ts UTC outcome timestamp
 */
public record RemediationOutcome(
        String eventId,
        String tenantId,
        String severity,
        String anomalyType,
        String remediationKey,
        String outcome,
        String reason,
        String playbookKey,
        boolean dryRun,
        Instant ts) {

    /** Outcome value for a successful auto-fix. */
    public static final String OUTCOME_FIXED = "fixed";

    /** Outcome value for a valid anomaly that did not auto-fix. */
    public static final String OUTCOME_SKIPPED = "skipped";

    /** Outcome value for a failed playbook attempt. */
    public static final String OUTCOME_FAILED = "failed";

    /**
     * Build a fixed outcome.
     *
     * @param event source anomaly
     * @param reason reason
     * @param playbookKey playbook key
     * @param now timestamp
     * @return outcome
     */
    public static RemediationOutcome fixed(final AnomalyEvent event,
                                           final String reason,
                                           final String playbookKey,
                                           final Instant now) {
        return of(event, OUTCOME_FIXED, reason, playbookKey, false, now);
    }

    /**
     * Build a skipped outcome.
     *
     * @param event source anomaly
     * @param reason reason
     * @param playbookKey playbook key
     * @param dryRun dry-run flag
     * @param now timestamp
     * @return outcome
     */
    public static RemediationOutcome skipped(final AnomalyEvent event,
                                             final String reason,
                                             final String playbookKey,
                                             final boolean dryRun,
                                             final Instant now) {
        return of(event, OUTCOME_SKIPPED, reason, playbookKey, dryRun, now);
    }

    /**
     * Build a failed outcome.
     *
     * @param event source anomaly
     * @param reason reason
     * @param playbookKey playbook key
     * @param now timestamp
     * @return outcome
     */
    public static RemediationOutcome failed(final AnomalyEvent event,
                                            final String reason,
                                            final String playbookKey,
                                            final Instant now) {
        return of(event, OUTCOME_FAILED, reason, playbookKey, false, now);
    }

    private static RemediationOutcome of(final AnomalyEvent event,
                                         final String outcome,
                                         final String reason,
                                         final String playbookKey,
                                         final boolean dryRun,
                                         final Instant now) {
        return new RemediationOutcome(event.eventId(), event.tenantId(),
                event.severity(), event.anomalyType(), event.remediationKey(),
                outcome, reason == null ? "" : reason,
                playbookKey == null ? "none" : playbookKey, dryRun, now);
    }
}
