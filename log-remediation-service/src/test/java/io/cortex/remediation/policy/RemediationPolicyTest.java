package io.cortex.remediation.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for remediation policy gates.
 */
class RemediationPolicyTest {

    /** Severity comparison must honor the expected LOW..CRITICAL order. */
    @Test
    void severityAllowsEventsAtOrAboveMinimum() {
        final RemediationPolicy policy =
                new RemediationPolicy(true, true, false, "HIGH", Set.of("*"));

        assertThat(policy.severityAllows(event("CRITICAL", "key"))).isTrue();
        assertThat(policy.severityAllows(event("HIGH", "key"))).isTrue();
        assertThat(policy.severityAllows(event("MEDIUM", "key"))).isFalse();
        assertThat(policy.severityAllows(event(null, "key"))).isFalse();
    }

    /** Wildcard and explicit remediation-key allowlists must both work. */
    @Test
    void keyAllowsWildcardAndExplicitAllowlist() {
        final RemediationPolicy wildcard =
                new RemediationPolicy(true, true, false, "LOW", Set.of("*"));
        final RemediationPolicy allowlist =
                new RemediationPolicy(true, true, false, "LOW",
                        Set.of("restart-service", "scale-up"));

        assertThat(wildcard.keyAllows(event("HIGH", "anything"))).isTrue();
        assertThat(allowlist.keyAllows(event("HIGH", "restart-service"))).isTrue();
        assertThat(allowlist.keyAllows(event("HIGH", "delete-index"))).isFalse();
    }

    /** Property-backed service trims blank CSV entries into an immutable policy. */
    @Test
    void serviceParsesAllowedKeysCsv() {
        final RemediationPolicyService service = new RemediationPolicyService(
                true, false, true, "MEDIUM", "restart-service, ,scale-up");

        final RemediationPolicy policy = service.policyFor(event("HIGH", "scale-up"));

        assertThat(policy.enabled()).isTrue();
        assertThat(policy.dryRunOnly()).isFalse();
        assertThat(policy.autoApply()).isTrue();
        assertThat(policy.minSeverity()).isEqualTo("MEDIUM");
        assertThat(policy.keyAllows(event("HIGH", "scale-up"))).isTrue();
        assertThat(policy.keyAllows(event("HIGH", "unknown"))).isFalse();
    }

    private static AnomalyEvent event(final String severity,
                                      final String remediationKey) {
        return new AnomalyEvent("evt-1", "tenant-a", severity, "reason",
                Instant.parse("2026-06-09T00:00:00Z"), "ERROR", "checkout",
                "boom", 0.7d, "BURST", remediationKey);
    }
}
