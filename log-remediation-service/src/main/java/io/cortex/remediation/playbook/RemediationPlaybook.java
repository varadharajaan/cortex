package io.cortex.remediation.playbook;

import io.cortex.remediation.parse.AnomalyEvent;

/**
 * Deterministic remediation playbook SPI.
 */
public interface RemediationPlaybook {

    /**
     * Stable lookup key.
     *
     * @return playbook key
     */
    String key();

    /**
     * Validate whether the playbook can act on this anomaly.
     *
     * @param event anomaly event
     * @return dry-run result
     */
    RemediationPlaybookResult dryRun(AnomalyEvent event);

    /**
     * Apply the playbook.
     *
     * @param event anomaly event
     * @return apply result
     */
    RemediationPlaybookResult apply(AnomalyEvent event);
}
