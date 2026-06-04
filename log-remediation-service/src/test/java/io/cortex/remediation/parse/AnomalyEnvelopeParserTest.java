package io.cortex.remediation.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnomalyEnvelopeParser} (P6.0 / ADR-0032 D2).
 *
 * <p>Round-trip a CloudEvent envelope built with the same producer-side
 * primitives ({@link CloudEventBuilder} + {@link JsonFormat}) the
 * upstream {@code AnomaliesPublisher} uses, then assert the parser
 * decodes back to the expected typed {@link AnomalyEvent}. Plus
 * three failure-path assertions covering the
 * {@link FailureReason} allowlist.</p>
 */
class AnomalyEnvelopeParserTest {

    private ObjectMapper mapper;
    private AnomalyEnvelopeParser parser;

    /** Builds a fresh parser + mapper before each test so state never leaks between cases. */
    @BeforeEach
    void setUp() {
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.parser = new AnomalyEnvelopeParser(this.mapper);
    }

    /**
     * Assembles a CloudEvent envelope with the supplied {@code type}
     * and {@link AnomalyEvent} payload so each test can drive its
     * own failure / success branch.
     *
     * @param type    CloudEvent {@code type} value
     * @param payload the {@link AnomalyEvent} to wrap in the {@code data} block
     * @return the serialized CloudEvent bytes
     * @throws Exception on Jackson or CloudEvents serialization failure
     */
    private byte[] buildEnvelope(final String type, final AnomalyEvent payload) throws Exception {
        final byte[] data = this.mapper.writeValueAsBytes(payload);
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId("ce-id-1")
                .withSource(URI.create("/cortex/log-processor-service"))
                .withType(type)
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withSubject(payload.tenantId())
                .withDataContentType("application/json")
                .withData(data)
                .build();
        return new JsonFormat().serialize(envelope);
    }

    /**
     * Serialize -> deserialize round-trip on a contract-shaped envelope
     * must preserve every business field.
     *
     * @throws Exception on serialization failure
     */
    @Test
    void roundTripsValidEnvelope() throws Exception {
        final AnomalyEvent payload = new AnomalyEvent(
                "evt-42",
                "tenant-x",
                "HIGH",
                "burst of 5xx",
                Instant.parse("2025-06-04T09:40:30Z"),
                "ERROR",
                "checkout",
                "503 from /pay endpoint");
        final byte[] envelope = buildEnvelope("io.cortex.anomaly.v1", payload);

        final AnomalyEvent decoded = this.parser.parse(envelope);

        assertThat(decoded).isNotNull();
        assertThat(decoded.eventId()).isEqualTo("evt-42");
        assertThat(decoded.tenantId()).isEqualTo("tenant-x");
        assertThat(decoded.severity()).isEqualTo("HIGH");
        assertThat(decoded.reason()).isEqualTo("burst of 5xx");
        assertThat(decoded.ts()).isEqualTo(Instant.parse("2025-06-04T09:40:30Z"));
        assertThat(decoded.level()).isEqualTo("ERROR");
        assertThat(decoded.service()).isEqualTo("checkout");
        assertThat(decoded.message()).isEqualTo("503 from /pay endpoint");
    }

