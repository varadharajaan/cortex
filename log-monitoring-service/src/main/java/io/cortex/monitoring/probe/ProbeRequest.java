package io.cortex.monitoring.probe;

/**
 * Immutable input record describing a single service instance to
 * probe (P8.0 / ADR-0044 D2).
 *
 * <p>Minimal Day-1 shape -- carries only the two fields the SPI
 * needs to identify the target instance. P8.1 will resolve
 * {@link #serviceId()} via the Eureka discovery client into a list
 * of {@code InstanceInfo} entries; {@link #instanceId()} selects a
 * specific instance when more than one is registered (Eureka's
 * stable {@code instance-id} field, formatted as
 * {@code <serviceId>:<random-uuid>} per the existing
 * {@code application.yml} template).</p>
 *
 * @param serviceId  the Eureka application id (e.g.
 *                   {@code log-indexer-service}); never
 *                   {@code null}, never blank
 * @param instanceId the Eureka instance id (e.g.
 *                   {@code log-indexer-service:abc-123}); may be
 *                   {@code null} when the caller wants the probe
 *                   to fan out across every registered instance
 */
public record ProbeRequest(String serviceId, String instanceId) {

    /**
     * Compact validator-style canonical constructor. Defends against
     * null + blank {@link #serviceId()} so downstream HTTP/JSON code
     * paths can skip the same defensive check. {@link #instanceId()}
     * stays nullable so callers can request a fan-out probe across
     * every registered instance of a given service.
     *
     * @throws IllegalArgumentException when {@code serviceId} is
     *                                  {@code null} or blank
     */
    public ProbeRequest {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException(
                    "serviceId must not be blank");
        }
    }
}
