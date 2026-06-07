package io.cortex.monitoring.probe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NoopServiceHealthProbe} (P8.0 scaffold
 * default probe).
 *
 * <p>Verifies the noop probe stamps {@code backend=noop} and
 * returns {@link HealthSnapshot#noop(String)} for every call. No
 * outbound network at all -- this is the binder-gated default in
 * every profile until P8.1+ wires the real Eureka-discovery
 * REST probe.</p>
 */
class NoopServiceHealthProbeTest {

    private final NoopServiceHealthProbe probe = new NoopServiceHealthProbe();

    @Test
    void backendIdIsNoop() {
        assertThat(probe.backendId()).isEqualTo(HealthSnapshot.BACKEND_NOOP);
    }

    @Test
    void probeReturnsNoopVerdict() {
        final HealthSnapshot snap = probe.probe(
                new ProbeRequest("log-indexer-service",
                        "log-indexer-service:abc-123"));
        assertThat(snap.backend()).isEqualTo(HealthSnapshot.BACKEND_NOOP);
        assertThat(snap.outcome()).isEqualTo(HealthSnapshot.OUTCOME_NOOP);
        assertThat(snap.reason())
                .contains("noop probe")
                .contains("P8.0 scaffold");
        assertThat(snap.detail()).isEmpty();
    }
}
