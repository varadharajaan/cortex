/**
 * Inbound security primitives for log-ingest-service.
 *
 * <p>P4.0 ships the mTLS-ready scaffold mandated by O8 / LD39 /
 * ADR-0020:</p>
 * <ul>
 *   <li>{@link io.cortex.ingest.security.ServiceJwtFilter} -- inbound
 *       presence-only check on {@code X-Cortex-Service-JWT} (full
 *       parse + signature validation lands in P5.x).</li>
 *   <li>{@link io.cortex.ingest.security.ServiceJwtProperties} --
 *       typed configuration bound from
 *       {@code cortex.security.service-jwt.*}.</li>
 * </ul>
 *
 * <p>The {@link org.springframework.boot.ssl.SslBundles} bean is
 * auto-configured by Spring Boot 3.3 when
 * {@code spring.ssl.bundle.*} properties are present. The
 * {@code application.yml} carries the commented bundle block so
 * activating mTLS for outbound clients in P5.x is a config-only
 * change.</p>
 */
package io.cortex.ingest.security;
