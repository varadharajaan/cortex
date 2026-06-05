package io.cortex.remediation.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JiraProperties} (P6.3 / ADR-0035 D1).
 *
 * <p>Covers the {@code @ConfigurationProperties} record's
 * compact-constructor defensive defaults so a partially-filled
 * yml block still wires without NPE.</p>
 */
@DisplayName("JiraProperties unit tests")
class JiraPropertiesTest {

    /** All-null inputs must coerce to documented defaults. */
    @Test
    void nullArgsCoerceToDocumentedDefaults() {
        final JiraProperties props =
                new JiraProperties(null, null, null, null, null, null, null);

        assertThat(props.baseUrl()).isEmpty();
        assertThat(props.email()).isEmpty();
        assertThat(props.apiToken()).isEmpty();
        assertThat(props.requestTimeout())
                .isEqualTo(JiraProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.projectKey()).isEmpty();
        assertThat(props.issueType()).isEqualTo(JiraProperties.DEFAULT_ISSUE_TYPE);
        assertThat(props.severityLabelPrefix())
                .isEqualTo(JiraProperties.DEFAULT_SEVERITY_LABEL_PREFIX);
    }

    /** Blank string inputs for the documented-default fields must also coerce. */
    @Test
    void blankStringInputsCoerceToDocumentedDefaults() {
        final JiraProperties props = new JiraProperties(
                "", "", "", Duration.ofSeconds(7), "", "", "");

        // baseUrl / email / apiToken / projectKey stay blank
        // (blank is the unconfigured signal per ADR-0035 D1)
        assertThat(props.baseUrl()).isEmpty();
        assertThat(props.email()).isEmpty();
        assertThat(props.apiToken()).isEmpty();
        assertThat(props.projectKey()).isEmpty();
        assertThat(props.issueType()).isEqualTo(JiraProperties.DEFAULT_ISSUE_TYPE);
        assertThat(props.severityLabelPrefix())
                .isEqualTo(JiraProperties.DEFAULT_SEVERITY_LABEL_PREFIX);
    }

    /** Non-null user input must round-trip verbatim. */
    @Test
    void fullArgsRoundTripVerbatim() {
        final JiraProperties props = new JiraProperties(
                "https://cortex.atlassian.net",
                "ops@cortex.io",
                "dummy-jira-token",
                Duration.ofSeconds(7),
                "OPS",
                "Task",
                "cortex-severity");

        assertThat(props.baseUrl()).isEqualTo("https://cortex.atlassian.net");
        assertThat(props.email()).isEqualTo("ops@cortex.io");
        assertThat(props.apiToken()).isEqualTo("dummy-jira-token");
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(props.projectKey()).isEqualTo("OPS");
        assertThat(props.issueType()).isEqualTo("Task");
        assertThat(props.severityLabelPrefix()).isEqualTo("cortex-severity");
    }

    /** Default request timeout must match the public constant for downstream symmetry. */
    @Test
    void defaultRequestTimeoutIsFiveSeconds() {
        assertThat(JiraProperties.DEFAULT_REQUEST_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(5));
    }
}
