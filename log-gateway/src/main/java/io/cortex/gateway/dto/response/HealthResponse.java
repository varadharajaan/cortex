package io.cortex.gateway.dto.response;

import java.time.OffsetDateTime;

/**
 * Lightweight response body for {@code GET /api/v1/health}.
 *
 * @param status      static literal {@code "UP"}
 * @param service     logical service name from {@code cortex.gateway.service}
 * @param environment deployment label from {@code cortex.gateway.environment}
 * @param timestamp   wall-clock time the response was assembled
 */
public record HealthResponse(
        String status,
        String service,
        String environment,
        OffsetDateTime timestamp) {
}
