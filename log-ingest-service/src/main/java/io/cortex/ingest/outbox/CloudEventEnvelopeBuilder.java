package io.cortex.ingest.outbox;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Wraps an {@link OutboxEvent} row's JSON payload in a CloudEvents
 * 1.0 envelope (P4.4b / ADR-0026 / B9.2 override O8).
 *
 * <p>Envelope contract:</p>
 * <ul>
 *   <li>{@code specversion} -- {@code "1.0"} (set by the builder)</li>
 *   <li>{@code id} -- mirrors {@link OutboxEvent#eventId()} so the
 *       consumer can dedupe end-to-end on the same key the producer
 *       used</li>
 *   <li>{@code source} -- from {@link OutboxPollerProperties.CloudEventProps#source()}</li>
 *   <li>{@code type} -- from {@link OutboxPollerProperties.CloudEventProps#type()}</li>
 *   <li>{@code time} -- {@link OutboxEvent#createdAt()} (ingest-time, not publish-time)</li>
 *   <li>{@code datacontenttype} -- {@code application/json}</li>
 *   <li>{@code subject} -- {@link OutboxEvent#tenantId()}</li>
 *   <li>{@code data} -- the outbox row's payload bytes, UTF-8</li>
 * </ul>
 *
 * <p>The {@code dataschema} attribute is intentionally NOT
 * populated -- it lights up in P14 when the migration to Avro +
 * Schema Registry replaces this JSON envelope (per O8 migration
 * path documented in ADR-0026).</p>
 */
@Component
public class CloudEventEnvelopeBuilder {

    /** Tunable envelope source / type. */
    private final OutboxPollerProperties properties;

    /** UTC clock injected for testability; not used today but reserved. */
    private final Clock clock;

    /**
     * Constructs the builder.
     *
     * @param properties bound CloudEvent envelope properties; must
     *                   not be {@code null}
     * @param clock      UTC clock; reserved for future use, never
     *                   {@code null}
     */
    public CloudEventEnvelopeBuilder(final OutboxPollerProperties properties,
                                     final Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Builds a CloudEvent 1.0 envelope from the supplied outbox row.
     *
     * @param row source outbox row; must not be {@code null} and
     *            must carry non-null {@code eventId}, {@code tenantId},
     *            {@code payload}, and {@code createdAt}
     * @return immutable {@link CloudEvent} ready to publish via
     *         {@code StreamBridge}
     */
    public CloudEvent toEnvelope(final OutboxEvent row) {
        final URI source = URI.create(this.properties.cloudevent().source());
        return CloudEventBuilder.v1()
                .withId(row.eventId())
                .withSource(source)
                .withType(this.properties.cloudevent().type())
                .withTime(OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC))
                .withDataContentType("application/json")
                .withSubject(row.tenantId())
                .withData(row.payload().getBytes(StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Returns the injected clock so subclasses or callers can read
     * the same time source the builder would use; reserved for
     * future use.
     *
     * @return injected UTC clock
     */
    protected Clock clock() {
        return this.clock;
    }
}
