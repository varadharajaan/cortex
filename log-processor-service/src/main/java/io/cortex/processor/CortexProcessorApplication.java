package io.cortex.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Spring Boot entrypoint for the CORTEX log-processor-service (P5).
 *
 * <p>P5.0 (this commit) ships the SCAFFOLD + first production code
 * path: module compiles, application context loads with Eureka +
 * Spring for Apache Kafka consumer + AnomalyClassifier SPI stub +
 * Micrometer counters wired. {@code @KafkaListener} on
 * {@code LogEventConsumer} binds to topic
 * {@code cortex.logs.events.v1} (CloudEvents 1.0 structured-mode
 * JSON envelopes shipped by {@code log-ingest-service} P4.4b),
 * decodes the envelope, dispatches to the
 * {@link io.cortex.processor.classify.AnomalyClassifier} SPI, and
 * increments three Micrometer counters. Real classifier + parser +
 * fan-out land in P5.1..P5.4.</p>
 *
 * <p>Registers with the local-dev Eureka registry at
 * {@code http://localhost:8761/eureka/} as application-id
 * {@code log-processor-service}. Production deployments use
 * Kubernetes Service DNS via the appropriate profile override.</p>
 *
 * <p>Per LD79 + ADR-0026 + ADR-0028 D1: direct Spring for Apache
 * Kafka {@code @KafkaListener} consumer rather than Spring Cloud
 * Stream inbound binder. Symmetric with the P4.4b
 * {@code KafkaTemplate} direct producer pivot, smaller dependency
 * surface, and explicit manual-ack control for offset commit
 * semantics (ADR-0028 D3).</p>
 *
 * <p>{@link EnableKafka} activates {@code @KafkaListener} bean
 * post-processing so the consumer container starts at boot.</p>
 */
@SpringBootApplication
@EnableKafka
public class CortexProcessorApplication {

    /**
     * Protected constructor to discourage external instantiation while
     * allowing Spring's CGLIB proxy subclass for
     * {@code @Configuration}.
     */
    protected CortexProcessorApplication() {
        // bootstrap class - instantiated by Spring only
    }

    /**
     * Java entrypoint.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(final String[] args) {
        SpringApplication.run(CortexProcessorApplication.class, args);
    }
}
