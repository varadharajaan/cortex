package io.cortex.processor.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Producer-side Kafka wiring for the {@code DlqPublisher} (P5.1)
 * and {@code AnomaliesPublisher} (P5.4 / ADR-0031).
 *
 * <p>The P5.0 scaffold configured only the consumer side; P5.1 added
 * a byte[]/byte[] {@link KafkaTemplate} so {@code DlqPublisher} can
 * forward failed records to {@code cortex.logs.events.v1.dlq}
 * byte-for-byte. P5.4 reuses the same template bean for
 * {@code AnomaliesPublisher} to write fresh CloudEvents 1.0
 * envelopes to {@code cortex.anomalies.v1}. Mirrors the
 * log-ingest-service P4.4b {@code KafkaConfig} producer wiring
 * (ADR-0026) so a tester can read either module and recognise the
 * same shape: acks=all + idempotence + bounded request timeout.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ProcessorKafkaProducerConfig {

    private final String bootstrapServers;

    /**
     * Spring constructor.
     *
     * @param bootstrapServers comma-separated Kafka bootstrap-servers
     *                         pulled from {@code spring.kafka.bootstrap-servers}
     *                         with a localhost fallback for local-dev
     */
    public ProcessorKafkaProducerConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
            final String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Byte-array producer factory tuned for DLQ writes.
     *
     * @return configured {@link ProducerFactory}
     */
    @Bean
    public ProducerFactory<byte[], byte[]> dlqProducerFactory() {
        final Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * Byte-array {@link KafkaTemplate} consumed by
     * {@code DlqPublisher} (P5.1) and {@code AnomaliesPublisher}
     * (P5.4 / ADR-0031). Bean name retained for backward
     * compatibility with the P5.1 DI graph; both publishers
     * autowire by type since there is exactly one bean of this
     * shape in the processor context.
     *
     * @param dlqProducerFactory the producer factory bean above
     * @return configured {@link KafkaTemplate}
     */
    @Bean
    public KafkaTemplate<byte[], byte[]> dlqKafkaTemplate(
            final ProducerFactory<byte[], byte[]> dlqProducerFactory) {
        return new KafkaTemplate<>(dlqProducerFactory);
    }
}
