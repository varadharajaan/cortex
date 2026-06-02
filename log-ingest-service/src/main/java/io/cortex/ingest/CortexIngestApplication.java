package io.cortex.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entrypoint for the CORTEX log-ingest-service (P4).
 *
 * <p>P4.0 (this commit) is the SCAFFOLD ONLY: module compiles, the
 * application context loads with Eureka + Flyway + Spring Data JDBC
 * wired but no business logic yet. POST {@code /api/v1/ingest/batch}
 * returns {@code 202 Accepted} so downstream callers can be wired up
 * end-to-end while P4.1..P4.4 add validation, dedupe, enrich, and
 * queue publish behind the same endpoint.</p>
 *
 * <p>Registers with the local-dev Eureka registry at
 * {@code http://localhost:8761/eureka/} as application-id
 * {@code log-ingest-service}. Production deployments use Kubernetes
 * Service DNS via the appropriate profile override.</p>
 *
 * <p>Mandated by O8 / ADR-0020: mTLS-ready scaffold lands day-one
 * (see {@link io.cortex.ingest.security.ServiceJwtFilter} and the
 * {@code cortex.security.mtls.*} / {@code cortex.security.service-jwt.*}
 * configuration blocks in {@code application.yml}).</p>
 *
 * <p>P4.4b / ADR-0026: {@link EnableScheduling} activates the
 * {@code @Scheduled} hooks used by {@code OutboxPoller} to drain
 * {@code outbox_events} PENDING rows into the SCSt Kafka binding
 * {@code cortexLogsEventsV1-out-0} -> {@code cortex.logs.events.v1}.</p>
 */
@SpringBootApplication
@EnableScheduling
public class CortexIngestApplication {

    /**
     * Protected constructor to discourage external instantiation while
     * allowing Spring's CGLIB proxy subclass for
     * {@code @Configuration}.
     */
    protected CortexIngestApplication() {
        // bootstrap class - instantiated by Spring only
    }

    /**
     * Java entrypoint.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(CortexIngestApplication.class, args);
    }
}
