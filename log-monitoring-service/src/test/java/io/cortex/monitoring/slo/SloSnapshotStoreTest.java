package io.cortex.monitoring.slo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SloSnapshotStore} (P8.6).
 */
class SloSnapshotStoreTest {

    @Test
    void recordIgnoresNullAndBlankKeys() {
        final SloSnapshotStore store = new SloSnapshotStore();

        store.record(null);
        store.record(snapshot("", "availability"));
        store.record(snapshot("svc-a", ""));
        store.record(snapshot("svc-a", "availability"));

        assertThat(store.find("svc-a", "availability")).isPresent();
        assertThat(store.find("", "availability")).isEmpty();
        assertThat(store.find("svc-a", "")).isEmpty();
    }

    @Test
    void findIgnoresNullAndBlankKeys() {
        final SloSnapshotStore store = new SloSnapshotStore();
        store.record(snapshot("svc-a", "availability"));

        assertThat(store.find(null, "availability")).isEmpty();
        assertThat(store.find("   ", "availability")).isEmpty();
        assertThat(store.find("svc-a", null)).isEmpty();
        assertThat(store.find("svc-a", "   ")).isEmpty();
        assertThat(store.find("svc-a", "latency")).isEmpty();
    }

    private static SloSnapshot snapshot(final String serviceId,
                                        final String sloName) {
        return new SloSnapshot("stub", serviceId, sloName,
                SloSnapshot.OUTCOME_HEALTHY, 0.9d, 0.1d, "");
    }
}
