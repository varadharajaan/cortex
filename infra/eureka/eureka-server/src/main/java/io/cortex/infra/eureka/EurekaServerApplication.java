package io.cortex.infra.eureka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Spring Boot entrypoint for the CORTEX standalone Eureka registry
 * (local-dev only, ADR-0016).
 *
 * <p>This process is NOT a Cortex production artifact: it is a
 * throwaway local-dev discovery registry that lets `log-gateway`
 * resolve `lb://log-echo-service` predicates without a Kubernetes
 * control plane. Replaced by Kubernetes `Service` DNS in prod
 * (Part 23.3).</p>
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerApplication {

    /**
     * Protected constructor to discourage external instantiation while
     * allowing Spring's CGLIB proxy subclass for {@code @Configuration}.
     */
    protected EurekaServerApplication() {
        // bootstrap class - instantiated by Spring only
    }

    /**
     * Java entrypoint.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }
}
