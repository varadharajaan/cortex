package io.cortex.monitoring.slo;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CounterFamilySloProperties} (P8.3).
 */
class CounterFamilySloPropertiesTest {

    @Test
    void canonicalPropertiesWire() {
        final CounterFamilySloProperties props =
                new CounterFamilySloProperties(Duration.ofSeconds(10),
                        "/internal/prometheus");

        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.actuatorPath()).isEqualTo("/internal/prometheus");
    }

    @Test
    void nullTimeoutCoercesToDefault() {
        final CounterFamilySloProperties props =
                new CounterFamilySloProperties(null, "/metrics");

        assertThat(props.requestTimeout())
                .isEqualTo(CounterFamilySloProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    void zeroTimeoutCoercesToDefault() {
        final CounterFamilySloProperties props =
                new CounterFamilySloProperties(Duration.ZERO, "/metrics");

        assertThat(props.requestTimeout())
                .isEqualTo(CounterFamilySloProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    void blankPathCoercesToDefault() {
        final CounterFamilySloProperties props =
                new CounterFamilySloProperties(Duration.ofSeconds(5), " ");

        assertThat(props.actuatorPath())
                .isEqualTo(CounterFamilySloProperties.DEFAULT_ACTUATOR_PATH);
    }

    @Test
    void missingLeadingSlashIsAdded() {
        final CounterFamilySloProperties props =
                new CounterFamilySloProperties(Duration.ofSeconds(5),
                        "actuator/prometheus");

        assertThat(props.actuatorPath()).isEqualTo("/actuator/prometheus");
    }
}
