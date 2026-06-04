package io.cortex.remediation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Spring Boot entrypoint for the CORTEX log-remediation-service (P6).
 *
 * <p>P6.0 (this commit) ships the SCAFFOLD + first production code
 * path: module compiles, application context loads with Eureka +
 * Spring for Apache Kafka consumer + RemediationDispatcher SPI stub
 * + Micrometer counter wired.
 * {@link io.cortex.remediation.consume.AnomalyConsumer @KafkaListener}
 * binds to topic {@code cortex.anomalies.v1} (CloudEvents 1.0
 * structured-mode JSON envelopes shipped by
 * {@code log-processor-service} P5.4 per ADR-0031), decodes the
 * envelope, dispatches to the
 * {@link io.cortex.remediation.dispatch.RemediationDispatcher} SPI,
 * and increments the
 * {@code cortex.remediation.dispatched_total} counter. Real
 * dispatcher adapters land in P6.1..P6.3 (Slack / PagerDuty / Jira).
 * </p>
 *
 * <p>Registers with the local-dev Eureka registry at
 * {@code http://localhost:8761/eureka/} as application-id
 * {@code log-remediation-service}. Production deployments use
 * Kubernetes Service DNS via the appropriate profile override.</p>
 *
 * <p>Per LD79 + ADR-0028 + ADR-0032 D1: direct Spring for Apache
 * Kafka {@code @KafkaListener} consumer rather than Spring Cloud
 * Stream inbound binder. Symmetric with the P5 LogEventConsumer +
 * the P4.4b KafkaTemplate producer, smaller dependency surface,
 * and explicit manual-ack control for offset commit semantics.</p>
 *
 * <p>{@link EnableKafka} activates {@code @KafkaListener} bean
 * post-processing so the consumer container starts at boot.</p>
 */
@SpringBootApplication
@EnableKafka
public class CortexRemediationApplication {

    /**
     * Protected constructor to discourage external instantiation while
     * allowing Spring's CGLIB proxy subclass for
     * {@code @Configuration}.
     */
    protected CortexRemediationApplication() {
        // bootstrap class - instantiated by Spring only
    }

    /**
     * Java entrypoint.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(CortexRemediationApplication.class, args);
    }
}
