package io.cortex.processor.classify;

import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link NoopAnomalyClassifier} (P5.0 / ADR-0028 D1).
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
        final var event = CloudEventBuilder.v1()
                .withId("test-id")
                .withSource(URI.create("/cortex/test"))
                .withType("io.cortex.logs.event.v1")
                .build();

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
