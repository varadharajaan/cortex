package io.cortex.remediation.policy;

import io.cortex.remediation.parse.AnomalyEvent;
import java.util.Locale;
import java.util.Set;

/**
 * Tenant-scoped remediation policy snapshot.
 *
 * @param enabled whether auto-remediation is enabled
 * @param dryRunOnly whether dry-run should prevent apply
 * @param autoApply whether apply is allowed
 * @param minSeverity minimum severity that can be remediated
 * @param allowedRemediationKeys allowed remediation keys
 */
public record RemediationPolicy(
        boolean enabled,
        boolean dryRunOnly,
        boolean autoApply,
        String minSeverity,
        Set<String> allowedRemediationKeys) {

    /**
     * Check severity gate.
     *
     * @param event anomaly event
     * @return true when severity is allowed
     */
    public boolean severityAllows(final AnomalyEvent event) {
        return rank(event.severity()) >= rank(this.minSeverity);
    }

    /**
     * Check playbook key allowlist.
     *
     * @param event anomaly event
     * @return true when key is allowed
     */
    public boolean keyAllows(final AnomalyEvent event) {
        return this.allowedRemediationKeys.contains("*")
                || this.allowedRemediationKeys.contains(event.remediationKey());
    }

    private static int rank(final String severity) {
        final String normalized = severity == null
                ? ""
                : severity.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            default -> 1;
        };
    }
}
