package io.cortex.processor.classify;

import io.cortex.processor.parse.RawLogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link NoopAnomalyClassifier} (P5.0 scaffold /
 * ADR-0028 D1; P5.1 SPI flip from {@code CloudEvent} to
 * {@link RawLogEvent}).
 *
 * <p>Asserts the default classifier returns the
 * {@link Classification#none()} singleton for every input - the
 * contract the scaffold relies on so the cortex.anomalies.v1
 * publish path stays dormant until the P5.2 Spring AI classifier
 * lands.</p>
 */
class NoopAnomalyClassifierTest {

    private final NoopAnomalyClassifier classifier = new NoopAnomalyClassifier();

    /** Asserts the no-op classifier returns the {@link Classification#none()} singleton. */
    @Test
    void returnsNoneSingletonForEveryEvent() {
        final RawLogEvent event = new RawLogEvent(
                "cortex-dev", "evt-1", Instant.now(), "INFO", "checkout",
                "hello", Map.of(), "idk-1", Instant.now());

        final Classification verdict = this.classifier.classify(event);

        assertThat(verdict).isSameAs(Classification.none());
        assertThat(verdict.anomaly()).isFalse();
        assertThat(verdict.severity()).isEqualTo("NONE");
        assertThat(verdict.reason()).isEmpty();
    }

    /** Asserts the {@code Classification.none()} sentinel is a stable singleton. */
    @Test
    void noneSingletonIsImmutable() {
        // Defensive: two retrievals return the same instance, so
        // downstream code can safely use reference equality.
        assertThat(Classification.none()).isSameAs(Classification.none());
    }
}
