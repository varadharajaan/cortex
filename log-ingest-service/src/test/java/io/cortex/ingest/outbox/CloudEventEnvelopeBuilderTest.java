package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.cloudevents.CloudEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CloudEventEnvelopeBuilder} (P4.4b / ADR-0026).
 *
 * <p>Pure-function bean: no Spring context, no mocks. Exercises the
 * CloudEvents 1.0 envelope contract end-to-end against a fixed
 * outbox row.</p>
 */
class CloudEventEnvelopeBuilderTest {

    /** Sample CloudEvents source URI. */
    private static final String SOURCE = "/cortex/log-ingest-service";

    /** Sample CloudEvents type identifier. */
    private static final String TYPE = "io.cortex.logs.event.v1";

    /** Fixed clock pinned to a known instant for deterministic assertions. */
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-06-02T10:30:00Z"), ZoneOffset.UTC);

    /** SUT - recreated per test for isolation. */
    private final CloudEventEnvelopeBuilder builder = new CloudEventEnvelopeBuilder(
            new OutboxPollerProperties(
                    new OutboxPollerProperties.PollerProps(true, 1_000L, 100, 250L, 60_000L),
                    new OutboxPollerProperties.CloudEventProps(SOURCE, TYPE)),
            this.clock);

    /** Default constructor used by JUnit. */
    CloudEventEnvelopeBuilderTest() {
        // no state
    }

    /**
     * Verifies every CloudEvents 1.0 attribute documented in the
     * builder's javadoc maps to the expected outbox column /
     * configuration value, and {@code data} is the UTF-8 payload
     * bytes verbatim.
     */
    @Test
    void buildsCloudEventEnvelopeFromOutboxRow() {
        final Instant createdAt = Instant.parse("2026-06-02T10:29:59Z");
        final String payload = "{\"eventId\":\"abc123\",\"hello\":\"world\"}";
        final OutboxEvent row = new OutboxEvent(
                42L,
                "cortex-dev",
                "abc123",
                payload,
                OutboxStatus.PENDING.name(),
                0,
                createdAt,
                null,
                createdAt,
                null);

        final CloudEvent ce = this.builder.toEnvelope(row);

        assertThat(ce.getSpecVersion().toString()).isEqualTo("1.0");
        assertThat(ce.getId()).isEqualTo("abc123");
        assertThat(ce.getSource()).isEqualTo(URI.create(SOURCE));
        assertThat(ce.getType()).isEqualTo(TYPE);
        assertThat(ce.getSubject()).isEqualTo("cortex-dev");
        assertThat(ce.getDataContentType()).isEqualTo("application/json");
        assertThat(ce.getTime()).isNotNull();
        assertThat(ce.getTime().toInstant()).isEqualTo(createdAt);
        assertThat(ce.getData()).isNotNull();
        assertThat(new String(ce.getData().toBytes(), StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    /**
     * Sanity-check that the builder echoes the row's
     * {@code eventId} as the CloudEvents {@code id} attribute so
     * the consumer can dedupe on the same key the producer used.
     */
    @Test
    void envelopeIdMirrorsOutboxEventId() {
        final OutboxEvent row = new OutboxEvent(
                7L,
                "cortex-dev",
                "evt-xyz-789",
                "{}",
                OutboxStatus.PENDING.name(),
                0,
                Instant.parse("2026-06-02T11:00:00Z"),
                null,
                Instant.parse("2026-06-02T11:00:00Z"),
                null);

        final CloudEvent ce = this.builder.toEnvelope(row);

        assertThat(ce.getId()).isEqualTo("evt-xyz-789");
    }
}
