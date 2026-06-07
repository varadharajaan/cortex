package io.cortex.monitoring.slo;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SloProperties} (P8.2 / ADR-0046 D2).
 *
 * <p>Locks the compact-ctor defensive defaults: a partially-filled
 * yml must still wire (null backend -> {@code noop}, null/zero
 * interval -> 30 s, null definitions -> empty list). Verifies
 * {@link SloProperties#definitions()} is defensively copied so
 * callers cannot mutate the bound list.</p>
 */
class SloPropertiesTest {

    @Test
    void canonicalPropertiesWire() {
        final SloDefinition def = new SloDefinition(
                "svc-a", "availability", 0.99d, Duration.ofHours(1));
        final SloProperties props = new SloProperties(
                true, "micrometer-derivation",
                Duration.ofSeconds(15), List.of(def));
        assertThat(props.enabled()).isTrue();
        assertThat(props.backend()).isEqualTo("micrometer-derivation");
        assertThat(props.evaluationInterval())
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(props.definitions()).containsExactly(def);
    }

    @Test
    void nullBackendCoercesToDefault() {
        final SloProperties props = new SloProperties(
                false, null, Duration.ofSeconds(30), List.of());
        assertThat(props.backend()).isEqualTo(SloProperties.DEFAULT_BACKEND);
    }

    @Test
    void blankBackendCoercesToDefault() {
        final SloProperties props = new SloProperties(
                false, "  ", Duration.ofSeconds(30), List.of());
        assertThat(props.backend()).isEqualTo(SloProperties.DEFAULT_BACKEND);
    }

    @Test
    void nullIntervalCoercesToDefault() {
        final SloProperties props = new SloProperties(
                false, "noop", null, List.of());
        assertThat(props.evaluationInterval())
                .isEqualTo(SloProperties.DEFAULT_EVALUATION_INTERVAL);
    }

    @Test
    void zeroIntervalCoercesToDefault() {
        final SloProperties props = new SloProperties(
                false, "noop", Duration.ZERO, List.of());
        assertThat(props.evaluationInterval())
                .isEqualTo(SloProperties.DEFAULT_EVALUATION_INTERVAL);
    }

    @Test
    void negativeIntervalCoercesToDefault() {
        final SloProperties props = new SloProperties(
                false, "noop", Duration.ofSeconds(-5), List.of());
        assertThat(props.evaluationInterval())
                .isEqualTo(SloProperties.DEFAULT_EVALUATION_INTERVAL);
    }

    @Test
    void nullDefinitionsCoerceToEmptyList() {
        final SloProperties props = new SloProperties(
                false, "noop", Duration.ofSeconds(30), null);
        assertThat(props.definitions()).isEmpty();
    }

    @Test
    void definitionsAreDefensivelyCopied() {
        final SloDefinition def = new SloDefinition(
                "svc-a", "availability", 0.99d, Duration.ofHours(1));
        final java.util.ArrayList<SloDefinition> mutable =
                new java.util.ArrayList<>();
        mutable.add(def);
        final SloProperties props = new SloProperties(
                true, "noop", Duration.ofSeconds(30), mutable);
        // After binding, the caller's list mutation must NOT be
        // visible through props.definitions().
        mutable.clear();
        assertThat(props.definitions()).containsExactly(def);
    }
}
