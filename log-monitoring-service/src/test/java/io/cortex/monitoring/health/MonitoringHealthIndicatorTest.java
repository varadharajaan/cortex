package io.cortex.monitoring.health;

import io.cortex.monitoring.probe.NoopServiceHealthProbe;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MonitoringHealthIndicator} (P8.0).
 *
 * <p>Verifies the indicator reports {@code UP} unconditionally and
 * surfaces the active probe backend id as the {@code backend}
 * detail.</p>
 */
class MonitoringHealthIndicatorTest {

    @Test
    void reportsUpWithNoopBackendDetail() {
        final MonitoringHealthIndicator indicator =
                new MonitoringHealthIndicator(new NoopServiceHealthProbe());
        final Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("backend", "noop");
    }
}
