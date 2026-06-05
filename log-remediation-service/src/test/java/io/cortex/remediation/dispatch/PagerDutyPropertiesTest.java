package io.cortex.remediation.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PagerDutyProperties} (P6.2 / ADR-0034 D1).
 *
 * <p>Covers the {@code @ConfigurationProperties} record's
 * compact-constructor defensive defaults so a partially-filled
 * yml block still wires without NPE.</p>
 */
@DisplayName("PagerDutyProperties unit tests")
class PagerDutyPropertiesTest {

    /** All-null inputs must coerce to documented defaults. */
    @Test
    void nullArgsCoerceToDocumentedDefaults() {
        final PagerDutyProperties props =
                new PagerDutyProperties(null, null, null, null, null, null);

        assertThat(props.routingKey()).isEmpty();
        assertThat(props.requestTimeout())
                .isEqualTo(PagerDutyProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.eventsUrl())
                .isEqualTo(PagerDutyProperties.DEFAULT_EVENTS_URL);
        assertThat(props.dedupKeyTemplate())
                .isEqualTo(PagerDutyProperties.DEFAULT_DEDUP_KEY_TEMPLATE);
        assertThat(props.source()).isEqualTo(PagerDutyProperties.DEFAULT_SOURCE);
        assertThat(props.severityDefault())
                .isEqualTo(PagerDutyProperties.DEFAULT_SEVERITY);
    }

    /** Blank string inputs for the documented-default fields must also coerce. */
    @Test
    void blankStringInputsCoerceToDocumentedDefaults() {
        final PagerDutyProperties props = new PagerDutyProperties(
                "", Duration.ofSeconds(7), "", "", "", "");

        // routingKey stays blank (blank is the unconfigured signal)
        assertThat(props.routingKey()).isEmpty();
        assertThat(props.eventsUrl())
                .isEqualTo(PagerDutyProperties.DEFAULT_EVENTS_URL);
        assertThat(props.dedupKeyTemplate())
                .isEqualTo(PagerDutyProperties.DEFAULT_DEDUP_KEY_TEMPLATE);
        assertThat(props.source()).isEqualTo(PagerDutyProperties.DEFAULT_SOURCE);
        assertThat(props.severityDefault())
                .isEqualTo(PagerDutyProperties.DEFAULT_SEVERITY);
    }

    /** Non-null user input must round-trip verbatim. */
    @Test
    void fullArgsRoundTripVerbatim() {
        final PagerDutyProperties props = new PagerDutyProperties(
                "00000000000000000000000000000000",
                Duration.ofSeconds(7),
                "https://events.eu.pagerduty.com/v2/enqueue",
                "tenant-{tenantId}",
                "cortex-prod",
                "critical");

        assertThat(props.routingKey())
                .isEqualTo("00000000000000000000000000000000");
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(props.eventsUrl())
                .isEqualTo("https://events.eu.pagerduty.com/v2/enqueue");
        assertThat(props.dedupKeyTemplate()).isEqualTo("tenant-{tenantId}");
        assertThat(props.source()).isEqualTo("cortex-prod");
        assertThat(props.severityDefault()).isEqualTo("critical");
    }

    /** Default request timeout must match the public constant for downstream symmetry. */
    @Test
    void defaultRequestTimeoutIsFiveSeconds() {
        assertThat(PagerDutyProperties.DEFAULT_REQUEST_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(5));
    }
}
