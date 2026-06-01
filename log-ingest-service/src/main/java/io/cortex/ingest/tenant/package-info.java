/**
 * Tenant resolution for log-ingest-service (P4.1).
 *
 * <p>Houses {@link io.cortex.ingest.tenant.TenantResolver} which
 * derives the active tenant id for an inbound batch. P4.1 honours
 * only the {@code X-Tenant-Id} header; the JWT-claim precedence
 * stub is reserved for P5.x when service-JWT parsing lands behind
 * {@code ServiceJwtFilter}.</p>
 */
package io.cortex.ingest.tenant;
