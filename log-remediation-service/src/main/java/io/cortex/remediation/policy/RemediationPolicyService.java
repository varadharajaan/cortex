package io.cortex.remediation.policy;

import io.cortex.remediation.parse.AnomalyEvent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Property-backed tenant policy lookup.
 */
@Service
public class RemediationPolicyService {

    private final RemediationPolicy defaultPolicy;

    /**
     * Spring constructor.
     *
     * @param enabled enable flag
     * @param dryRunOnly dry-run-only flag
     * @param autoApply apply flag
     * @param minSeverity minimum severity
     * @param allowedKeys comma-separated allowed keys
     */
    public RemediationPolicyService(
            @Value("${cortex.remediation.policy.enabled:true}") final boolean enabled,
            @Value("${cortex.remediation.policy.dry-run-only:true}") final boolean dryRunOnly,
            @Value("${cortex.remediation.policy.auto-apply:false}") final boolean autoApply,
            @Value("${cortex.remediation.policy.min-severity:HIGH}") final String minSeverity,
            @Value("${cortex.remediation.policy.allowed-remediation-keys:*}")
            final String allowedKeys) {
        this.defaultPolicy = new RemediationPolicy(enabled, dryRunOnly, autoApply,
                minSeverity, parseKeys(allowedKeys));
    }

    /**
     * Resolve policy for the event's tenant.
     *
     * @param event anomaly event
     * @return policy
     */
    public RemediationPolicy policyFor(final AnomalyEvent event) {
        return this.defaultPolicy;
    }

    private static Set<String> parseKeys(final String csv) {
        return Arrays.stream((csv == null ? "*" : csv).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
