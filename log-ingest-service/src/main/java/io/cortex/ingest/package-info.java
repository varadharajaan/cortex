/**
 * Root package for the CORTEX log-ingest-service (P4).
 *
 * <p>Contains the Spring Boot entrypoint
 * {@link io.cortex.ingest.CortexIngestApplication}. Sub-packages:</p>
 * <ul>
 *   <li>{@code config} - typed {@link org.springframework.boot.context.properties.ConfigurationProperties}
 *       and OpenAPI customisation.</li>
 *   <li>{@code controller} - inbound HTTP controllers (REST adapter
 *       layer).</li>
 *   <li>{@code dto.request} / {@code dto.response} - immutable
 *       record DTOs.</li>
 *   <li>{@code exception} - {@link org.springframework.web.bind.annotation.RestControllerAdvice}
 *       producing RFC 7807 {@link org.springframework.http.ProblemDetail}.</li>
 *   <li>{@code security} - mTLS-ready scaffolding (service-JWT
 *       inbound filter + {@link org.springframework.boot.ssl.SslBundle}
 *       hook per O8 / ADR-0020).</li>
 *   <li>{@code service} - business-logic interfaces (kept thin in
 *       P4.0; fleshed out in P4.1..P4.4).</li>
 * </ul>
 */
package io.cortex.ingest;
