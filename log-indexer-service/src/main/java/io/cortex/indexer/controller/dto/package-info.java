/**
 * HTTP request / response DTOs for the log-indexer-service search
 * surface (P9.1a / ADR-0042 Amendment 1).
 *
 * <p>{@link io.cortex.indexer.controller.dto.SearchHttpRequest} is
 * the validated inbound body ({@code indexId}, {@code query},
 * optional {@code maxHits}); the tenant id is taken from the
 * {@code X-Tenant-Id} header, not the body.
 * {@link io.cortex.indexer.controller.dto.SearchHttpResponse} is the
 * happy-path 200 body ({@code numHits}, {@code hits}). The internal
 * {@link io.cortex.indexer.search.SearchResult} verdict mechanics
 * ({@code backend} / {@code outcome} / {@code reason}) are not
 * leaked onto the wire -- failures become an HTTP status plus an
 * RFC 7807 problem body.</p>
 */
package io.cortex.indexer.controller.dto;
