/**
 * Tenant-scoped REST search surface for the CORTEX
 * log-indexer-service (P9.1a / ADR-0042 Amendment 1).
 *
 * <p>Contains the {@link io.cortex.indexer.controller.SearchController}
 * HTTP adapter over the P7.4
 * {@link io.cortex.indexer.search.LogSearchClient} SPI, plus the
 * {@link io.cortex.indexer.controller.SearchControllerAdvice} that
 * maps client-input faults to RFC 7807 400 responses. The request
 * and response bodies live in
 * {@code io.cortex.indexer.controller.dto}.</p>
 *
 * <p>This is the downstream surface the gateway (P9.1b) calls via
 * {@code lb://log-indexer-service} to back the public
 * {@code searchLogs} REST + GraphQL query. The controller owns no
 * search logic: the active search SPI implementation performs the
 * query, enforces the ADR-0042 D3 tenant guardrail, and ticks the
 * {@code cortex.indexer.search_total} counter.</p>
 */
package io.cortex.indexer.controller;
