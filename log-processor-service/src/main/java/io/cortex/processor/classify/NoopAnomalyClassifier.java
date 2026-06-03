package io.cortex.processor.classify;

import io.cloudevents.CloudEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link AnomalyClassifier} implementation that returns
 * {@link Classification#none()} for every event (P5.0 / ADR-0028 D1).
 *
 * <p>The P5.0 scaffold runs end-to-end without an LLM dependency:
 * the consumer decodes the CloudEvent, hands it to this no-op, and
 * the no-anomaly verdict short-circuits the (not yet wired) P5.4
 * cortex.anomalies.v1 publish path. The Micrometer counters in
 * {@code ProcessorMetrics} still fire so the metric surface is
 * stable from P5.0 onwards (P5.2 just swaps the bean implementation
 * behind {@code cortex.processor.classifier=spring-ai}).</p>
 *
 * <p>Gated by {@code cortex.processor.classifier=noop}
 * ({@code matchIfMissing=true}), so it's the default in every
 * profile until P5.2 introduces the Spring AI 1.0 alternative.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.processor",
        name = "classifier",
        havingValue = "noop",
        matchIfMissing = true
)
public class NoopAnomalyClassifier implements AnomalyClassifier {

    @Override
    public Classification classify(final CloudEvent event) {
        // No anomaly. Singleton avoids per-event allocation under
        // P5.3's higher consumer concurrency.
        return Classification.none();
    }
}
