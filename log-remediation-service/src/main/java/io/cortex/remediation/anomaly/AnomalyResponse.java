package io.cortex.remediation.anomaly;

import java.time.Instant;

/**
 * HTTP response projection for {@code GET /api/v1/anomalies}.
 *
 * @param tenantId       owning tenant id
 * @param eventId        upstream anomaly event id
 * @param severity       anomaly severity bucket
 * @param reason         classifier/playbook reason text
 * @param ts             original log timestamp
 * @param level          original log level
 * @param service        logical source service
 * @param message        original log message
 * @param confidence     classifier confidence score
 * @param anomalyType    classifier anomaly type
 * @param remediationKey playbook lookup key
 * @param receivedAt     remediation-service receive timestamp
 */
public record AnomalyResponse(
        String tenantId,
        String eventId,
        String severity,
        String reason,
        Instant ts,
        String level,
        String service,
        String message,
        double confidence,
        String anomalyType,
        String remediationKey,
        Instant receivedAt) {

    /**
     * Projects a durable read-model row onto the public API shape.
     *
     * @param record persisted anomaly row
     * @return response DTO
     */
    public static AnomalyResponse from(final AnomalyRecord record) {
        return new AnomalyResponse(
                record.tenantId(),
                record.eventId(),
                record.severity(),
                record.reason(),
                record.ts(),
                record.level(),
                record.service(),
                record.message(),
                record.confidence(),
                record.anomalyType(),
                record.remediationKey(),
                record.receivedAt());
    }
}
