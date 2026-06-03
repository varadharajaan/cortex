package io.cortex.agent.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cortex.agent.LogEntry;
import io.cortex.agent.exception.CortexClientException;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * Thin wrapper around a single shared Jackson {@link ObjectMapper}
 * configured for CORTEX wire format.
 *
 * <p>Wire format rules:</p>
 * <ul>
 *   <li>The HTTP body is the JSON object {@code {"entries":[...]}}
 *       because the ingest endpoint
 *       ({@code POST /api/v1/ingest/batch}) deserializes into
 *       {@code IngestBatchRequest(List<LogEntry> entries)}; emitting
 *       a bare JSON array yields HTTP 400.</li>
 *   <li>{@code java.time.Instant} is serialized as an ISO-8601 string
 *       (not as a numeric timestamp).</li>
 *   <li>Null fields are omitted to keep payloads compact.</li>
 *   <li>The mapper is shared across threads (Jackson docs guarantee
 *       it is thread-safe after configuration).</li>
 * </ul>
 */
public final class JsonCodec {

    /** Pre-configured, thread-safe mapper instance shared by all calls. */
    private final ObjectMapper mapper;

    /**
     * Creates a codec with the default CORTEX wire-format configuration.
     */
    public JsonCodec() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Serializes a batch of {@link LogEntry} instances into the ingest
     * envelope {@code {"entries":[...]}} as UTF-8 bytes suitable as an
     * HTTP request body.
     *
     * @param entries entries to encode; must not be {@code null}
     * @return UTF-8 bytes of the JSON envelope
     * @throws CortexClientException if Jackson cannot serialize an entry
     */
    public byte[] encodeBatch(final Collection<LogEntry> entries) {
        try {
            return this.mapper.writeValueAsBytes(Map.of("entries", entries));
        } catch (IOException ex) {
            throw new CortexClientException("Failed to encode log batch", ex);
        }
    }
}

