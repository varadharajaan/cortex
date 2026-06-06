/**
 * CORTEX log-indexer-service (P7 epic) -- Quickwit index lifecycle
 * admin owner.
 *
 * <p>Per LD3 + ADR-0038: sole owner of Quickwit administration
 * (index create / drop + retention enforcement + per-tenant
 * cardinality budgets + future search-side proxy). The writer
 * side ({@code QuickwitSink}) is owned by
 * {@code log-processor-service} P5.3 and is intentionally not
 * referenced from this module.</p>
 *
 * <p>Package layout (P7.0 scaffold):</p>
 * <ul>
 *   <li>{@link io.cortex.indexer.admin admin} -- Quickwit admin SPI
 *       + DTOs + noop default impl.</li>
 *   <li>{@link io.cortex.indexer.metrics metrics} -- Micrometer
 *       counter (bootstrap-registered).</li>
 *   <li>{@link io.cortex.indexer.health health} -- Spring Boot
 *       health indicator.</li>
 * </ul>
 */
package io.cortex.indexer;
