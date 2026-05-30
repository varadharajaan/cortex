package io.cortex.agent.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cortex.agent.LogEntry;
import io.cortex.agent.exception.CortexClientException;
import java.io.IOException;
import java.util.Collection;

/**
 * Thin wrapper around a single shared Jackson {@link ObjectMapper}
 * configured for CORTEX wire format.
 *
 * <p>Wire format rules:</p>
 * <ul>
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
     * Serializes a batch of {@link LogEntry} instances to a UTF-8 JSON
     * array suitable as an HTTP request body.
     *
     * @param entries entries to encode; must not be {@code null}
     * @return UTF-8 bytes of the JSON array
     * @throws CortexClientException if Jackson cannot serialize an entry
     */
    public byte[] encodeBatch(final Collection<LogEntry> entries) {
        try {
            return this.mapper.writeValueAsBytes(entries);
        } catch (IOException ex) {
            throw new CortexClientException("Failed to encode log batch", ex);
        }
    }
}
