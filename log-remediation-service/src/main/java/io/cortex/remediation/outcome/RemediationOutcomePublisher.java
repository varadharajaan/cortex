package io.cortex.remediation.outcome;

/**
 * Publishes remediation outcome audit events.
 */
public interface RemediationOutcomePublisher {

    /**
     * Publish an outcome.
     *
     * @param outcome outcome to publish
     */
    void publish(RemediationOutcome outcome);
}
