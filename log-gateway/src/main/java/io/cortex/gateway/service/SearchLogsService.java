package io.cortex.gateway.service;

import io.cortex.gateway.dto.request.LogSearchRequest;
import io.cortex.gateway.dto.response.LogSearchResult;

/**
 * Executes a tenant-scoped log search by delegating to
 * log-indexer-service (P9.1b / ADR-0004 / ADR-0049).
 *
 * <p>Shared by the REST controller
 * ({@link io.cortex.gateway.controller.SearchLogsController}) and the
 * GraphQL resolver
 * ({@link io.cortex.gateway.graphql.SearchLogsGraphQlController}) so both
 * surfaces resolve to identical behaviour (validation, downstream-failure
 * mapping); the only divergence is the transport layer. This mirrors the
 * P9.0 {@link NlQueryService} parity pattern.</p>
 *
 * <p>Implementations call P9.1a's {@code POST /api/v1/search} on
 * {@code lb://log-indexer-service}, forwarding {@code tenantId} as the
 * {@code X-Tenant-Id} header (the indexer's single source of truth for
 * tenant scoping, ADR-0042 D3), and map the returned verdict / HTTP
 * status onto a {@link LogSearchResult} or an
 * {@link io.cortex.gateway.exception.ApplicationException}.</p>
 */
public interface SearchLogsService {

    /**
     * Runs a search for {@code tenantId}.
     *
     * @param request  validated query inputs (index id, query, optional
     *                 max hits); must not be {@code null}
     * @param tenantId resolved tenant id forwarded as {@code X-Tenant-Id};
     *                 must not be blank
     * @return the search result envelope
     */
    LogSearchResult search(LogSearchRequest request, String tenantId);
}
