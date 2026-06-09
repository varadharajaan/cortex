package io.cortex.gateway.dto.response;

import java.util.Map;

/**
 * A single persisted log row, returned byte-identically by the REST
 * surface ({@code GET /api/v1/logs/{eventId}}) and the GraphQL surface
 * ({@code getLogById(id): LogEntry}) (P9.2b / ADR-0004 / ADR-0049).
 *
 * <p>Mirrors the P9.2a ingest response
 * ({@code io.cortex.ingest.dto.response.LogResponse}). The gateway is a
 * thin pass-through, so the temporal fields ({@code ts},
 * {@code receivedAt}) are carried as ISO-8601 strings exactly as the
 * ingest backer emits them -- no re-parsing -- which keeps the REST and
 * GraphQL payloads byte-identical (the parity contract). {@code labels}
 * is exposed on the GraphQL surface through the {@code JSON} scalar
 * (shared with P9.1b {@code searchLogs}).</p>
 *
 * @param eventId    server-computed dedupe key (the caller-facing id)
 * @param tenantId   owning tenant id (echoes the request tenant)
 * @param ts         event UTC timestamp (ISO-8601 string)
 * @param level      severity name (TRACE / DEBUG / INFO / WARN / ERROR)
 * @param service    logical service name that produced the line
 * @param message    human-readable message (post server-side PII mask)
 * @param labels     flat string-string label map; never {@code null}
 * @param receivedAt server-side acceptance timestamp (ISO-8601 string)
 */
public record LogEntry(
        String eventId,
        String tenantId,
        String ts,
        String level,
        String service,
        String message,
        Map<String, String> labels,
        String receivedAt) {

    /**
     * Compact constructor; defensively copies {@code labels} (or
     * substitutes the canonical empty map for a {@code null}) so the
     * published entry cannot leak a mutable backing map.
     */
    public LogEntry {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
