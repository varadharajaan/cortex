package io.cortex.remediation.anomaly;

import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Instant;
import org.apache.commons.lang3.StringUtils;

/**
 * Durable read-model row for a valid anomaly consumed by
 * log-remediation-service (P9.3).
 *
 * <p>The row is populated from {@link AnomalyEvent} after CloudEvents
 * parsing succeeds. It intentionally mirrors the public query shape so
 * the REST controller does not need to re-open Kafka history.</p>
 *
 * @param id             database surrogate key; {@code null} before insert
 * @param tenantId       owning tenant id
 * @param eventId        upstream anomaly event id
 * @param severity       anomaly severity bucket
 * @param reason         classifier/playbook reason text
 * @param ts             original log event timestamp, or receive time fallback
 * @param level          original log level
 * @param service        logical source service
 * @param message        original log message
 * @param confidence     classifier confidence score
 * @param anomalyType    classifier anomaly type
 * @param remediationKey playbook lookup key
 * @param receivedAt     remediation-service receive timestamp
 */
public record AnomalyRecord(
        Long id,
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
     * Canonical constructor with defensive defaults for legacy
     * P5/P6 anomaly envelopes.
     *
     * @param id             see record javadoc
     * @param tenantId       see record javadoc
     * @param eventId        see record javadoc
     * @param severity       see record javadoc
     * @param reason         see record javadoc
     * @param ts             see record javadoc
     * @param level          see record javadoc
     * @param service        see record javadoc
     * @param message        see record javadoc
     * @param confidence     see record javadoc
     * @param anomalyType    see record javadoc
     * @param remediationKey see record javadoc
     * @param receivedAt     see record javadoc
     */
    @SuppressWarnings("checkstyle:parameternumber")
    public AnomalyRecord {
        severity = StringUtils.defaultIfBlank(severity, "UNKNOWN");
        reason = StringUtils.defaultString(reason);
        anomalyType = StringUtils.defaultIfBlank(anomalyType, "UNKNOWN");
        remediationKey = StringUtils.defaultIfBlank(remediationKey, "none");
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        ts = ts == null ? receivedAt : ts;
    }

    /**
     * Maps a parsed anomaly into a new unsaved read-model row.
     *
     * @param event      parsed anomaly from Kafka
     * @param receivedAt receive timestamp captured by the consumer branch
     * @return unsaved read-model row
     */
    public static AnomalyRecord from(final AnomalyEvent event,
                                     final Instant receivedAt) {
        return new AnomalyRecord(
                null,
                event.tenantId(),
                event.eventId(),
                event.severity(),
                event.reason(),
                event.ts(),
                event.level(),
                event.service(),
                event.message(),
                event.confidence(),
                event.anomalyType(),
                event.remediationKey(),
                receivedAt);
    }
}
