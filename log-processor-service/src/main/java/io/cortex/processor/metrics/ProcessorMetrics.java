package io.cortex.processor.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters published by the log-processor-service
 * (P5.0 / Part 17 / ADR-0028 D6; P5.2 / ADR-0029 D5 adds the
 * classified counter).
 *
 * <p>Four counters establish the stable metric surface. Tag keys
 * are {@code topic} (always {@code cortex.logs.events.v1} in P5.0;
 * may broaden in P5.x), {@code tenant_id} (extracted from the
 * CloudEvent attribute set once the parser lands in P5.1;
 * {@code "unknown"} placeholder until then), and {@code outcome}
 * (P5.2 classifier verdict: {@code anomaly}, {@code normal},
 * {@code low_confidence}, {@code error}). Tag-key allowlist is
 * enforced per Part 17 rule to avoid tag-cardinality explosions.</p>
 *
 * <ul>
 *   <li>{@code cortex.processor.events.consumed_total} - every
 *       record polled off the Kafka topic, regardless of decode or
 *       classify outcome.</li>
 *   <li>{@code cortex.processor.events.parsed_total} - every record
 *       whose CloudEvent envelope decoded cleanly + handed to the
 *       AnomalyClassifier SPI.</li>
 *   <li>{@code cortex.processor.events.classified_total{outcome}} -
 *       every record after the AnomalyClassifier returns or throws.
 *       Ticks exactly once per parsed event with one of the four
 *       outcome tag values listed above (P5.2 / ADR-0029 D5).</li>
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
    private static final String TAG_OUTCOME = "outcome";

    /** Classification outcome: model flagged the event as anomaly above threshold. */
    public static final String OUTCOME_ANOMALY = "anomaly";
    /** Classification outcome: model returned a benign verdict. */
    public static final String OUTCOME_NORMAL = "normal";
    /** Classification outcome: anomaly verdict downgraded by the confidence-threshold gate. */
    public static final String OUTCOME_LOW_CONFIDENCE = "low_confidence";
    /** Classification outcome: classifier threw or returned null. */
    public static final String OUTCOME_ERROR = "error";

    /** The classifier counter metric name (kept public so tests can reference it). */
    public static final String METRIC_CLASSIFIED_TOTAL =
            "cortex.processor.events.classified_total";

    /** The anomalies-published counter metric name (P5.4 / ADR-0031). */
    public static final String METRIC_ANOMALIES_PUBLISHED_TOTAL =
            "cortex.processor.anomalies.published_total";

    private final MeterRegistry registry;

    /** Hot-path consumed-record counter for the production topic. */
    private final Counter consumed;
    /** Hot-path successfully-parsed counter for the production topic. */
    private final Counter parsed;
    /** Hot-path DLQ replay counter (P5.x path; 0 in P5.0). */
    private final Counter dlqReplay;
    /** Hot-path classified-anomaly counter (P5.2 / ADR-0029 D5). */
    private final Counter classifiedAnomaly;
    /** Hot-path classified-normal counter (P5.2 / ADR-0029 D5). */
    private final Counter classifiedNormal;
    /** Hot-path classified-low-confidence counter (P5.2 / ADR-0029 D5). */
    private final Counter classifiedLowConfidence;
    /** Hot-path classified-error counter (P5.2 / ADR-0029 D5). */
    private final Counter classifiedError;
    /** Hot-path anomaly-published counter (P5.4 / ADR-0031). */
    private final Counter anomaliesPublished;

    /**
     * Spring constructor.
     *
     * @param registry the autoconfigured Micrometer registry (Prometheus)
     */
    public ProcessorMetrics(final MeterRegistry registry) {
        this.registry = registry;
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
        this.classifiedAnomaly = buildClassifiedCounter(OUTCOME_ANOMALY);
        this.classifiedNormal = buildClassifiedCounter(OUTCOME_NORMAL);
        this.classifiedLowConfidence = buildClassifiedCounter(OUTCOME_LOW_CONFIDENCE);
        this.classifiedError = buildClassifiedCounter(OUTCOME_ERROR);
        // Bootstrap the P5.4 anomalies-published counter (LD112) so the
        // /actuator/prometheus scrape sees a stable surface even before
        // the first anomaly verdict ticks. tenant_id stays "unknown"
        // to match the bounded-cardinality policy of the other counters
        // (Part 17 tag-key allowlist).
        this.anomaliesPublished =
                Counter.builder(METRIC_ANOMALIES_PUBLISHED_TOTAL)
                        .description("Anomaly verdicts successfully published to"
                                + " cortex.anomalies.v1 (P5.4 / ADR-0031)")
                        .tag(TAG_TOPIC, "cortex.anomalies.v1")
                        .tag(TAG_TENANT, "unknown")
                        .register(registry);
    }

    /**
     * Pre-register one outcome series of the classifier counter so
     * downstream dashboards see a stable label surface even before
     * the first event ticks (Part 17).
     *
     * @param outcome one of {@link #OUTCOME_ANOMALY},
     *                {@link #OUTCOME_NORMAL},
     *                {@link #OUTCOME_LOW_CONFIDENCE},
     *                {@link #OUTCOME_ERROR}
     * @return the registered Micrometer {@link Counter}
     */
    private Counter buildClassifiedCounter(final String outcome) {
        return Counter.builder(METRIC_CLASSIFIED_TOTAL)
                .description("AnomalyClassifier verdicts after the confidence-threshold gate (P5.2)")
                .tag(TAG_TOPIC, "cortex.logs.events.v1")
                .tag(TAG_TENANT, "unknown")
                .tag(TAG_OUTCOME, outcome)
                .register(this.registry);
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

    /**
     * Increment the classifier counter for the supplied outcome tag.
     * Routes to the pre-registered counter for the four allowlist
     * values; an unknown outcome falls through to {@code error} so
     * tag cardinality stays bounded (Part 17).
     *
     * @param outcome one of {@link #OUTCOME_ANOMALY},
     *                {@link #OUTCOME_NORMAL},
     *                {@link #OUTCOME_LOW_CONFIDENCE},
     *                {@link #OUTCOME_ERROR}
     */
    public void incClassified(final String outcome) {
        switch (outcome) {
            case OUTCOME_ANOMALY -> this.classifiedAnomaly.increment();
            case OUTCOME_NORMAL -> this.classifiedNormal.increment();
            case OUTCOME_LOW_CONFIDENCE -> this.classifiedLowConfidence.increment();
            case OUTCOME_ERROR -> this.classifiedError.increment();
            default -> this.classifiedError.increment();
        }
    }

    /**
     * Increment the anomalies-published counter by one. Called by
     * {@code AnomaliesPublisher} after a successful synchronous send
     * to the {@code cortex.anomalies.v1} topic (P5.4 / ADR-0031).
     */
    public void incAnomaliesPublished() {
        this.anomaliesPublished.increment();
    }
}
