package io.cortex.processor.classify;

import io.cortex.processor.parse.RawLogEvent;

/**
 * Service Provider Interface for anomaly classification of a single
 * {@link RawLogEvent} parsed off {@code cortex.logs.events.v1}
 * (P5.0 scaffold / ADR-0028 D1; P5.1 SPI flip from {@code CloudEvent}
 * to {@link RawLogEvent}).
 *
 * <p>Implementations decide whether an incoming log event constitutes
 * an anomaly worthy of downstream remediation (P6) and, if so, what
 * classification metadata to attach. The default
 * {@link NoopAnomalyClassifier} returns {@link Classification#none()}
 * for every input -- the scaffold runs end-to-end without an LLM
 * dependency. P5.2 will land a {@code SpringAiAnomalyClassifier}
 * gated by {@code cortex.processor.classifier=spring-ai} and routed
 * through Spring AI 1.0.0 (Ollama local / Azure OpenAI prod per
 * ADR-0006).</p>
 *
 * <p>P5.1 flips the SPI from {@code CloudEvent} to {@link RawLogEvent}
 * so implementations reason about the typed domain payload
 * ({@code message}, {@code level}, {@code service}) rather than wire
 * format. The {@code consume.LogEventConsumer} orchestrates the
 * envelope -> parse -> validate -> classify pipeline; classifiers
 * never see a malformed or schema-violating event.</p>
 *
 * <p>Selection at runtime is driven by the {@code cortex.processor
 * .classifier} property + {@code @ConditionalOnProperty} on each
 * implementation. Only one classifier bean is active in a given
 * profile.</p>
 *
 * <p>Implementations MUST be thread-safe: the
 * {@code KafkaListenerContainerFactory} default concurrency is 1 in
 * P5.0 but raises in P5.3 when fan-out lands; the classifier is
 * called from multiple consumer threads concurrently.</p>
 */
public interface AnomalyClassifier {

    /**
     * Classify a single parsed log event.
     *
     * @param event the typed log event parsed from the CloudEvent
     *              envelope shipped by {@code log-ingest-service}
     *              P4.4b. Never {@code null} and guaranteed to have
     *              passed {@code SchemaValidator}.
     * @return the {@link Classification} verdict for this event.
     *         Implementations MUST return a non-{@code null} value;
     *         use {@link Classification#none()} to signal "no
     *         anomaly".
     */
    Classification classify(RawLogEvent event);
}

