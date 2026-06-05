package io.cortex.remediation.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SlackProperties} (P6.1 / ADR-0033 D1).
 *
 * <p>Covers the {@code @ConfigurationProperties} record's
 * compact-constructor defensive defaults so a partially-filled
 * yml block still wires without NPE.</p>
 */
@DisplayName("SlackProperties unit tests")
class SlackPropertiesTest {

    /** All-null inputs must coerce to documented defaults (blank URL + 5s + blank user + blank channel). */
    @Test
    void nullArgsCoerceToDocumentedDefaults() {
        final SlackProperties props = new SlackProperties(null, null, null, null);

        assertThat(props.webhookUrl()).isEmpty();
        assertThat(props.requestTimeout())
                .isEqualTo(SlackProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.username()).isEmpty();
        assertThat(props.channelOverride()).isEmpty();
    }

    /** Non-null user input must round-trip verbatim. */
    @Test
    void fullArgsRoundTripVerbatim() {
        final SlackProperties props = new SlackProperties(
                "https://hooks.slack.com/services/T/B/X",
                Duration.ofSeconds(7),
                "cortex-remediation",
                "#sre-incidents");

        assertThat(props.webhookUrl())
                .isEqualTo("https://hooks.slack.com/services/T/B/X");
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(props.username()).isEqualTo("cortex-remediation");
        assertThat(props.channelOverride()).isEqualTo("#sre-incidents");
    }

    /** Default request timeout must match the public constant for downstream symmetry. */
    @Test
    void defaultRequestTimeoutIsFiveSeconds() {
        assertThat(SlackProperties.DEFAULT_REQUEST_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(5));
    }
}
