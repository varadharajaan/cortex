package io.cortex.monitoring.probe.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit guards for {@link EurekaActuatorProperties} (P8.1).
 *
 * <p>Asserts the compact-ctor defensive defaults coerce
 * null/zero/negative {@code requestTimeout} to
 * {@link EurekaActuatorProperties#DEFAULT_REQUEST_TIMEOUT} and
 * null/blank {@code actuatorPath} to
 * {@link EurekaActuatorProperties#DEFAULT_ACTUATOR_PATH} so a
 * partially-filled yml still wires.</p>
 */
@DisplayName("EurekaActuatorProperties defensive defaults")
class EurekaActuatorPropertiesTest {

    @Test
    @DisplayName("null requestTimeout coerces to 5 s default")
    void nullRequestTimeoutCoerces() {
        final EurekaActuatorProperties props =
                new EurekaActuatorProperties(null, "/actuator/health");

        assertThat(props.requestTimeout())
                .isEqualTo(EurekaActuatorProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    @DisplayName("zero requestTimeout coerces to 5 s default")
    void zeroRequestTimeoutCoerces() {
        final EurekaActuatorProperties props =
                new EurekaActuatorProperties(Duration.ZERO, "/health");

        assertThat(props.requestTimeout())
                .isEqualTo(EurekaActuatorProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    @DisplayName("negative requestTimeout coerces to 5 s default")
    void negativeRequestTimeoutCoerces() {
        final EurekaActuatorProperties props =
                new EurekaActuatorProperties(
                        Duration.ofSeconds(-1), "/health");

        assertThat(props.requestTimeout())
                .isEqualTo(EurekaActuatorProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    @DisplayName("null actuatorPath coerces to /actuator/health default")
    void nullActuatorPathCoerces() {
        final EurekaActuatorProperties props =
                new EurekaActuatorProperties(Duration.ofSeconds(3), null);

        assertThat(props.actuatorPath())
                .isEqualTo(EurekaActuatorProperties.DEFAULT_ACTUATOR_PATH);
    }

    @Test
    @DisplayName("blank actuatorPath coerces to /actuator/health default")
    void blankActuatorPathCoerces() {
        final EurekaActuatorProperties props =
                new EurekaActuatorProperties(Duration.ofSeconds(3), "   ");

        assertThat(props.actuatorPath())
                .isEqualTo(EurekaActuatorProperties.DEFAULT_ACTUATOR_PATH);
    }

    @Test
    @DisplayName("non-default values are preserved verbatim")
    void overridesArePreserved() {
        final EurekaActuatorProperties props =
                new EurekaActuatorProperties(
                        Duration.ofSeconds(10), "/health");

        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.actuatorPath()).isEqualTo("/health");
    }
}
