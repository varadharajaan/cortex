/**
 * Eureka-discovery REST probe adapter for
 * {@link io.cortex.monitoring.probe.ServiceHealthProbe}
 * (P8.1 / ADR-0045).
 *
 * <p>Walks the Spring Cloud
 * {@link org.springframework.cloud.client.discovery.DiscoveryClient}
 * to enumerate instances of a target Eureka service and scrapes
 * each instance's {@code /actuator/health} via an HTTP/1.1-pinned
 * (LD42) + dual-timeout (LD121) {@link
 * org.springframework.web.client.RestClient}.</p>
 *
 * <p>Activated only when
 * {@code cortex.monitoring.probe.backend=eureka-actuator}; the
 * default-dev profile leaves the property at {@code noop} so the
 * {@link io.cortex.monitoring.probe.NoopServiceHealthProbe} stays
 * the wired bean.</p>
 *
 * <p>Mirror of the P7.1 admin adapter pattern
 * ({@code io.cortex.indexer.admin.quickwit}) so the SPI gates +
 * outcome classification stay symmetric across modules.</p>
 */
package io.cortex.monitoring.probe.eureka;
