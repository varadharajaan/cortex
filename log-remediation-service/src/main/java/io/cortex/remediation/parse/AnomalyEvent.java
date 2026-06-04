package io.cortex.remediation.parse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Typed view of the JSON payload carried inside the CloudEvent
 * {@code data} field on topic {@code cortex.anomalies.v1}
 * (P6.0 / ADR-0032; mirror of the producer-side
 * {@code log-processor-service.AnomaliesPublisher} envelope per
 * ADR-0031 and the {@code docs/p5-to-p6-handoff.md} contract).
 *
 * <p>Eight fields land verbatim from the upstream producer:
 * {@code eventId}, {@code tenantId}, {@code severity}, {@code reason},
 * {@code ts}, {@code level}, {@code service}, {@code message}.
 * Constructed by {@link AnomalyEnvelopeParser} after the CloudEvent
 * envelope decodes + the {@code specversion}/{@code type} guard
 * passes.</p>
 *
 * @param eventId  SHA-256 hex from the upstream {@code LogEvent.eventId}
 *                 (duplicates the envelope {@code subject}; use as the
 *                 idempotency key for downstream dispatch dedupe)
 * @param tenantId UUID of the originating tenant
 * @param severity classifier severity bucket (NONE / LOW / MEDIUM / HIGH /
 *                 CRITICAL; producer guarantees a non-null value on
 *                 the anomaly branch)
 * @param reason   short human-readable explanation from the classifier;
 *                 may be empty but never {@code null} after parse
 * @param ts       ISO-8601 UTC instant of the originating log line
 * @param level    original log level string (TRACE / DEBUG / INFO /
 *                 WARN / ERROR)
 * @param service  logical service / app name that produced the log line
 * @param message  human-readable message verbatim from the source
 */
public record AnomalyEvent(
        String eventId,
        String tenantId,
        String severity,
        String reason,
        Instant ts,
        String level,
        String service,
        String message) {

    /**
     * Jackson constructor. Mirrors the producer-side
     * {@code AnomaliesPublisher} {@code data} field order so
     * round-trip tests in the parser unit suite stay deterministic.
     *
     * @param eventId  see record javadoc
     * @param tenantId see record javadoc
     * @param severity see record javadoc
     * @param reason   see record javadoc
     * @param ts       see record javadoc
     * @param level    see record javadoc
     * @param service  see record javadoc
     * @param message  see record javadoc
     */
    @JsonCreator
    @SuppressWarnings("checkstyle:parameternumber")
    public AnomalyEvent(
            @JsonProperty("eventId") final String eventId,
            @JsonProperty("tenantId") final String tenantId,
            @JsonProperty("severity") final String severity,
            @JsonProperty("reason") final String reason,
            @JsonProperty("ts") final Instant ts,
            @JsonProperty("level") final String level,
            @JsonProperty("service") final String service,
            @JsonProperty("message") final String message) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.severity = severity;
        this.reason = reason == null ? "" : reason;
        this.ts = ts;
        this.level = level;
        this.service = service;
        this.message = message;
    }
}
