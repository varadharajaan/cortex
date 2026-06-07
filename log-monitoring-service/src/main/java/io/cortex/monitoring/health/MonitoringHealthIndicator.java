package io.cortex.monitoring.health;

import io.cortex.monitoring.probe.ServiceHealthProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot {@link HealthIndicator} bound to the
 * {@code /actuator/health/monitoring} URI segment (P8.0 /
 * ADR-0044 D4).
 *
 * <p>In P8.0 reports {@code UP} unconditionally and surfaces the
 * active {@link ServiceHealthProbe#backendId()} as a detail. The
 * P8.1+ binding will aggregate the latest
 * {@link io.cortex.monitoring.probe.HealthSnapshot} per registered
 * service + flip to {@code DOWN} when any downstream is reporting
 * {@code unhealthy} or {@code unreachable} so a real outage lifts
 * the monitoring pod out of the K8s readiness pool.</p>
 *
 * <p>Aggregated into the global {@code /actuator/health} response
 * by Spring Boot's default aggregator and exposed to Kubernetes
 * via {@code /actuator/health/readiness} (per ADR-0044 D4).</p>
 */
@Component("monitoring")
@RequiredArgsConstructor
public class MonitoringHealthIndicator implements HealthIndicator {

    private static final String DETAIL_BACKEND = "backend";

    private final ServiceHealthProbe probe;

    @Override
    public Health health() {
        return Health.up()
                .withDetail(DETAIL_BACKEND, this.probe.backendId())
                .build();
    }
}
