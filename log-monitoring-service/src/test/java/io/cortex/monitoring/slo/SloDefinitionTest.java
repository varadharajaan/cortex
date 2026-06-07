package io.cortex.monitoring.slo;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SloDefinition} (P8.2 / ADR-0046 D1).
 *
 * <p>Locks the compact-ctor validation contract: null/blank
 * serviceId or sloName rejected; targetSuccessRatio must sit in
 * the open interval {@code (0, 1)} (boundaries 0.0 and 1.0
 * rejected); window must be a positive {@link Duration}. Every
 * boundary case has a dedicated assertion so a regression on the
 * compact ctor light up immediately.</p>
 */
class SloDefinitionTest {

    @Test
    void canonicalDefinitionWires() {
        final SloDefinition def = new SloDefinition(
                "log-indexer-service", "availability",
                0.99d, Duration.ofHours(1));
        assertThat(def.serviceId()).isEqualTo("log-indexer-service");
        assertThat(def.sloName()).isEqualTo("availability");
        assertThat(def.targetSuccessRatio()).isEqualTo(0.99d);
        assertThat(def.window()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void nullServiceIdRejected() {
        assertThatThrownBy(() -> new SloDefinition(null, "availability",
                0.99d, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void blankServiceIdRejected() {
        assertThatThrownBy(() -> new SloDefinition("  ", "availability",
                0.99d, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void blankSloNameRejected() {
        assertThatThrownBy(() -> new SloDefinition("log-indexer-service",
                "", 0.99d, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sloName");
    }

    @Test
    void targetExactlyZeroRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                0.0d, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetSuccessRatio");
    }

    @Test
    void targetExactlyOneRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                1.0d, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetSuccessRatio");
    }

    @Test
    void targetNegativeRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                -0.1d, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetSuccessRatio");
    }

    @Test
    void targetAboveOneRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                1.5d, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetSuccessRatio");
    }

    @Test
    void nullWindowRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                0.5d, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void zeroWindowRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                0.5d, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void negativeWindowRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                0.5d, Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }
}
