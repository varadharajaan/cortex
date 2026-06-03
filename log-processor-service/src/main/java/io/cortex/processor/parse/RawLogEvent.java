package io.cortex.processor.parse;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed view of the JSON payload carried inside the CloudEvent
 * {@code data} field on topic {@code cortex.logs.events.v1}
 * (P5.1 / ADR-0026).
 *
 * <p>Mirrors the on-the-wire shape produced by the P4.4a
 * {@code OutboxEventFactory} (one log per row, NOT a batch):
 * {@code tenantId, eventId, ts, level, service, message, labels,
 * idempotencyKey, receivedAt}. Constructed by
 * {@link LogEventParser} after the CloudEvent envelope decodes.
 * Validated by {@link SchemaValidator} before reaching the
 * {@code AnomalyClassifier} SPI.</p>
 *
 * @param tenantId       resolved tenant identifier
 * @param eventId        server-computed dedupe key
 * @param ts             ISO-8601 UTC instant the log line occurred
 * @param level          severity string (TRACE / DEBUG / INFO / WARN
 *                       / ERROR); validated by {@link SchemaValidator}
 * @param service        logical service / app name
 * @param message        human-readable message
 * @param labels         structured key / value labels; never
 *                       {@code null} after construction
 * @param idempotencyKey client-supplied idempotency key
 * @param receivedAt     server-side acceptance timestamp
 */
public record RawLogEvent(
        String tenantId,
        String eventId,
        Instant ts,
        String level,
        String service,
        String message,
        Map<String, String> labels,
        String idempotencyKey,
        Instant receivedAt) {

    /**
     * Jackson constructor with defensive label-map copy so the record
     * stays immutable when callers mutate the input after parsing.
     *
     * @param tenantId       resolved tenant identifier
     * @param eventId        server-computed dedupe key
     * @param ts             ISO-8601 UTC instant the log line occurred
     * @param level          severity string
     * @param service        logical service / app name
     * @param message        human-readable message
     * @param labels         structured key / value labels; may be
     *                       {@code null} on the wire, normalised to
     *                       an empty unmodifiable map
     * @param idempotencyKey client-supplied idempotency key
     * @param receivedAt     server-side acceptance timestamp
     */
    @JsonCreator
    @SuppressWarnings("checkstyle:parameternumber")
    public RawLogEvent(
            @JsonProperty("tenantId") final String tenantId,
            @JsonProperty("eventId") final String eventId,
            @JsonProperty("ts") final Instant ts,
            @JsonProperty("level") final String level,
            @JsonProperty("service") final String service,
            @JsonProperty("message") final String message,
            @JsonProperty("labels") final Map<String, String> labels,
            @JsonProperty("idempotencyKey") final String idempotencyKey,
            @JsonProperty("receivedAt") final Instant receivedAt) {
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.ts = ts;
        this.level = level;
        this.service = service;
        this.message = message;
        this.labels = labels == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        this.idempotencyKey = idempotencyKey;
        this.receivedAt = receivedAt;
    }
}
