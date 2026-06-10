package io.cortex.gateway.dto.response;

/**
 * A single persisted anomaly row, returned byte-identically by the REST
 * surface ({@code GET /api/v1/anomalies}) and the GraphQL surface
 * ({@code getAnomalies(...): [Anomaly!]!}) (P9.3b / ADR-0004 / ADR-0049).
 *
 * <p>Mirrors the P9.3a remediation response
 * ({@code io.cortex.remediation.anomaly.AnomalyResponse}). The gateway is
 * a thin pass-through, so the temporal fields ({@code ts},
 * {@code receivedAt}) are carried as ISO-8601 strings exactly as the
 * remediation backer emits them -- no re-parsing -- which keeps the REST
 * and GraphQL payloads byte-identical (the parity contract). This mirrors
 * the P9.2b {@link LogEntry} pass-through shape.</p>
 *
 * @param tenantId       owning tenant id (echoes the request tenant)
 * @param eventId        upstream anomaly event id (the caller-facing id)
 * @param severity       anomaly severity bucket
 * @param reason         classifier / playbook reason text
 * @param ts             original log UTC timestamp (ISO-8601 string)
 * @param level          original log level
 * @param service        logical source service name
 * @param message        original log message
 * @param confidence     classifier confidence score in [0.0, 1.0]
 * @param anomalyType    classifier anomaly type
 * @param remediationKey playbook lookup key
 * @param receivedAt     remediation-service receive timestamp (ISO-8601 string)
 */
public record Anomaly(
        String tenantId,
        String eventId,
        String severity,
        String reason,
        String ts,
        String level,
        String service,
        String message,
        double confidence,
        String anomalyType,
        String remediationKey,
        String receivedAt) {
}
