package io.cortex.ingest.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub {@link OutboxEventPublisher} for the Azure Service Bus binder
 * (P4.4c / ADR-0027 D5 / D6).
 *
 * <p>Gated behind {@code cortex.outbox.publisher=servicebus}. The real
 * Azure Service Bus connector + its SDK dependencies land in the P10
 * infra phase; today's stub exists to prove the
 * {@code @ConditionalOnProperty} gate works end-to-end (booting with
 * the property set to {@code servicebus} wires THIS bean and NOT
 * {@link KafkaOutboxPublisher}, so any publish attempt fails fast with
 * a clear "not yet implemented" message rather than silently doing
 * nothing).</p>
 *
 * <p>Both methods throw {@link UnsupportedOperationException} to make
 * the deferred state explicit. The poller treats this exception the
 * same as any other publish failure: bump {@code attempts}, reschedule
 * the row, and (eventually) DLQ it. So a misconfigured deployment with
 * {@code cortex.outbox.publisher=servicebus} but no real connector
 * will NOT lose data -- it will park every row in PENDING and surface
 * the situation via the {@code cortex.ingest.outbox.failed} counter.</p>
 */
@Component
@ConditionalOnProperty(name = "cortex.outbox.publisher",
        havingValue = "servicebus")
public class ServiceBusOutboxPublisher implements OutboxEventPublisher {

    /** Slf4j logger. */
    private static final Logger LOG =
            LoggerFactory.getLogger(ServiceBusOutboxPublisher.class);

    /** Default constructor; nothing to wire until the real SDK lands. */
    public ServiceBusOutboxPublisher() {
        LOG.warn("ServiceBusOutboxPublisher stub wired -- real Azure SB connector "
                + "is deferred to P10 infra phase; every publish attempt will throw "
                + "UnsupportedOperationException and the row will retry forever");
    }

    @Override
    public void publish(final OutboxEvent row,
                        final byte[] value,
                        final String contentType) {
        throw new UnsupportedOperationException(
                "ServiceBusOutboxPublisher.publish is a P4.4c stub; real "
                + "Azure Service Bus connector lands in P10 infra phase. "
                + "Set cortex.outbox.publisher=kafka (or remove the property) "
                + "to publish via the Kafka path.");
    }

    @Override
    public void publishDlq(final OutboxEvent row,
                           final byte[] value,
                           final String contentType,
                           final String origTopic,
                           final String reason) {
        throw new UnsupportedOperationException(
                "ServiceBusOutboxPublisher.publishDlq is a P4.4c stub; real "
                + "Azure Service Bus DLQ connector lands in P10 infra phase.");
    }
}
