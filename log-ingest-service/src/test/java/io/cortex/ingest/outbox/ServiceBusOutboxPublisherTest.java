package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceBusOutboxPublisher} (P4.4c /
 * ADR-0027 D5 / D6).
 *
 * <p>Pins the stub contract: both methods MUST throw
 * {@link UnsupportedOperationException} so the poller can treat a
 * misconfigured deployment ({@code cortex.outbox.publisher=servicebus}
 * with no real Azure SB connector yet) as a publish failure and
 * eventually route the row to DLQ rather than silently dropping it.</p>
 */
class ServiceBusOutboxPublisherTest {

    /** SUT. */
    private final ServiceBusOutboxPublisher publisher = new ServiceBusOutboxPublisher();

    /** Default constructor used by JUnit. */
    ServiceBusOutboxPublisherTest() {
        // no state
    }

    /** {@code publish} throws with the documented stub message. */
    @Test
    void publishThrowsUnsupportedWithStubMessage() {
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> this.publisher.publish(row(),
                        new byte[]{1}, "application/cloudevents+json"))
                .withMessageContaining("P4.4c stub");
    }

    /** {@code publishDlq} throws with the documented stub message. */
    @Test
    void publishDlqThrowsUnsupportedWithStubMessage() {
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> this.publisher.publishDlq(row(),
                        new byte[]{1}, "application/cloudevents+json",
                        KafkaOutboxPublisher.PRODUCTION_TOPIC,
                        FailureReason.KAFKA_TIMEOUT))
                .withMessageContaining("P4.4c stub");
    }

    /**
     * Builds a deterministic PENDING outbox row for the assertions.
     *
     * @return a synthetic PENDING OutboxEvent for the SUT
     */
    private static OutboxEvent row() {
        return new OutboxEvent(
                1L,
                "cortex-dev",
                "evt-1",
                "{}",
                OutboxStatus.PENDING.name(),
                0,
                Instant.parse("2026-06-02T12:00:00Z"),
                null,
                Instant.parse("2026-06-02T12:00:00Z"),
                null);
    }
}
