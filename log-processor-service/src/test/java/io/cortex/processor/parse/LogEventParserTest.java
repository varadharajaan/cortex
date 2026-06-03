package io.cortex.processor.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link LogEventParser} (P5.1).
 *
 * <p>Covers four scenarios:</p>
 * <ul>
 *   <li>happy path: well-formed CloudEvent envelope + valid data
 *       JSON -> typed {@link RawLogEvent} with the right fields</li>
 *   <li>parse failure: corrupt envelope bytes -> {@link ParseException}</li>
 *   <li>parse failure: well-formed envelope with no data field -> {@link ParseException}</li>
 *   <li>parse failure: well-formed envelope but data is not a valid
 *       {@code RawLogEvent} JSON -> {@link ParseException}</li>
 * </ul>
 */
class LogEventParserTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().findAndRegisterModules();

    private LogEventParser parser;

    /** Resets the parser before each test. */
    @BeforeEach
    void setUp() {
        this.parser = new LogEventParser(MAPPER);
    }

    /**
     * Happy path: every {@link RawLogEvent} field round-trips intact
     * from the structured-mode CloudEvent envelope.
     *
     * @throws Exception if the envelope cannot be serialised
     */
    @Test
    void parsesWellFormedCloudEventIntoRawLogEvent() throws Exception {
        final String dataJson = "{"
                + "\"tenantId\":\"cortex-dev\","
                + "\"eventId\":\"evt-1\","
                + "\"ts\":\"2026-06-03T12:00:00Z\","
                + "\"level\":\"INFO\","
                + "\"service\":\"checkout\","
                + "\"message\":\"hello\","
                + "\"labels\":{\"region\":\"eu-west\"},"
                + "\"idempotencyKey\":\"idk-1\","
                + "\"receivedAt\":\"2026-06-03T12:00:01Z\""
                + "}";
        final byte[] envelopeBytes = buildEnvelopeBytes("evt-1", "cortex-dev", dataJson);

        final RawLogEvent event = this.parser.parse(envelopeBytes);

        assertThat(event.tenantId()).isEqualTo("cortex-dev");
        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.ts()).isEqualTo(Instant.parse("2026-06-03T12:00:00Z"));
        assertThat(event.level()).isEqualTo("INFO");
        assertThat(event.service()).isEqualTo("checkout");
        assertThat(event.message()).isEqualTo("hello");
        assertThat(event.labels()).containsEntry("region", "eu-west");
        assertThat(event.idempotencyKey()).isEqualTo("idk-1");
        assertThat(event.receivedAt()).isEqualTo(Instant.parse("2026-06-03T12:00:01Z"));
    }

    /** Garbage envelope bytes surface as {@link ParseException}. */
    @Test
    void rejectsCorruptCloudEventEnvelope() {
        final byte[] notJson = "not-a-cloudevent".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> this.parser.parse(notJson))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("CloudEvent envelope deserialization failed");
    }

    /**
     * A well-formed CloudEvent envelope with no {@code data} block
     * is rejected because there is nothing to map to
     * {@link RawLogEvent}.
     *
     * @throws Exception if the envelope cannot be serialised
     */
    @Test
    void rejectsEnvelopeWithNoDataField() throws Exception {
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId("evt-empty")
                .withSource(URI.create("/cortex/test"))
                .withType("io.cortex.logs.event.v1")
                .build();
        final byte[] bytes = new JsonFormat().serialize(envelope);
        assertThatThrownBy(() -> this.parser.parse(bytes))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("CloudEvent envelope carries no data field");
    }

    /**
     * Envelope serialises but data JSON cannot be mapped to a
     * {@link RawLogEvent}; surfaces as {@link ParseException}.
     *
     * @throws Exception if the envelope cannot be serialised
     */
    @Test
    void rejectsEnvelopeWithMalformedDataJson() throws Exception {
        // Valid CloudEvent envelope, but `data` is not a valid
        // RawLogEvent JSON document (broken structure).
        final byte[] envelopeBytes = buildEnvelopeBytes(
                "evt-malformed", "cortex-dev", "{\"ts\":\"not-an-instant\"}");
        assertThatThrownBy(() -> this.parser.parse(envelopeBytes))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("CloudEvent data field is not a valid RawLogEvent JSON");
    }

    /**
     * Build a CloudEvent envelope wrapping the supplied data JSON.
     *
     * @param id        eventId
     * @param tenantId  CloudEvent subject
     * @param dataJson  data payload as JSON text
     * @return CloudEvents 1.0 structured-mode JSON bytes
     * @throws Exception if the envelope cannot be serialised
     */
    private static byte[] buildEnvelopeBytes(
            final String id, final String tenantId, final String dataJson) throws Exception {
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId(id)
                .withSource(URI.create("/cortex/log-ingest-service"))
                .withType("io.cortex.logs.event.v1")
                .withSubject(tenantId)
                .withDataContentType("application/json")
                .withData(dataJson.getBytes(StandardCharsets.UTF_8))
                .build();
        return new JsonFormat().serialize(envelope);
    }
}
