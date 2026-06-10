package io.cortex.monitoring.slo;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for advanced SLO backend property records.
 */
class AdvancedSloPropertiesTest {

    @Test
    void timerPercentilePropertiesDefaultDefensively() {
        final TimerPercentileSloProperties props =
                new TimerPercentileSloProperties(Duration.ZERO, "prometheus");

        assertThat(props.requestTimeout())
                .isEqualTo(TimerPercentileSloProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.actuatorPath()).isEqualTo("/prometheus");
    }

    @Test
    void promQlPropertiesDefaultDefensively() {
        final PromQlSloProperties props =
                new PromQlSloProperties(Duration.ofSeconds(-1), null);

        assertThat(props.requestTimeout())
                .isEqualTo(PromQlSloProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.baseUrl())
                .isEqualTo(PromQlSloProperties.DEFAULT_BASE_URL);
    }

    @Test
    void promQlPropertiesKeepCustomBaseUrl() {
        final URI baseUrl = URI.create("http://prometheus.monitoring:9090");
        final PromQlSloProperties props =
                new PromQlSloProperties(Duration.ofSeconds(7), baseUrl);

        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(props.baseUrl()).isEqualTo(baseUrl);
    }

    @Test
    void otelPropertiesDefaultDefensively() {
        final OtelSloProperties props =
                new OtelSloProperties(null, " ");

        assertThat(props.requestTimeout())
                .isEqualTo(OtelSloProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.actuatorPath())
                .isEqualTo(OtelSloProperties.DEFAULT_ACTUATOR_PATH);
    }
}
