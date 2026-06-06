/**
 * Quickwit index lifecycle admin SPI for the CORTEX
 * log-indexer-service (P7 epic).
 *
 * <p>Contains the {@link io.cortex.indexer.admin.QuickwitIndexAdmin}
 * SPI, the immutable {@link io.cortex.indexer.admin.IndexAdminResult}
 * verdict record, the {@link io.cortex.indexer.admin.IndexSpec}
 * input record, and the {@link io.cortex.indexer.admin.NoopQuickwitIndexAdmin}
 * default implementation that ships with the P7.0 scaffold.</p>
 *
 * <p>Per LD3 + ADR-0038: this is the sole owner of Quickwit
 * administration. The writer side ({@code QuickwitSink}) lives in
 * {@code log-processor-service} P5.3 and is intentionally not
 * referenced from this package.</p>
 */
package io.cortex.indexer.admin;
