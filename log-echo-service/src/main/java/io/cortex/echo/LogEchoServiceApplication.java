package io.cortex.echo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entrypoint for the CORTEX log-echo-service (ADR-0016).
 *
 * <p>Throwaway downstream stub. Exists only so {@code log-gateway} has
 * a real downstream to satisfy Part 21 Level 4 verification for the
 * P3.2 (rate-limit), P3.3 (AI route), and P3.4 (RouteLocator)
 * sub-phases. Deleted when {@code log-ingest-service} (P4) and
 * {@code log-query-service} take over.</p>
 *
 * <p>Registers with the local-dev Eureka registry at
 * {@code http://localhost:8761/eureka/} as application-id
 * {@code log-echo-service}.</p>
 */
@SpringBootApplication
public class LogEchoServiceApplication {

    /**
     * Protected constructor to discourage external instantiation while
     * allowing Spring's CGLIB proxy subclass for {@code @Configuration}.
     */
    protected LogEchoServiceApplication() {
        // bootstrap class - instantiated by Spring only
    }

    /**
     * Java entrypoint.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(LogEchoServiceApplication.class, args);
    }
}
