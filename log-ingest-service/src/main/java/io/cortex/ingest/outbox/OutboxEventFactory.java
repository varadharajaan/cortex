package io.cortex.ingest.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.ingest.persistence.RawLog;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Builds an {@link OutboxEvent} from a persisted {@link RawLog}
 * (P4.4a / ADR-0025).
 *
 * <p>The {@code payload} is a stable JSON object containing the
 * same fields as {@link RawLog} but serialised with deterministic
 * key ordering so the bytes on the outbox row match the bytes the
 * P4.4b poller will eventually publish into the
 * {@code cortex.logs.events.v1} topic. Field order:
 * {@code tenantId, eventId, ts, level, service, message, labels,
 * idempotencyKey, receivedAt}. Timestamps are ISO-8601 UTC
 * ({@code Instant.toString()} format). The {@code labels} map is
 * sorted by key so two batches with identical content produce
 * byte-identical payloads.</p>
 *
 * <p>This factory is intentionally a thin pure-function bean (no
 * state, no I/O, no transaction). The transactional outbox-write
 * itself lives in {@link RawLogTransactionalWriter}.</p>
 */
@Component
public class OutboxEventFactory {

    /** Reusable Jackson encoder; shares the Spring-managed instance. */
    private final ObjectMapper objectMapper;

    /**
     * Constructs the factory with the shared Jackson encoder.
     *
     * @param objectMapper Spring-managed {@link ObjectMapper}; must
     *                     not be {@code null}
     */
    public OutboxEventFactory(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serialises a {@link RawLog} into a fresh PENDING
     * {@link OutboxEvent}.
     *
     * @param raw raw_logs row about to be inserted; must not be
     *            {@code null}
     * @return new {@link OutboxEvent} carrying the deterministic
     *         JSON payload, status {@link OutboxStatus#PENDING}
     * @throws IllegalStateException if Jackson cannot serialise the
     *                               raw_logs fields; this is a
     *                               programmer error (all fields
     *                               are bean-validated strings,
     *                               instants, and a flat label map)
     *                               and is rethrown so the request
     *                               handler surfaces a 500 verbatim
     */
    public OutboxEvent toPendingEvent(final RawLog raw) {
        final Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("tenantId", raw.tenantId());
        envelope.put("eventId", raw.eventId());
        envelope.put("ts", DateTimeFormatter.ISO_INSTANT.format(raw.ts()));
        envelope.put("level", raw.level());
        envelope.put("service", raw.service());
        envelope.put("message", raw.message());
        envelope.put("labels", raw.labels() == null
                ? Map.of()
                : new TreeMap<>(raw.labels()));
        envelope.put("idempotencyKey", raw.idempotencyKey());
        envelope.put("receivedAt",
                DateTimeFormatter.ISO_INSTANT.format(raw.receivedAt()));
        try {
            final String json = this.objectMapper.writeValueAsString(envelope);
            return OutboxEvent.pending(
                    raw.tenantId(),
                    raw.eventId(),
                    json,
                    raw.receivedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "failed to serialise raw_logs row into outbox payload", ex);
        }
    }
}
