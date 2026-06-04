package io.cortex.remediation.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventDeserializationException;
import io.cloudevents.jackson.JsonFormat;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Decode CloudEvents 1.0 structured-mode JSON envelopes off
 * {@code cortex.anomalies.v1} into typed {@link AnomalyEvent}
 * records (P6.0 / ADR-0032 D2).
 *
 * <p>Mirrors the upstream producer
 * {@code log-processor-service.AnomaliesPublisher} contract exactly
 * (P5.4 / ADR-0031 D3): the record value is the full CloudEvent JSON
 * including a deterministic 8-field {@code data} object. Defensive
 * envelope guards per {@code docs/p5-to-p6-handoff.md} section 3:
 * {@code specversion} must equal {@code "1.0"} and {@code type} must
 * equal {@code "io.cortex.anomaly.v1"}; anything else raises a
 * {@link ParseException} so the future P6.4 DLQ writer can route it
 * to {@code cortex.anomalies.v1.dlq} with
 * {@code x-failure-reason=wrong_type}.</p>
 *
 * <p>Thread-safe: the {@link ObjectMapper} + {@link JsonFormat}
 * instances are immutable after construction; multiple consumer
 * threads in P6.x (when concurrency lands) call {@link #parse} in
 * parallel.</p>
 */
@Component
public class AnomalyEnvelopeParser {

    /** CloudEvents 1.0 {@code specversion} the producer guarantees. */
    public static final String EXPECTED_SPECVERSION = "1.0";

    /** CloudEvents {@code type} the upstream producer stamps. */
    public static final String EXPECTED_TYPE = "io.cortex.anomaly.v1";

    private final ObjectMapper objectMapper;
    private final JsonFormat jsonFormat;

    /**
     * Spring constructor.
     *
     * @param objectMapper autoconfigured Jackson mapper used to
     *                     deserialise the inner {@code data} object
     *                     into the typed {@link AnomalyEvent}
     */
    public AnomalyEnvelopeParser(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonFormat = new JsonFormat();
    }

    /**
     * Decode the supplied record value bytes into an
     * {@link AnomalyEvent}.
     *
     * @param payload raw record value polled off
     *                {@code cortex.anomalies.v1}; must be non-null
     *                and non-empty (the consumer skips empty records
     *                before reaching this method)
     * @return typed {@link AnomalyEvent}
     * @throws ParseException with {@link FailureReason#INVALID_ENVELOPE}
     *                        on CloudEvents JSON decode failure;
     *                        {@link FailureReason#WRONG_TYPE} when
     *                        the envelope {@code type} or
     *                        {@code specversion} does not match the
     *                        contract; {@link FailureReason#MISSING_DATA}
     *                        when the envelope decoded but the
     *                        {@code data} block is null or could not
     *                        be reshaped into {@link AnomalyEvent}
     */
    public AnomalyEvent parse(final byte[] payload) throws ParseException {
        final CloudEvent envelope = decodeEnvelope(payload);
        validateEnvelopeShape(envelope);
        final AnomalyEvent event = decodeData(envelope);
        if (StringUtils.isBlank(event.eventId())
                || StringUtils.isBlank(event.tenantId())) {
            throw new ParseException(FailureReason.MISSING_DATA,
                    "data block missing required eventId/tenantId");
        }
        return event;
    }

    /**
     * Decodes the raw CloudEvents JSON envelope bytes; surfaces a
     * {@link FailureReason#INVALID_ENVELOPE} on any wire-format
     * decode failure.
     *
     * @param payload raw CloudEvent envelope bytes
     * @return the decoded {@link CloudEvent}
     * @throws ParseException with {@link FailureReason#INVALID_ENVELOPE}
     *                        on decode failure
     */
    private CloudEvent decodeEnvelope(final byte[] payload) throws ParseException {
        try {
            return this.jsonFormat.deserialize(payload);
        } catch (EventDeserializationException ex) {
            throw new ParseException(FailureReason.INVALID_ENVELOPE,
                    "CloudEvents envelope failed to decode", ex);
        }
    }

    /**
     * Enforces the static envelope contract: {@code specversion="1.0"}
     * and {@code type="io.cortex.anomaly.v1"}. Misses raise
     * {@link FailureReason#WRONG_TYPE}.
     *
     * @param envelope the decoded {@link CloudEvent} to validate
     * @throws ParseException with {@link FailureReason#WRONG_TYPE}
     *                        if {@code specversion} or {@code type} drifts
     */
    private void validateEnvelopeShape(final CloudEvent envelope) throws ParseException {
        if (!EXPECTED_SPECVERSION.equals(envelope.getSpecVersion().toString())) {
            throw new ParseException(FailureReason.WRONG_TYPE,
                    "Unexpected CloudEvents specversion="
                            + envelope.getSpecVersion());
        }
        if (!EXPECTED_TYPE.equals(envelope.getType())) {
            throw new ParseException(FailureReason.WRONG_TYPE,
                    "Unexpected CloudEvents type=" + envelope.getType());
        }
    }

    /**
     * Reshapes the CloudEvents {@code data} block into the typed
     * {@link AnomalyEvent}; raises {@link FailureReason#MISSING_DATA}
     * on null / empty / undecodable payloads.
     *
     * @param envelope the decoded {@link CloudEvent} carrying the {@code data} block
     * @return the typed {@link AnomalyEvent}
     * @throws ParseException with {@link FailureReason#MISSING_DATA}
     *                        when the data block is missing or undecodable
     */
    private AnomalyEvent decodeData(final CloudEvent envelope) throws ParseException {
        if (envelope.getData() == null) {
            throw new ParseException(FailureReason.MISSING_DATA,
                    "CloudEvents envelope has null data block");
        }
        final byte[] data = envelope.getData().toBytes();
        if (data == null || data.length == 0) {
            throw new ParseException(FailureReason.MISSING_DATA,
                    "CloudEvents envelope has empty data block");
        }
        try {
            return this.objectMapper.readValue(data, AnomalyEvent.class);
        } catch (JsonProcessingException ex) {
            throw new ParseException(FailureReason.MISSING_DATA,
                    "data block did not decode into AnomalyEvent", ex);
        } catch (java.io.IOException ex) {
            throw new ParseException(FailureReason.MISSING_DATA,
                    "data block I/O failure", ex);
        }
    }
}
