package io.cortex.monitoring.probe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link ProbeRequest} value object (P8.0).
 * Verifies the canonical constructor rejects null / blank
 * {@code serviceId} + accepts a well-formed pair and accepts a
 * {@code null} {@code instanceId} for fan-out probes.
 */
class ProbeRequestTest {

    @Test
    void wellFormedRequestConstructs() {
        final ProbeRequest req =
                new ProbeRequest("log-indexer-service",
                        "log-indexer-service:abc-123");
        assertThat(req.serviceId()).isEqualTo("log-indexer-service");
        assertThat(req.instanceId())
                .isEqualTo("log-indexer-service:abc-123");
    }

    @Test
    void nullInstanceIdIsAccepted() {
        final ProbeRequest req =
                new ProbeRequest("log-indexer-service", null);
        assertThat(req.serviceId()).isEqualTo("log-indexer-service");
        assertThat(req.instanceId()).isNull();
    }

    @Test
    void blankServiceIdIsRejected() {
        assertThatThrownBy(() -> new ProbeRequest(" ", "i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void nullServiceIdIsRejected() {
        assertThatThrownBy(() -> new ProbeRequest(null, "i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void emptyServiceIdIsRejected() {
        assertThatThrownBy(() -> new ProbeRequest("", "i"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }
}
