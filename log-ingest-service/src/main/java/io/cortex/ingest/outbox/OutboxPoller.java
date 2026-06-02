package io.cortex.ingest.outbox;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains PENDING {@link OutboxEvent} rows to the Kafka topic
 * {@value #DEFAULT_TOPIC} as CloudEvent 1.0 structured-mode JSON
 * envelopes (P4.4b / ADR-0026 / B9.2 override O8).
 *
 * <p>Publishes via {@link KafkaTemplate} directly (Spring Kafka
 * producer). SCSt's outbound binder path was prototyped but the
 * StreamBridge -> binder handler dispatch silently dropped the
 * message between the bound channel subscriber and the producer
 * network thread (no {@code PRODUCE} request ever reached the
 * broker). Synchronous {@code send(...).get(timeout)} on
 * KafkaTemplate gives deterministic delivery semantics: the row
 * is only flipped to PUBLISHED after the broker acknowledges per
 * the producer's {@code acks=all} setting. The SCSt binding
 * portability story moves to P4.4c when the Service Bus binder
 * lands (ADR-0027); at that point a thin
 * {@code OutboxEventPublisher} interface will be introduced.</p>
 */
@Component
public class OutboxPoller {

    /** Output Kafka topic; pinned by ADR-0026. */
    public static final String DEFAULT_TOPIC = "cortex.logs.events.v1";

    /** Micrometer counter for successfully published rows. */
    public static final String METRIC_PUBLISHED = "cortex.ingest.outbox.published";

    /** Micrometer counter for publish failures (per-attempt, NOT per-row). */
    public static final String METRIC_FAILED = "cortex.ingest.outbox.failed";

    /** Kafka record header carrying the CloudEvents content-type. */
    public static final String HEADER_CONTENT_TYPE = "content-type";

    /** Slf4j logger. */
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPoller.class);

    /** Maximum length of the {@code last_error} text persisted on failure. */
    private static final int LAST_ERROR_MAX_LEN = 1024;

    /** Max seconds to await broker ack before timing out an attempt. */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** CloudEvents structured-mode JSON encoder (application/cloudevents+json). */
    private static final EventFormat JSON_FORMAT =
            EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);

    /** Repository facade for outbox row lifecycle mutations. */
    private final OutboxRepository repository;

    /** CloudEvents envelope builder. */
    private final CloudEventEnvelopeBuilder envelopeBuilder;

    /** Spring Kafka producer template (byte[] key + byte[] value). */
    private final KafkaTemplate<byte[], byte[]> kafkaTemplate;

    /** UTC clock for the per-tick {@code now}. */
    private final Clock clock;

    /** Bound configuration tree. */
    private final OutboxPollerProperties properties;

    /** Cached published-counter handle. */
    private final Counter publishedCounter;

    /** Cached failed-counter handle. */
    private final Counter failedCounter;

    /**
     * Constructs the poller. Counters are registered eagerly so
     * they appear in {@code /actuator/prometheus} before the first
     * tick runs.
     *
     * @param repository      outbox row gateway
     * @param envelopeBuilder CloudEvents envelope builder
     * @param kafkaTemplate   Spring Kafka producer template
     * @param clock           UTC clock
     * @param properties      bound poller configuration
     * @param meterRegistry   Micrometer registry
     */
    public OutboxPoller(final OutboxRepository repository,
                        final CloudEventEnvelopeBuilder envelopeBuilder,
                        final KafkaTemplate<byte[], byte[]> kafkaTemplate,
                        final Clock clock,
                        final OutboxPollerProperties properties,
                        final MeterRegistry meterRegistry) {
        this.repository = repository;
        this.envelopeBuilder = envelopeBuilder;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.properties = properties;
        this.publishedCounter = Counter.builder(METRIC_PUBLISHED)
                .description("Outbox rows successfully published to Kafka")
                .register(meterRegistry);
        this.failedCounter = Counter.builder(METRIC_FAILED)
                .description("Outbox publish attempts that failed and were rescheduled")
                .register(meterRegistry);
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
     * Publishes a single outbox row synchronously, blocking on the
     * broker ack so the row is only marked PUBLISHED after delivery
     * is confirmed.
     *
     * @param row PENDING row due for publish
     * @return {@code true} when PUBLISHED; {@code false} when rescheduled
     */
    private boolean publishOne(final OutboxEvent row) {
        try {
            final CloudEvent envelope = this.envelopeBuilder.toEnvelope(row);
            final byte[] value = JSON_FORMAT.serialize(envelope);
            final byte[] key = row.tenantId().getBytes(StandardCharsets.UTF_8);
            final ProducerRecord<byte[], byte[]> record =
                    new ProducerRecord<>(DEFAULT_TOPIC, null, key, value);
            record.headers().add(new RecordHeader(HEADER_CONTENT_TYPE,
                    JsonFormat.CONTENT_TYPE.getBytes(StandardCharsets.UTF_8)));
            this.kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            this.repository.markPublished(row.id(), this.clock.instant());
            this.publishedCounter.increment();
            LOG.debug("outbox-publish id={} eventId={} tenant={} ok",
                    row.id(), row.eventId(), row.tenantId());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            this.recordFailure(row, formatLastError(ex));
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            this.recordFailure(row, formatLastError(ex));
            LOG.warn("outbox-publish id={} eventId={} tenant={} failed attempts={} cause={}",
                    row.id(), row.eventId(), row.tenantId(),
                    row.attempts() + 1, ex.toString());
            return false;
        }
    }

    /**
     * Records a publish failure: bumps {@code attempts}, pushes
     * {@code next_attempt_at}, persists a truncated error string.
     *
     * @param row       failed row
     * @param lastError short error description
     */
    private void recordFailure(final OutboxEvent row, final String lastError) {
        final int attempts = row.attempts() + 1;
        final Instant nextAt = this.clock.instant()
                .plus(this.properties.poller().nextBackoff(attempts));
        final String truncated;
        if (lastError == null) {
            truncated = "";
        } else if (lastError.length() > LAST_ERROR_MAX_LEN) {
            truncated = lastError.substring(0, LAST_ERROR_MAX_LEN);
        } else {
            truncated = lastError;
        }
        this.repository.markFailureAndReschedule(row.id(), attempts, nextAt, truncated);
        this.failedCounter.increment();
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
