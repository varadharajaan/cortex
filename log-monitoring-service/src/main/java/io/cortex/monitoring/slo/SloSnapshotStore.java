package io.cortex.monitoring.slo;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * In-memory last-snapshot cache used by the evaluator and the P8.6
 * composite backend.
 */
@Component
public class SloSnapshotStore {

    private final ConcurrentMap<Key, SloSnapshot> snapshots =
            new ConcurrentHashMap<>();

    /**
     * Save the latest snapshot for a `(serviceId, sloName)` pair.
     *
     * @param snapshot non-null snapshot returned by an engine
     */
    public void record(final SloSnapshot snapshot) {
        if (snapshot == null || snapshot.serviceId().isBlank()
                || snapshot.sloName().isBlank()) {
            return;
        }
        this.snapshots.put(new Key(snapshot.serviceId(), snapshot.sloName()),
                snapshot);
    }

    /**
     * Look up the latest snapshot for a child SLO.
     *
     * @param serviceId child service id
     * @param sloName child SLO name
     * @return latest snapshot, if one has been recorded
     */
    public Optional<SloSnapshot> find(final String serviceId,
                                      final String sloName) {
        if (serviceId == null || serviceId.isBlank()
                || sloName == null || sloName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(this.snapshots.get(
                new Key(serviceId, sloName)));
    }

    private record Key(String serviceId, String sloName) {
    }
}
