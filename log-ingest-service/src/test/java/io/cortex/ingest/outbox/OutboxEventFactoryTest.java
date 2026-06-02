package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.ingest.persistence.RawLog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxEventFactory} (P4.4a / ADR-0025).
 *
 * <p>Pure-function bean: no Spring context, no mocks. Exercises the
 * deterministic JSON envelope contract end-to-end against the
 * shared Jackson encoder.</p>
 */
class OutboxEventFactoryTest {

    /** Shared encoder; same configuration as the production bean. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Factory under test, recreated per test for isolation. */
    private final OutboxEventFactory factory = new OutboxEventFactory(MAPPER);

    /** Default constructor used by JUnit. */
    OutboxEventFactoryTest() {
        // no state
    }

    /**
     * Verifies the envelope contains every {@link RawLog} field at
     * the documented JSON key, the labels map is serialised as a
     * JSON object, and timestamps are ISO-8601 UTC strings.
     *
     * @throws Exception when Jackson cannot re-read the produced JSON
     */
    @Test
    void buildsPendingEventWithStableJsonEnvelope() throws Exception {
        final Map<String, String> labels = new LinkedHashMap<>();
        labels.put("env", "test");
        labels.put("region", "us-east-1");
        final RawLog raw = new RawLog(
                null,
                "cortex-dev",
                "abc123",
                Instant.parse("2026-06-02T10:00:00Z"),
                "INFO",
                "cortex-it",
                "hello world",
                labels,
                "idem-1",
                Instant.parse("2026-06-02T10:00:01Z"));

        final OutboxEvent event = this.factory.toPendingEvent(raw);

        assertThat(event.id()).isNull();
        assertThat(event.tenantId()).isEqualTo("cortex-dev");
        assertThat(event.eventId()).isEqualTo("abc123");
        assertThat(event.status()).isEqualTo("PENDING");
        assertThat(event.attempts()).isZero();
        assertThat(event.nextAttemptAt()).isEqualTo(raw.receivedAt());
        assertThat(event.createdAt()).isEqualTo(raw.receivedAt());
        assertThat(event.lastError()).isNull();
        assertThat(event.publishedAt()).isNull();

        final JsonNode envelope = MAPPER.readTree(event.payload());
        assertThat(envelope.get("tenantId").asText()).isEqualTo("cortex-dev");
        assertThat(envelope.get("eventId").asText()).isEqualTo("abc123");
        assertThat(envelope.get("ts").asText()).isEqualTo("2026-06-02T10:00:00Z");
        assertThat(envelope.get("level").asText()).isEqualTo("INFO");
        assertThat(envelope.get("service").asText()).isEqualTo("cortex-it");
        assertThat(envelope.get("message").asText()).isEqualTo("hello world");
        assertThat(envelope.get("idempotencyKey").asText()).isEqualTo("idem-1");
        assertThat(envelope.get("receivedAt").asText()).isEqualTo("2026-06-02T10:00:01Z");
        assertThat(envelope.get("labels").get("env").asText()).isEqualTo("test");
        assertThat(envelope.get("labels").get("region").asText()).isEqualTo("us-east-1");
    }

    /**
     * Labels are serialised in sorted key order so byte-identical
     * inputs across runs produce byte-identical payloads. This is
     * what allows downstream consumers to dedupe on raw bytes if
     * they choose.
     *
     * @throws Exception when Jackson cannot re-serialise the envelope
     */
    @Test
    void labelsSerialisedInDeterministicSortedOrder() throws Exception {
        final Map<String, String> unsorted = new LinkedHashMap<>();
        unsorted.put("zebra", "z");
        unsorted.put("alpha", "a");
        unsorted.put("mango", "m");
        final RawLog raw = new RawLog(
                null, "t", "e", Instant.EPOCH, "INFO", "svc", "msg",
                unsorted, null, Instant.EPOCH);

        final String payload = this.factory.toPendingEvent(raw).payload();

        final int alphaIdx = payload.indexOf("\"alpha\"");
        final int mangoIdx = payload.indexOf("\"mango\"");
        final int zebraIdx = payload.indexOf("\"zebra\"");
        assertThat(alphaIdx).isPositive();
        assertThat(mangoIdx).isGreaterThan(alphaIdx);
        assertThat(zebraIdx).isGreaterThan(mangoIdx);
    }

    /**
     * A {@code null} labels map is serialised as an empty JSON
     * object so the consumer schema is uniform.
     *
     * @throws Exception when Jackson cannot re-read the envelope
     */
    @Test
    void nullLabelsBecomeEmptyJsonObject() throws Exception {
        final RawLog raw = new RawLog(
                null, "t", "e", Instant.EPOCH, "INFO", "svc", "msg",
                null, null, Instant.EPOCH);

        final JsonNode envelope = MAPPER.readTree(
                this.factory.toPendingEvent(raw).payload());

        assertThat(envelope.get("labels").isObject()).isTrue();
        assertThat(envelope.get("labels").size()).isZero();
    }
}
