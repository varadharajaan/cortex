package io.cortex.processor.classify;

import io.cloudevents.CloudEvent;

/**
 * Service Provider Interface for anomaly classification of a single
 * {@link CloudEvent} consumed from {@code cortex.logs.events.v1}
 * (P5.0 / ADR-0028 D1).
 *
 * <p>Implementations decide whether an incoming log event constitutes
 * an anomaly worthy of downstream remediation (P6) and, if so, what
 * classification metadata to attach. The default
 * {@link NoopAnomalyClassifier} returns {@link Classification#none()}
 * for every input -- the P5.0 scaffold runs end-to-end without an
 * LLM dependency. P5.2 will land a {@code SpringAiAnomalyClassifier}
 * gated by {@code cortex.processor.classifier=spring-ai} and routed
 * through Spring AI 1.0.0 (Ollama local / Azure OpenAI prod per
 * ADR-0006).</p>
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
     * Classify a single CloudEvent envelope.
     *
     * @param event the decoded CloudEvent shipped by
     *              {@code log-ingest-service} P4.4b (CloudEvents
     *              1.0 structured-mode JSON, type
     *              {@code io.cortex.logs.event.v1}). Never
     *              {@code null}.
     * @return the {@link Classification} verdict for this event.
     *         Implementations MUST return a non-{@code null} value;
     *         use {@link Classification#none()} to signal "no
     *         anomaly".
     */
    Classification classify(CloudEvent event);
}
