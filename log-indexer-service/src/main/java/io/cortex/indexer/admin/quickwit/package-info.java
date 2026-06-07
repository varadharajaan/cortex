/**
 * Real Quickwit HTTP admin client implementation of the
 * {@link io.cortex.indexer.admin.QuickwitIndexAdmin} SPI (P7.1 /
 * ADR-0039).
 *
 * <p>The {@link io.cortex.indexer.admin.quickwit.QuickwitHttpAdmin}
 * bean is gated behind {@code cortex.indexer.admin.backend=quickwit}
 * so the default-dev boot still selects the
 * {@link io.cortex.indexer.admin.NoopQuickwitIndexAdmin} (which
 * activates with {@code matchIfMissing=true}). Configuration is
 * sourced from the {@code cortex.indexer.quickwit} prefix bound by
 * {@link io.cortex.indexer.admin.quickwit.QuickwitProperties}.</p>
 *
 * <p>Outbound HTTP is pinned to HTTP/1.1 via
 * {@link org.springframework.http.client.JdkClientHttpRequestFactory}
 * (LD42) with dual connect + read timeouts (LD121); outcome
 * classification is delegated to
 * {@link io.cortex.indexer.admin.quickwit.RestAdminTemplate}
 * (mirror of the P6.0a {@code RestDispatchTemplate} composition
 * pattern, ADR-0036).</p>
 *
 * <p>Symmetric to the P5.3 writer side in
 * {@code io.cortex.processor.sink.QuickwitSink}: same HTTP/1.1
 * pin, same dual-timeout shape, same transport-exception
 * classification. The two services together honor the ownership
 * boundary locked in ADR-0038 D1 -- the processor OWNs the writer
 * (POST {@code /api/v1/&lt;index&gt;/ingest}), the indexer OWNs the
 * admin lifecycle (POST/GET/DELETE {@code /api/v1/indexes}).</p>
 */
package io.cortex.indexer.admin.quickwit;
