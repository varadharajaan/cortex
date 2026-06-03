package io.cortex.processor.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import io.cloudevents.jackson.JsonFormat;
import org.springframework.stereotype.Component;

/**
 * Two-step CloudEvent decode + RawLogEvent extraction for the
 * production topic {@code cortex.logs.events.v1} (P5.1).
 *
 * <p>Step 1: deserialize the raw record bytes into a
 * {@link CloudEvent} via the CloudEvents 1.0 structured-mode JSON
 * format (ADR-0026). Step 2: extract the {@code data} bytes and
 * map them into the typed {@link RawLogEvent} record whose shape
 * mirrors the on-the-wire payload produced by the P4.4a
 * {@code OutboxEventFactory}.</p>
 *
 * <p>Any failure in either step is wrapped in {@link ParseException};
 * the consumer maps that to DLQ header
 * {@code x-failure-reason=parse_error} per ADR-0027 contract.</p>
 *
 * <p>Thread-safe: {@link JsonFormat} and {@link ObjectMapper} are
 * documented thread-safe for read; the {@code KafkaListenerContainerFactory}
 * may call {@link #parse(byte[])} from multiple consumer threads in
 * P5.3+.</p>
 */
@Component
public class LogEventParser {

    /** CloudEvents 1.0 structured-mode JSON codec (ADR-0026). */
    private final JsonFormat cloudEventFormat = new JsonFormat();

    /** Reusable Jackson encoder; shares the Spring-managed instance. */
    private final ObjectMapper objectMapper;

    /**
     * Constructs the parser with the shared Jackson encoder.
     *
     * @param objectMapper Spring-managed {@link ObjectMapper}; must
     *                     not be {@code null}
     */
    public LogEventParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Decode a Kafka record's value bytes into a typed
     * {@link RawLogEvent}.
     *
     * @param cloudEventBytes raw record value (CloudEvents 1.0
     *                        structured-mode JSON envelope); must
     *                        not be {@code null} or empty (callers
     *                        guard upstream)
     * @return typed view of the CloudEvent {@code data} field
     * @throws ParseException if envelope deserialization fails, if
     *                        the envelope carries no {@code data}
     *                        field, or if the {@code data} bytes
     *                        cannot be mapped to {@link RawLogEvent}
     */
    public RawLogEvent parse(final byte[] cloudEventBytes) {
        final CloudEvent envelope;
        try {
            envelope = this.cloudEventFormat.deserialize(cloudEventBytes);
        } catch (RuntimeException ex) {
            throw new ParseException(
                    "CloudEvent envelope deserialization failed: "
                            + ex.getClass().getSimpleName(), ex);
        }
        final CloudEventData data = envelope.getData();
        if (data == null) {
            throw new ParseException(
                    "CloudEvent envelope carries no data field",
                    new IllegalStateException("event.getData() == null"));
        }
        try {
            return this.objectMapper.readValue(data.toBytes(), RawLogEvent.class);
        } catch (java.io.IOException ex) {
            throw new ParseException(
                    "CloudEvent data field is not a valid RawLogEvent JSON: "
                            + ex.getClass().getSimpleName(), ex);
        }
    }
}
