package io.cortex.ingest.outbox;

/**
 * Outbox publish path abstraction (P4.4c / ADR-0027).
 *
 * <p>The P4.4b publisher path was a direct {@code KafkaTemplate} call inside
 * {@link OutboxPoller#publishOne}. P4.4c introduces this thin seam so the
 * production topic publish and the DLQ topic publish can be swapped at
 * configuration time via {@code @ConditionalOnProperty(name =
 * "cortex.outbox.publisher", havingValue = "...")} -- one Kafka
 * implementation ({@link KafkaOutboxPublisher}, default) and one Azure
 * Service Bus stub ({@link ServiceBusOutboxPublisher}, gated behind
 * {@code havingValue = "servicebus"}). The real Service Bus connector is
 * deferred to P10 infra phase; the gate exists today so the production
 * topology can be toggled without a code change when P10 lands.</p>
 *
 * <p>Both methods MUST block until the broker has acknowledged delivery
 * under the broker's strongest durability mode ({@code acks=all} for
 * Kafka) and MUST throw {@link RuntimeException} on failure. The poller
 * relies on the throw / no-throw signal to drive the row's
 * {@code attempts} / {@code next_attempt_at} lifecycle. Returning
 * normally without acknowledgment would silently break at-least-once
 * delivery.</p>
 *
 * @see OutboxPoller
 * @see FailureReason
 */
public interface OutboxEventPublisher {

    /**
     * Publishes the supplied CloudEvent envelope bytes to the
     * production outbox topic ({@value KafkaOutboxPublisher#PRODUCTION_TOPIC}
     * for the Kafka impl). Blocks until the broker acknowledges or throws.
     *
     * @param row         outbox row being published; supplies the
     *                    partition key (tenant id) and observability context
     * @param value       serialized CloudEvent envelope bytes (the same
     *                    bytes the consumer side will deserialize)
     * @param contentType envelope content-type for the Kafka record
     *                    header (e.g. {@code application/cloudevents+json})
     * @throws RuntimeException when the broker did not acknowledge inside
     *                          the per-publisher send-timeout budget
     */
    void publish(OutboxEvent row, byte[] value, String contentType);

    /**
     * Publishes the supplied CloudEvent envelope bytes to the DLQ topic
     * ({@value KafkaOutboxPublisher#DLQ_TOPIC} for the Kafka impl) with
     * the two failure-tracking headers the operator runbook expects:
     * {@code x-orig-topic} and {@code x-failure-reason}. Same blocking
     * contract as {@link #publish(OutboxEvent, byte[], String)}.
     *
     * @param row         outbox row being dead-lettered
     * @param value       serialized CloudEvent envelope bytes (unchanged
     *                    from the failed production publish so a downstream
     *                    operator can replay the row by-hand)
     * @param contentType envelope content-type for the Kafka record header
     * @param origTopic   name of the production topic the row could not be
     *                    published to (stamped on {@code x-orig-topic})
     * @param reason      short failure category from {@link FailureReason}
     *                    (stamped on {@code x-failure-reason})
     * @throws RuntimeException when even the DLQ publish failed; the
     *                          poller leaves the row at status
     *                          {@link OutboxStatus#PENDING} so the next
     *                          tick retries
     */
    void publishDlq(OutboxEvent row,
                    byte[] value,
                    String contentType,
                    String origTopic,
                    String reason);
}
