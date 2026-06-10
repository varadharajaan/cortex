package io.cortex.remediation.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for remediation outcome factories.
 */
class RemediationOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

    /** Fixed outcomes carry source anomaly fields into the audit envelope. */
    @Test
    void fixedOutcomeCopiesAnomalyFields() {
        final RemediationOutcome outcome =
                RemediationOutcome.fixed(event(), "applied", "restart-service", NOW);

        assertThat(outcome.eventId()).isEqualTo("evt-1");
        assertThat(outcome.tenantId()).isEqualTo("tenant-a");
        assertThat(outcome.severity()).isEqualTo("HIGH");
        assertThat(outcome.anomalyType()).isEqualTo("BURST");
        assertThat(outcome.remediationKey()).isEqualTo("restart-service");
        assertThat(outcome.outcome()).isEqualTo(RemediationOutcome.OUTCOME_FIXED);
        assertThat(outcome.reason()).isEqualTo("applied");
        assertThat(outcome.playbookKey()).isEqualTo("restart-service");
        assertThat(outcome.dryRun()).isFalse();
        assertThat(outcome.ts()).isEqualTo(NOW);
    }

    /** Skipped factory preserves dry-run flag and normalizes null reason/playbook. */
    @Test
    void skippedOutcomeNormalizesNulls() {
        final RemediationOutcome outcome =
                RemediationOutcome.skipped(event(), null, null, true, NOW);

        assertThat(outcome.outcome()).isEqualTo(RemediationOutcome.OUTCOME_SKIPPED);
        assertThat(outcome.reason()).isEmpty();
        assertThat(outcome.playbookKey()).isEqualTo("none");
        assertThat(outcome.dryRun()).isTrue();
    }

    /** Failed factory emits the failed outcome value. */
    @Test
    void failedOutcomeUsesFailedValue() {
        final RemediationOutcome outcome =
                RemediationOutcome.failed(event(), "apply-exception", "pb", NOW);

        assertThat(outcome.outcome()).isEqualTo(RemediationOutcome.OUTCOME_FAILED);
        assertThat(outcome.reason()).isEqualTo("apply-exception");
    }

    private static AnomalyEvent event() {
        return new AnomalyEvent("evt-1", "tenant-a", "HIGH", "reason",
                NOW, "ERROR", "checkout", "boom", 0.9d, "BURST",
                "restart-service");
    }
}
