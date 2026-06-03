package io.cortex.processor.classify;

import io.cortex.processor.parse.RawLogEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link AnomalyClassifier} implementation that returns
 * {@link Classification#none()} for every event (P5.0 / ADR-0028 D1;
 * P5.1 SPI flip from {@code CloudEvent} to {@link RawLogEvent}).
 *
 * <p>The scaffold runs end-to-end without an LLM dependency: the
 * consumer parses + validates the event, hands it to this no-op,
 * and the no-anomaly verdict short-circuits the (not yet wired)
 * P5.4 cortex.anomalies.v1 publish path. The Micrometer counters
 * in {@code ProcessorMetrics} still fire so the metric surface is
 * stable from P5.0 onwards (P5.2 just swaps the bean implementation
 * behind {@code cortex.processor.classifier=spring-ai}).</p>
 *
 * <p>Gated by {@code cortex.processor.classifier=noop}
 * ({@code matchIfMissing=true}), so it's the default in every
 * profile until P5.2 introduces the Spring AI 1.0 alternative.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.processor.classifier",
        name = "provider",
        havingValue = "noop",
        matchIfMissing = true
)
public class NoopAnomalyClassifier implements AnomalyClassifier {

    @Override
    public Classification classify(final RawLogEvent event) {
        // No anomaly. Singleton avoids per-event allocation under
        // P5.3's higher consumer concurrency.
        return Classification.none();
    }
}

