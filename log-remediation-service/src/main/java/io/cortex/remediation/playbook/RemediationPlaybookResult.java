package io.cortex.remediation.playbook;

/**
 * Result from a deterministic remediation playbook.
 *
 * @param outcome fixed, skipped, or failed
 * @param reason short categorical reason
 */
public record RemediationPlaybookResult(String outcome, String reason) {

    /** Playbook fixed the anomaly. */
    public static final String OUTCOME_FIXED = "fixed";

    /** Playbook intentionally skipped the anomaly. */
    public static final String OUTCOME_SKIPPED = "skipped";

    /** Playbook failed to handle the anomaly. */
    public static final String OUTCOME_FAILED = "failed";

    /**
     * Fixed factory.
     *
     * @param reason reason
     * @return result
     */
    public static RemediationPlaybookResult fixed(final String reason) {
        return new RemediationPlaybookResult(OUTCOME_FIXED, safe(reason));
    }

    /**
     * Skipped factory.
     *
     * @param reason reason
     * @return result
     */
    public static RemediationPlaybookResult skipped(final String reason) {
        return new RemediationPlaybookResult(OUTCOME_SKIPPED, safe(reason));
    }

    /**
     * Failed factory.
     *
     * @param reason reason
     * @return result
     */
    public static RemediationPlaybookResult failed(final String reason) {
        return new RemediationPlaybookResult(OUTCOME_FAILED, safe(reason));
    }

    private static String safe(final String reason) {
        return reason == null ? "" : reason;
    }
}
