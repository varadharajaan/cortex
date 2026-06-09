package io.cortex.ingest.dto.response;

import io.cortex.ingest.persistence.RawLog;
import java.time.Instant;
import java.util.Map;

/**
 * Operator-facing response body for {@code GET /api/v1/logs/{eventId}}
 * (P9.2a / ADR-0022 Amendment 1).
 *
 * <p>Projects the persisted {@link RawLog} aggregate onto the public
 * read shape. The internal surrogate primary key ({@code id}) and the
 * {@code idempotencyKey} are intentionally NOT exposed on the wire --
 * the {@code id} is a database implementation detail and the
 * idempotency key is an inbound-request artefact, neither of which a
 * read client needs. The {@code eventId} is the stable, caller-facing
 * identifier (it is the path variable the caller supplied).</p>
 *
 * @param eventId    server-computed dedupe key (the caller-facing id)
 * @param tenantId   owning tenant id (echoes the request
 *                   {@code X-Tenant-Id} for confirmation)
 * @param ts         event UTC timestamp from the original log line
 * @param level      severity name (TRACE / DEBUG / INFO / WARN / ERROR)
 * @param service    logical service name that produced the line
 * @param message    human-readable message (post server-side PII mask)
 * @param labels     flat string-string label map; never {@code null}
 * @param receivedAt server-side acceptance timestamp
 */
public record LogResponse(
        String eventId,
        String tenantId,
        Instant ts,
        String level,
        String service,
        String message,
        Map<String, String> labels,
        Instant receivedAt) {

    /**
     * Projects a persisted {@link RawLog} onto the public read shape,
     * dropping the internal surrogate id and idempotency key.
     *
     * @param row the persisted aggregate; must not be {@code null}
     * @return the operator-facing response projection
     */
    public static LogResponse from(final RawLog row) {
        return new LogResponse(
                row.eventId(),
                row.tenantId(),
                row.ts(),
                row.level(),
                row.service(),
                row.message(),
                row.labels(),
                row.receivedAt());
    }
}
