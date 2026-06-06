package io.cortex.indexer.health;

import io.cortex.indexer.admin.QuickwitIndexAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Spring Boot {@link HealthIndicator} bound to the
 * {@code /actuator/health/quickwit} URI segment (P7.0 / ADR-0038 D4).
 *
 * <p>In P7.0 reports {@code UP} unconditionally and surfaces the
 * active {@link QuickwitIndexAdmin#backendId()} as a detail. The
 * P7.1+ binding will probe the real Quickwit
 * {@code /api/v1/health} endpoint via the bound HTTP admin client +
 * surface backend latency + last-error timestamp.</p>
 *
 * <p>Aggregated into the global {@code /actuator/health} response
 * by Spring Boot's default aggregator and exposed to Kubernetes via
 * {@code /actuator/health/readiness} so a Quickwit outage flips
 * readiness to NOT_READY and lifts indexer pods out of the gateway
 * routing pool (per ADR-0038 D4).</p>
 */
@Component("quickwit")
@RequiredArgsConstructor
public class QuickwitHealthIndicator implements HealthIndicator {

    private static final String DETAIL_BACKEND = "backend";

    private final QuickwitIndexAdmin admin;

    @Override
    public Health health() {
        return Health.up()
                .withDetail(DETAIL_BACKEND, this.admin.backendId())
                .build();
    }
}
