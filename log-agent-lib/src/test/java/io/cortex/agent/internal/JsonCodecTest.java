package io.cortex.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.agent.LogEntry;
import io.cortex.agent.LogLevel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonCodec}.
 */
class JsonCodecTest {

    /** A serialized batch is the ingest envelope {@code {"entries":[...]}}. */
    @Test
    void encodesBatchAsIngestEnvelope() {
        final JsonCodec codec = new JsonCodec();
        final LogEntry entry = new LogEntry(
                Instant.parse("2026-01-02T03:04:05Z"),
                LogLevel.INFO,
                "svc",
                "hello",
                java.util.Map.of("k", "v"));
        final String json = new String(codec.encodeBatch(List.of(entry)), StandardCharsets.UTF_8);
        assertThat(json).startsWith("{\"entries\":[").endsWith("]}");
        assertThat(json).contains("\"service\":\"svc\"");
        assertThat(json).contains("\"message\":\"hello\"");
        assertThat(json).contains("\"level\":\"INFO\"");
        assertThat(json).contains("\"k\":\"v\"");
    }

    /** Instants serialize as ISO-8601 strings, not numeric timestamps. */
    @Test
    void instantsSerializeAsIsoString() {
        final JsonCodec codec = new JsonCodec();
        final LogEntry entry = new LogEntry(
                Instant.parse("2026-01-02T03:04:05Z"),
                LogLevel.INFO,
                "svc",
                "msg",
                java.util.Map.of());
        final String json = new String(codec.encodeBatch(List.of(entry)), StandardCharsets.UTF_8);
        assertThat(json).contains("\"2026-01-02T03:04:05Z\"");
        assertThat(json).doesNotContain("1767322445");
    }

    /** Empty batches produce an envelope with an empty array. */
    @Test
    void emptyBatchProducesEmptyEnvelope() {
        final JsonCodec codec = new JsonCodec();
        final String json = new String(codec.encodeBatch(List.of()), StandardCharsets.UTF_8);
        assertThat(json).isEqualTo("{\"entries\":[]}");
    }
}
