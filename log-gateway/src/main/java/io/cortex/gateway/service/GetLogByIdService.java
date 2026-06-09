package io.cortex.gateway.service;

import io.cortex.gateway.dto.response.LogEntry;

/**
 * Fetches a single persisted log row by id by delegating to
 * log-ingest-service (P9.2b / ADR-0004 / ADR-0049).
 *
 * <p>Shared by the REST controller
 * ({@link io.cortex.gateway.controller.GetLogByIdController}) and the
 * GraphQL resolver
 * ({@link io.cortex.gateway.graphql.GetLogByIdGraphQlController}) so both
 * surfaces resolve to identical behaviour (downstream-failure mapping);
 * the only divergence is the transport layer. This mirrors the P9.0
 * {@link NlQueryService} / P9.1b {@link SearchLogsService} parity
 * pattern.</p>
 *
 * <p>Implementations call P9.2a's {@code GET /api/v1/logs/{eventId}} on
 * {@code lb://log-ingest-service}, forwarding {@code tenantId} as the
 * {@code X-Tenant-Id} header (the ingest backer's single source of truth
 * for tenant scoping), and map the returned HTTP status onto a
 * {@link LogEntry} or an
 * {@link io.cortex.gateway.exception.ApplicationException}.</p>
 */
public interface GetLogByIdService {

    /**
     * Fetches the log row identified by {@code eventId} for
     * {@code tenantId}.
     *
     * @param eventId  the caller-facing event id; must not be blank
     * @param tenantId resolved tenant id forwarded as {@code X-Tenant-Id};
     *                 must not be blank
     * @return the log entry
     */
    LogEntry getLogById(String eventId, String tenantId);
}
