package io.cortex.processor.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters published by the log-processor-service
 * (P5.0 / Part 17 / ADR-0028 D6).
 *
 * <p>Three counters establish the stable metric surface from P5.0
 * onwards. Tag keys are {@code topic} (always
 * {@code cortex.logs.events.v1} in P5.0; may broaden in P5.x) and
 * {@code tenant_id} (extracted from the CloudEvent attribute set
 * once the parser lands in P5.1; {@code "unknown"} placeholder in
 * P5.0). Tag-key allowlist is enforced per Part 17 rule to avoid
 * tag-cardinality explosions.</p>
 *
 * <ul>
 *   <li>{@code cortex.processor.events.consumed_total} - every
 *       record polled off the Kafka topic, regardless of decode or
 *       classify outcome.</li>
 *   <li>{@code cortex.processor.events.parsed_total} - every record
 *       whose CloudEvent envelope decoded cleanly + handed to the
 *       AnomalyClassifier SPI.</li>
 *   <li>{@code cortex.processor.events.dlq_replay_total} - reserved
 *       for the P5.x DLQ replay path from the P4.4c outbox
 *       dead-letter; emits 0 in P5.0 (counter still registered so
 *       Grafana dashboards have a stable surface).</li>
 * </ul>
 *
 * <p>Bean name is explicitly {@code cortexProcessorMetrics} to
 * avoid collision with Spring Boot's
 * {@code SystemMetricsAutoConfiguration#processorMetrics} (the
 * Micrometer CPU/JVM ProcessorMetrics binder), which also resolves
 * to default bean name {@code processorMetrics}.</p>
 */
@Component("cortexProcessorMetrics")
public class ProcessorMetrics {

    private static final String TAG_TOPIC = "topic";
    private static final String TAG_TENANT = "tenant_id";

    /** Hot-path consumed-record counter for the production topic. */
    private final Counter consumed;
    /** Hot-path successfully-parsed counter for the production topic. */
    private final Counter parsed;
    /** Hot-path DLQ replay counter (P5.x path; 0 in P5.0). */
    private final Counter dlqReplay;

    /**
     * Spring constructor.
     *
     * @param registry the autoconfigured Micrometer registry (Prometheus)
     */
    public ProcessorMetrics(final MeterRegistry registry) {
        this.consumed = Counter.builder("cortex.processor.events.consumed_total")
                .description("Records polled off cortex.logs.events.v1, regardless of decode outcome")
                .tag(TAG_TOPIC, "cortex.logs.events.v1")
                .tag(TAG_TENANT, "unknown")
                .register(registry);
        this.parsed = Counter.builder("cortex.processor.events.parsed_total")
                .description("CloudEvent envelopes that decoded cleanly + dispatched to AnomalyClassifier")
                .tag(TAG_TOPIC, "cortex.logs.events.v1")
                .tag(TAG_TENANT, "unknown")
                .register(registry);
        this.dlqReplay = Counter.builder("cortex.processor.events.dlq_replay_total")
                .description("Records replayed from cortex.logs.events.v1.dlq (P5.x path; 0 in P5.0)")
                .tag(TAG_TOPIC, "cortex.logs.events.v1.dlq")
                .tag(TAG_TENANT, "unknown")
                .register(registry);
    }

    /** Increment the consumed-record counter by one. */
    public void incConsumed() {
        this.consumed.increment();
    }

    /** Increment the successfully-parsed counter by one. */
    public void incParsed() {
        this.parsed.increment();
    }

    /** Increment the DLQ replay counter by one. */
    public void incDlqReplay() {
        this.dlqReplay.increment();
    }
}