    /** A non-CloudEvent payload must surface {@link FailureReason#INVALID_ENVELOPE}. */
    @Test
    void rejectsMalformedJsonAsInvalidEnvelope() {
        final byte[] garbage = "not-a-cloudevent".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> this.parser.parse(garbage))
                .isInstanceOf(ParseException.class)
                .extracting(ex -> ((ParseException) ex).reason())
                .isEqualTo(FailureReason.INVALID_ENVELOPE);
    }

    /**
     * An envelope with a wrong {@code type} must surface
     * {@link FailureReason#WRONG_TYPE}.
     *
     * @throws Exception on serialization failure
     */
    @Test
    void rejectsWrongTypeAsWrongType() throws Exception {
        final AnomalyEvent payload = new AnomalyEvent(
                "evt-1", "tenant-y", "HIGH", "",
                Instant.now(), "ERROR", "svc", "msg");
        final byte[] envelope = buildEnvelope("io.cortex.unknown.v1", payload);

        assertThatThrownBy(() -> this.parser.parse(envelope))
                .isInstanceOf(ParseException.class)
                .extracting(ex -> ((ParseException) ex).reason())
                .isEqualTo(FailureReason.WRONG_TYPE);
    }

    /**
     * A data block with a blank {@code eventId} must surface
     * {@link FailureReason#MISSING_DATA}.
     *
     * @throws Exception on serialization failure
     */
    @Test
    void rejectsMissingEventIdAsMissingData() throws Exception {
        final AnomalyEvent payload = new AnomalyEvent(
                "", "tenant-z", "HIGH", "",
                Instant.now(), "ERROR", "svc", "msg");
        final byte[] envelope = buildEnvelope("io.cortex.anomaly.v1", payload);

        assertThatThrownBy(() -> this.parser.parse(envelope))
                .isInstanceOf(ParseException.class)
                .extracting(ex -> ((ParseException) ex).reason())
                .isEqualTo(FailureReason.MISSING_DATA);
    }

    /**
     * A data block with a blank {@code tenantId} must also surface
     * {@link FailureReason#MISSING_DATA} (covers the second leg of the
     * isBlank guard in {@link AnomalyEnvelopeParser#parse(byte[])}).
     *
     * @throws Exception on serialization failure
     */
    @Test
    void rejectsMissingTenantIdAsMissingData() throws Exception {
        final AnomalyEvent payload = new AnomalyEvent(
                "evt-1", "", "HIGH", "",
                Instant.now(), "ERROR", "svc", "msg");
        final byte[] envelope = buildEnvelope("io.cortex.anomaly.v1", payload);

        assertThatThrownBy(() -> this.parser.parse(envelope))
                .isInstanceOf(ParseException.class)
                .extracting(ex -> ((ParseException) ex).reason())
                .isEqualTo(FailureReason.MISSING_DATA);
    }

    /**
     * A contract-shaped envelope with NO {@code data} block at all
     * must surface {@link FailureReason#MISSING_DATA}.
     *
     * @throws Exception on serialization failure
     */
    @Test
    void rejectsNullDataBlockAsMissingData() throws Exception {
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId("ce-id-2")
                .withSource(URI.create("/cortex/log-processor-service"))
                .withType("io.cortex.anomaly.v1")
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withSubject("tenant-q")
                .withDataContentType("application/json")
                .build();
        final byte[] bytes = new JsonFormat().serialize(envelope);

        assertThatThrownBy(() -> this.parser.parse(bytes))
                .isInstanceOf(ParseException.class)
                .extracting(ex -> ((ParseException) ex).reason())
                .isEqualTo(FailureReason.MISSING_DATA);
    }

    /**
     * A data block whose JSON does not deserialize into
     * {@link AnomalyEvent} must surface
     * {@link FailureReason#MISSING_DATA} via the JsonProcessingException
     * branch in {@link AnomalyEnvelopeParser}.
     *
     * @throws Exception on serialization failure
     */
    @Test
    void rejectsUndecodableDataBlockAsMissingData() throws Exception {
        final byte[] badData = "{\"ts\":\"not-an-instant\"}".getBytes(StandardCharsets.UTF_8);
        final CloudEvent envelope = CloudEventBuilder.v1()
                .withId("ce-id-3")
                .withSource(URI.create("/cortex/log-processor-service"))
                .withType("io.cortex.anomaly.v1")
                .withTime(OffsetDateTime.now(ZoneOffset.UTC))
                .withSubject("tenant-r")
                .withDataContentType("application/json")
                .withData(badData)
                .build();
        final byte[] bytes = new JsonFormat().serialize(envelope);

        assertThatThrownBy(() -> this.parser.parse(bytes))
                .isInstanceOf(ParseException.class)
                .extracting(ex -> ((ParseException) ex).reason())
                .isEqualTo(FailureReason.MISSING_DATA);
    }

    /**
     * A null {@code reason} on the Jackson constructor must coerce to
     * empty string (covers the null branch of the ternary in
     * {@link AnomalyEvent}).
     */
    @Test
    void nullReasonCoercesToEmptyString() {
        final AnomalyEvent event = new AnomalyEvent(
                "evt-7", "tenant-7", "HIGH", null,
                Instant.now(), "ERROR", "svc", "msg");

        assertThat(event.reason()).isEmpty();
    }
}
