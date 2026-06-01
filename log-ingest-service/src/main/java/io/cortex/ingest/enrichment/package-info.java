/**
 * Server-side enrichment pipeline for log-ingest-service (P4.3).
 *
 * <p>Components in this package add metadata to every accepted
 * {@link io.cortex.agent.LogEntry} before it is persisted:
 * correlation-id propagation, tenant stamping, label
 * normalisation, and a GeoIP stub (real lookup deferred to P5).
 * The pipeline is invoked from
 * {@link io.cortex.ingest.service.impl.IngestServiceImpl} after
 * hot-path dedupe and before {@code event_id} hashing so the
 * canonical identity of a row reflects the normalised label
 * shape (LD3 / ADR-0022).</p>
 */
package io.cortex.ingest.enrichment;
