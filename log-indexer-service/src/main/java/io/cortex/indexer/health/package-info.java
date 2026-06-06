/**
 * Spring Boot {@code HealthIndicator}s for the CORTEX
 * log-indexer-service (P7 epic).
 *
 * <p>Hosts {@link io.cortex.indexer.health.QuickwitHealthIndicator}
 * -- bound to the {@code /actuator/health/quickwit} URI; reports
 * UP in P7.0 (noop backend); the P7.1+ binding probes the real
 * Quickwit {@code /api/v1/health} endpoint.</p>
 */
package io.cortex.indexer.health;
