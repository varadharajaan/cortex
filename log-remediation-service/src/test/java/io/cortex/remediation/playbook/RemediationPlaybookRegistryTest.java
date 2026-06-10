package io.cortex.remediation.playbook;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.remediation.parse.AnomalyEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for playbook registry lookup semantics.
 */
class RemediationPlaybookRegistryTest {

    /** Registry finds known keys and ignores blank lookups. */
    @Test
    void findReturnsPlaybookForKnownKey() {
        final RemediationPlaybook playbook = playbook("restart-service");
        final RemediationPlaybookRegistry registry =
                new RemediationPlaybookRegistry(List.of(playbook));

        assertThat(registry.find("restart-service")).containsSame(playbook);
        assertThat(registry.find("")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("unknown")).isEmpty();
    }

    /** Duplicate keys keep the first bean so boot is deterministic. */
    @Test
    void duplicateKeysKeepFirstPlaybook() {
        final RemediationPlaybook first = playbook("restart-service");
        final RemediationPlaybook second = playbook("restart-service");
        final RemediationPlaybookRegistry registry =
                new RemediationPlaybookRegistry(List.of(first, second));

        assertThat(registry.find("restart-service")).containsSame(first);
    }

    private static RemediationPlaybook playbook(final String key) {
        return new RemediationPlaybook() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public RemediationPlaybookResult dryRun(final AnomalyEvent event) {
                return RemediationPlaybookResult.fixed("dry-run-ok");
            }

            @Override
            public RemediationPlaybookResult apply(final AnomalyEvent event) {
                return RemediationPlaybookResult.fixed("applied");
            }
        };
    }
}
