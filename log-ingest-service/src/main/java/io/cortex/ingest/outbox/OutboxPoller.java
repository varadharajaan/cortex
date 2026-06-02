package io.cortex.ingest.outbox;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains PENDING {@link OutboxEvent} rows to the destination topic
 * {@value KafkaOutboxPublisher#PRODUCTION_TOPIC} as CloudEvent 1.0
 * structured-mode JSON envelopes (P4.4b / ADR-0026 + P4.4c /
 * ADR-0027).
 *
 * <p>At P4.4b this class performed the {@link
 * org.springframework.kafka.core.KafkaTemplate} send inline. At
 * P4.4c the send moved behind the {@link OutboxEventPublisher} seam
 * so the production publish + the DLQ publish can swap between
 * Kafka and Azure Service Bus via the {@code cortex.outbox.publisher}
 * property without touching this class (ADR-0027 D5). The poller
 * still owns the row's lifecycle: success -> PUBLISHED, transient
 * failure -> attempts++ with capped exponential backoff,
 * retry-exhausted (ADR-0027 D2) -> DLQ publish + status DEAD.</p>
 *
 * <p>All three Micrometer counters
 * ({@value OutboxMetrics#METRIC_PUBLISHED},
 * {@value OutboxMetrics#METRIC_FAILED},
 * {@value OutboxMetrics#METRIC_DLQ}) are written through
 * {@link OutboxMetrics} so cardinality stays bounded by the
 * {@code (topic, tenant_id, reason)} allowlist surface.</p>
 */
@Component
public class OutboxPoller {

    /** Destination Kafka topic; pinned by ADR-0026. */
    public static final String DEFAULT_TOPIC = KafkaOutboxPublisher.PRODUCTION_TOPIC;

    /** Slf4j logger. */
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);

    /** Maximum length of the {@code last_error} text persisted on failure. */
    private static final int LAST_ERROR_MAX_LEN = 1024;

    /** CloudEvents structured-mode JSON encoder (application/cloudevents+json). */
    private static final EventFormat JSON_FORMAT =
            EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);

    /** Repository facade for outbox row lifecycle mutations. */
    private final OutboxRepository repository;

    /** CloudEvents envelope builder. */
    private final CloudEventEnvelopeBuilder envelopeBuilder;

    /** Publisher seam (Kafka by default; Service Bus when gated in). */
    private final OutboxEventPublisher publisher;

    /** UTC clock for the per-tick {@code now}. */
    private final Clock clock;

    /** Bound configuration tree. */
    private final OutboxPollerProperties properties;

    /** Tagged-counter helper for the published / failed / dlq surface. */
    private final OutboxMetrics metrics;

    /**
     * Constructs the poller.
     *
     * @param repository      outbox row gateway
     * @param envelopeBuilder CloudEvents envelope builder
     * @param publisher       publisher seam (Kafka / Service Bus)
     * @param clock           UTC clock
     * @param properties      bound poller configuration
     * @param metrics         tagged-counter helper
     */
    public OutboxPoller(final OutboxRepository repository,
                        final CloudEventEnvelopeBuilder envelopeBuilder,
                        final OutboxEventPublisher publisher,
                        final Clock clock,
                        final OutboxPollerProperties properties,
                        final OutboxMetrics metrics) {
        this.repository = repository;
        this.envelopeBuilder = envelopeBuilder;
        this.publisher = publisher;
        this.clock = clock;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** Scheduled tick; short-circuits when the master switch is off. */
    @Scheduled(
            fixedDelayString = "${cortex.ingest.outbox.poller.fixed-delay-ms:1000}",
            initialDelayString = "${cortex.ingest.outbox.poller.fixed-delay-ms:1000}")
    public void tick() {
        if (!this.properties.poller().enabled()) {
            return;
        }
        try {
            this.drainOnce();
        } catch (RuntimeException ex) {
            LOG.warn("outbox-poller tick aborted by unexpected exception", ex);
        }
    }

    /**
     * Runs one drain cycle: fetch up to {@code batch-size} due
     * rows and attempt to publish each. Visible for testing.
     *
     * @return number of rows processed; zero when no rows are due
     */
    int drainOnce() {
        final Instant now = this.clock.instant();
        final int limit = Math.max(1, this.properties.poller().batchSize());
        final List<OutboxEvent> due = this.repository.findPendingDueForPublish(now, limit);
        if (due.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (final OutboxEvent row : due) {
            this.publishOne(row);
            processed++;
        }
        return processed;
    }

    /**
     * Publishes a single outbox row through the configured
     * {@link OutboxEventPublisher} seam. Splits the publish into
     * two stages so the failure-reason classification can
     * distinguish an envelope-build poison from a broker-side
     * transport error:
     *
     * <ol>
     *   <li>Build + serialize the CloudEvent envelope. Any
     *       exception here yields
     *       {@code reason={@link FailureReason#OUTBOX_POISON}} and
     *       the raw {@code outbox_events.payload} bytes are
     *       forwarded to the DLQ branch when retries exhaust.</li>
     *   <li>Hand the bytes to the publisher seam. Any exception
     *       there is classified via {@link FailureReason#fromThrowable}
     *       (timeout / interrupt / kafka.execute / unknown).</li>
     * </ol>
     *
     * @param row PENDING row due for publish
     * @return {@code true} when the row reached PUBLISHED; {@code false} otherwise
     */
    private boolean publishOne(final OutboxEvent row) {
        final byte[] envelopeBytes;
        try {
            final CloudEvent envelope = this.envelopeBuilder.toEnvelope(row);
            envelopeBytes = JSON_FORMAT.serialize(envelope);
        } catch (RuntimeException ex) {
            final byte[] rawPayload = row.payload() == null
                    ? new byte[0]
                    : row.payload().getBytes(StandardCharsets.UTF_8);
            this.handleFailure(row, ex, FailureReason.OUTBOX_POISON, rawPayload);
            LOG.warn("outbox-publish id={} eventId={} tenant={} envelope build failed attempts={} cause={}",
                    row.id(), row.eventId(), row.tenantId(),
                    row.attempts() + 1, ex.toString());
            return false;
        }

        try {
            this.publisher.publish(row, envelopeBytes, JsonFormat.CONTENT_TYPE);
            this.repository.markPublished(row.id(), this.clock.instant());
            this.metrics.incrementPublished(DEFAULT_TOPIC, row.tenantId());
            LOG.debug("outbox-publish id={} eventId={} tenant={} ok",
                    row.id(), row.eventId(), row.tenantId());
            return true;
        } catch (RuntimeException ex) {
            final String reason = FailureReason.fromThrowable(unwrap(ex));
            this.handleFailure(row, ex, reason, envelopeBytes);
            LOG.warn("outbox-publish id={} eventId={} tenant={} failed attempts={} reason={} cause={}",
                    row.id(), row.eventId(), row.tenantId(),
                    row.attempts() + 1, reason, ex.toString());
            return false;
        }
    }

    /**
     * Translates a publish failure into the row's lifecycle update:
     * always bumps {@code attempts} and the
     * {@value OutboxMetrics#METRIC_FAILED} counter; routes to the
     * DLQ + flips status to {@link OutboxStatus#DEAD} when
     * {@link OutboxPollerProperties.PollerProps#isRetryExhausted(int)}
     * returns true (ADR-0027 D2); otherwise reschedules the row
     * with capped exponential backoff.
     *
     * <p>If the DLQ publish itself fails, the row is left at
     * {@link OutboxStatus#PENDING} with bumped {@code attempts} so
     * the next tick retries the entire publish path. The
     * {@value OutboxMetrics#METRIC_DLQ} counter is NOT incremented
     * in that case (no record actually landed on the DLQ topic).</p>
     *
     * @param row           failed row
     * @param ex            originating exception
     * @param reason        short {@link FailureReason} category
     * @param envelopeBytes envelope bytes (or raw payload bytes
     *                      when the envelope build itself failed)
     *                      to forward on the DLQ record
     */
    private void handleFailure(final OutboxEvent row,
                               final Throwable ex,
                               final String reason,
                               final byte[] envelopeBytes) {
        final int newAttempts = row.attempts() + 1;
        final String truncated = truncate(formatLastError(ex));
        this.metrics.incrementFailed(DEFAULT_TOPIC, row.tenantId(), reason);

        if (!this.properties.poller().isRetryExhausted(newAttempts)) {
            final Instant nextAt = this.clock.instant()
                    .plus(this.properties.poller().nextBackoff(newAttempts));
            this.repository.markFailureAndReschedule(
                    row.id(), newAttempts, nextAt, truncated);
            return;
        }

        try {
            this.publisher.publishDlq(row, envelopeBytes, JsonFormat.CONTENT_TYPE,
                    DEFAULT_TOPIC, reason);
            this.repository.markDead(row.id(), newAttempts, truncated);
            this.metrics.incrementDlq(
                    KafkaOutboxPublisher.DLQ_TOPIC, row.tenantId(), reason);
            LOG.warn("outbox-dlq id={} eventId={} tenant={} attempts={} reason={} routed",
                    row.id(), row.eventId(), row.tenantId(), newAttempts, reason);
        } catch (RuntimeException dlqEx) {
            final Instant nextAt = this.clock.instant()
                    .plus(this.properties.poller().nextBackoff(newAttempts));
            final String dlqError = truncate("dlq-failed: " + formatLastError(dlqEx));
            this.repository.markFailureAndReschedule(
                    row.id(), newAttempts, nextAt, dlqError);
            LOG.warn("outbox-dlq id={} eventId={} tenant={} attempts={} DLQ publish FAILED cause={}; row stays PENDING",
                    row.id(), row.eventId(), row.tenantId(), newAttempts, dlqEx.toString());
        }
    }

    /**
     * Unwraps a wrapped exception so {@link FailureReason#fromThrowable}
     * sees the originating cause (e.g. a {@code TimeoutException}
     * wrapped in {@code IllegalStateException} by
     * {@link KafkaOutboxPublisher}).
     *
     * @param ex exception observed at the call site
     * @return cause if present, otherwise {@code ex}
     */
    private static Throwable unwrap(final Throwable ex) {
        if (ex == null) {
            return null;
        }
        final Throwable cause = ex.getCause();
        return cause == null ? ex : cause;
    }

    /**
     * Truncates the {@code lastError} string to the column ceiling
     * so a misbehaving stack trace cannot blow up the row width.
     *
     * @param text candidate text
     * @return text capped at {@value #LAST_ERROR_MAX_LEN} chars
     */
    private static String truncate(final String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= LAST_ERROR_MAX_LEN) {
            return text;
        }
        return text.substring(0, LAST_ERROR_MAX_LEN);
    }

    /**
     * Formats an exception as {@code <ClassName>: <message>}.
     *
     * @param ex exception to format
     * @return short error description
     */
    private static String formatLastError(final Throwable ex) {
        if (ex == null) {
            return "";
        }
        final String message = ex.getMessage() == null ? "" : ex.getMessage();
        return ex.getClass().getSimpleName() + ": " + message;
    }
}
