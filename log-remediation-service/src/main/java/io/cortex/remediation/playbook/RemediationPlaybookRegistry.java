package io.cortex.remediation.playbook;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Lookup registry for remediation playbooks.
 */
@Component
public class RemediationPlaybookRegistry {

    private final Map<String, RemediationPlaybook> playbooks;

    /**
     * Spring constructor.
     *
     * @param playbooks active playbook beans
     */
    public RemediationPlaybookRegistry(final List<RemediationPlaybook> playbooks) {
        this.playbooks = playbooks.stream()
                .collect(Collectors.toUnmodifiableMap(RemediationPlaybook::key,
                        Function.identity(), (left, right) -> left));
    }

    /**
     * Find a playbook by key.
     *
     * @param key lookup key
     * @return playbook when present
     */
    public Optional<RemediationPlaybook> find(final String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.playbooks.get(key));
    }
}
