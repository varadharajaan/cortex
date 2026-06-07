package io.cortex.monitoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Spring Boot entrypoint for the CORTEX log-monitoring-service (P8).
 *
 * <p>P8.0 (this commit) ships the SCAFFOLD: module compiles,
 * application context loads with Eureka + actuator + Prometheus
 * exposition + {@link io.cortex.monitoring.probe.ServiceHealthProbe}
 * SPI stub + {@link io.cortex.monitoring.metrics.MonitoringMetrics}
 * bootstrap counter wired. Real probe adapters land in P8.1+
 * behind {@code cortex.monitoring.probe.backend=eureka-actuator};
 * the SLO budget engine lands in P8.2.</p>
 *
 * <p>Registers with the local-dev Eureka registry at
 * {@code http://localhost:8761/eureka/} as application-id
 * {@code log-monitoring-service}. Production deployments use
 * Kubernetes Service DNS via the appropriate profile override.</p>
 *
 * <p>Per ADR-0044: this module aggregates cross-service health +
 * computes SLO budgets WITHOUT owning the Grafana dashboards or
 * Prometheus alert rules (that layer stays in P17). The ownership
 * boundary is enforced by the ArchUnit layer-purity test +
 * ADR-0044 contract.</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class CortexMonitoringApplication {

    /**
     * Protected constructor to discourage external instantiation while
     * allowing Spring's CGLIB proxy subclass for
     * {@code @Configuration}.
     */
    protected CortexMonitoringApplication() {
        // bootstrap class - instantiated by Spring only
    }

    /**
     * Java entrypoint.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(CortexMonitoringApplication.class, args);
    }
}
