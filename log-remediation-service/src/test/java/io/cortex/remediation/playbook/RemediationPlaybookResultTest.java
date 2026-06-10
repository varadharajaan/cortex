package io.cortex.remediation.playbook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for playbook result factories.
 */
class RemediationPlaybookResultTest {

    /** Factory helpers must publish bounded outcome strings. */
    @Test
    void factoriesUseBoundedOutcomeValues() {
        assertThat(RemediationPlaybookResult.fixed("done").outcome())
                .isEqualTo(RemediationPlaybookResult.OUTCOME_FIXED);
        assertThat(RemediationPlaybookResult.skipped("skip").outcome())
                .isEqualTo(RemediationPlaybookResult.OUTCOME_SKIPPED);
        assertThat(RemediationPlaybookResult.failed("fail").outcome())
                .isEqualTo(RemediationPlaybookResult.OUTCOME_FAILED);
    }

    /** Null reasons are normalized to blank so outcome audit is stable. */
    @Test
    void nullReasonBecomesBlank() {
        assertThat(RemediationPlaybookResult.fixed(null).reason()).isEmpty();
        assertThat(RemediationPlaybookResult.skipped(null).reason()).isEmpty();
        assertThat(RemediationPlaybookResult.failed(null).reason()).isEmpty();
    }
}
