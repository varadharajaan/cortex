package io.cortex.ingest.config;

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
 * Spring Kafka producer wiring for the P4.4b outbox publisher
 * (ADR-0026). Produces a single {@link KafkaTemplate} bound to
 * {@code byte[]} key + {@code byte[]} value with the strong
 * delivery guarantees the outbox contract requires: {@code acks=all},
 * idempotent producer, retries on transient errors.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    /** Comma-separated bootstrap brokers (defaults to local smoke broker). */
    private final String bootstrapServers;

    /**
     * Constructs the configuration.
     *
     * @param bootstrapServers comma-separated Kafka bootstrap-servers
     */
    public KafkaConfig(@Value("${spring.kafka.bootstrap-servers:localhost:9092}")
                       final String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Kafka producer factory configured for at-least-once outbox
     * publishing.
     *
     * @return producer factory bound to {@code byte[]} key + value
     */
    @Bean
    public ProducerFactory<byte[], byte[]> outboxProducerFactory() {
        final Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * Spring Kafka template injected into {@code OutboxPoller}.
     *
     * @param producerFactory producer factory bean
     * @return ready-to-send {@link KafkaTemplate}
     */
    @Bean
    public KafkaTemplate<byte[], byte[]> outboxKafkaTemplate(
            final ProducerFactory<byte[], byte[]> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
