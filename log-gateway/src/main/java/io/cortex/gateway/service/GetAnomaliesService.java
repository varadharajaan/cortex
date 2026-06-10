package io.cortex.gateway.service;

import io.cortex.gateway.dto.response.Anomaly;
import java.util.List;

/**
 * Lists persisted anomalies for a tenant by delegating to
 * log-remediation-service (P9.3b / ADR-0004 / ADR-0049).
 *
 * <p>Shared by the REST controller
 * ({@link io.cortex.gateway.controller.GetAnomaliesController}) and the
 * GraphQL resolver
 * ({@link io.cortex.gateway.graphql.GetAnomaliesGraphQlController}) so
 * both surfaces resolve to identical behaviour (validation +
 * downstream-failure mapping); the only divergence is the transport
 * layer. This mirrors the P9.0 {@link NlQueryService} / P9.1b
 * {@link SearchLogsService} / P9.2b {@link GetLogByIdService} parity
 * pattern.</p>
 *
 * <p>Implementations call P9.3a's {@code GET /api/v1/anomalies} on
 * {@code lb://log-remediation-service}, forwarding {@code tenantId} as a
 * query parameter (the remediation backer's single source of truth for
 * tenant scoping) together with the optional {@code since} / {@code until}
 * / {@code limit} filters, and map the returned HTTP status onto a
 * {@link List} of {@link Anomaly} or an
 * {@link io.cortex.gateway.exception.ApplicationException}.</p>
 */
public interface GetAnomaliesService {

    /**
     * Lists anomalies for {@code tenantId}, newest first, optionally
     * bounded by a time window and a row limit.
     *
     * @param tenantId resolved tenant id forwarded as the
     *                 {@code tenantId} query parameter; must not be blank
     * @param since    optional inclusive ISO-8601 lower timestamp bound;
     *                 forwarded verbatim when present
     * @param until    optional inclusive ISO-8601 upper timestamp bound;
     *                 forwarded verbatim when present
     * @param limit    optional row limit; strictly positive when present
     * @return matching anomalies (possibly empty, never {@code null})
     */
    List<Anomaly> getAnomalies(String tenantId, String since, String until, Integer limit);
}