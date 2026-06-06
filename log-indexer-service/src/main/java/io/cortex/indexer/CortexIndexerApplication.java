package io.cortex.indexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Spring Boot entrypoint for the CORTEX log-indexer-service (P7).
 *
 * <p>P7.0 (this commit) ships the SCAFFOLD: module compiles,
 * application context loads with Eureka + actuator + Prometheus
 * exposition + {@link io.cortex.indexer.admin.QuickwitIndexAdmin}
 * SPI stub + {@link io.cortex.indexer.metrics.IndexerMetrics}
 * bootstrap counter wired. Real Quickwit HTTP admin lands in
 * P7.1..P7.4 behind {@code cortex.indexer.admin.backend=quickwit}.</p>
 *
 * <p>Registers with the local-dev Eureka registry at
 * {@code http://localhost:8761/eureka/} as application-id
 * {@code log-indexer-service}. Production deployments use
 * Kubernetes Service DNS via the appropriate profile override.</p>
 *
 * <p>Per LD3 + ADR-0038: this module is the sole owner of Quickwit
 * administration (create / drop indexes + retention enforcement +
 * per-tenant cardinality budgets + future search-side proxy).
 * {@code log-processor-service} P5.3 owns the writer side
 * ({@code QuickwitSink}); this service owns the lifecycle admin
 * side. The ownership boundary is enforced by the ArchUnit
 * layer-purity test + ADR-0038 contract.</p>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class CortexIndexerApplication {

    /**
     * Protected constructor to discourage external instantiation while
     * allowing Spring's CGLIB proxy subclass for
     * {@code @Configuration}.
     */
    protected CortexIndexerApplication() {
        // bootstrap class - instantiated by Spring only
    }

    /**
     * Java entrypoint.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(CortexIndexerApplication.class, args);
    }
}
