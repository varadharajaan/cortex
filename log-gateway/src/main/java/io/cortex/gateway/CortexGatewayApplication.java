package io.cortex.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entrypoint for the CORTEX log-gateway service.
 *
 * <p>Boots the edge gateway: authentication, request correlation, rate
 * limiting, NL&rarr;LogQL routing, and reverse-proxy of {@code /logs/**}
 * and {@code /search/**} to downstream CORTEX services.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CortexGatewayApplication {

    /**
     * Protected constructor to discourage external instantiation while
     * allowing Spring's CGLIB proxy subclass for {@code @Configuration}.
     */
    protected CortexGatewayApplication() {
        // bootstrap class - instantiated by Spring only
    }

    /**
     * Java entrypoint.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(CortexGatewayApplication.class, args);
    }
}
