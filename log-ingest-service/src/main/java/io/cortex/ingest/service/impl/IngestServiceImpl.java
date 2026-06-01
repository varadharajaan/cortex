package io.cortex.ingest.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.agent.LogEntry;
import io.cortex.ingest.dedupe.IdempotencyDedupeService;
import io.cortex.ingest.dto.request.IngestBatchRequest;
import io.cortex.ingest.dto.response.IngestAcceptedResponse;
import io.cortex.ingest.persistence.RawLog;
import io.cortex.ingest.persistence.RawLogRepository;
import io.cortex.ingest.service.IngestService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.stereotype.Service;

/**
 * Persisting implementation of {@link IngestService} (P4.1 / D3 /
 * D7 / ADR-0022).
 *
 * <p>For every inbound {@link LogEntry} the service computes a
 * server-side {@code event_id} as the SHA-256 hex of
 * {@code tenantId | service | ts.epochMicros | message |
 * sortedLabelsJson} and writes a {@link RawLog} row to the
 * {@code raw_logs} table. The {@code UNIQUE (tenant_id, event_id)}
 * constraint silently absorbs cold-path duplicates: a per-row
 * {@link DuplicateKeyException} is logged at DEBUG and the loop
 * continues so the rest of the batch still persists.</p>
 *
 * <p>The acknowledgement returned to the caller mirrors the
 * inbound entry count ({@code receivedCount = entries.size()}) so
 * dedupe is transparent to the client. The first call against a
 * fresh batch INSERTs {@code N} rows; the second call against the
 * same batch INSERTs 0 rows and still returns the same
 * {@code receivedCount}, giving callers PUT-style idempotency
 * without an explicit Idempotency-Key today.</p>
 *
 * <p>Persistence is intentionally NOT wrapped in a single
 * {@code @Transactional} boundary so that one duplicate row does
 * not roll back the rest of the batch. Each {@link
 * RawLogRepository#save(Object)} call runs in its own auto-commit
 * transaction; {@link DuplicateKeyException} is caught per row.</p>
 */
@Service
public class IngestServiceImpl implements IngestService {

    /** Logger; kept private static final per LOGGERS_ARE_PRIVATE_STATIC_FINAL. */
    private static final Logger LOG = LoggerFactory.getLogger(IngestServiceImpl.class);

    /** Microseconds per second; used when packing event timestamps. */
    private static final long MICROS_PER_SECOND = 1_000_000L;

    /** Nanos per microsecond; used when packing event timestamps. */
    private static final long NANOS_PER_MICRO = 1_000L;

    /** Hex characters per SHA-256 digest byte. */
    private static final int HEX_PER_BYTE = 2;

    /** Low-byte mask used when widening a signed {@code byte} to {@code int} for hex encoding. */
    private static final int BYTE_MASK = 0xff;

    /** Field separator inside the event-id pre-image. */
    private static final String FIELD_SEPARATOR = "|";

    /** Clock used for the acceptance timestamp; injected so tests can pin it. */
    private final Clock clock;

    /** Spring Data JDBC gateway to the {@code raw_logs} table. */
    private final RawLogRepository repository;

    /** Jackson encoder used to canonicalise labels for the event-id hash. */
    private final ObjectMapper objectMapper;

    /**
     * Optional hot-path dedupe (D3 / P4.2); empty when
     * {@code cortex.ingest.dedupe.enabled=false} or when Spring did
     * not wire the bean (e.g. test slices that exclude Redis).
     */
    private final Optional<IdempotencyDedupeService> dedupeService;

    /**
     * Constructs the persisting service implementation.
     *
     * @param clock         clock used for the acceptance timestamp;
     *                      must not be {@code null}
     * @param repository    raw-logs JDBC repository; must not be
     *                      {@code null}
     * @param objectMapper  shared Jackson encoder used to
     *                      canonicalise the {@code labels} map for
     *                      the event-id pre-image; must not be
     *                      {@code null}
     * @param dedupeService optional hot-path dedupe (D3); empty when
     *                      the bean is disabled or not on the
     *                      classpath
     */
    public IngestServiceImpl(final Clock clock,
                             final RawLogRepository repository,
                             final ObjectMapper objectMapper,
                             final Optional<IdempotencyDedupeService> dedupeService) {
        this.clock = clock;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.dedupeService = dedupeService;
    }

    @Override
    public IngestAcceptedResponse acceptBatch(final IngestBatchRequest request,
                                              final String tenantId,
                                              final String idempotencyKey) {
        final OffsetDateTime receivedAt = OffsetDateTime.now(this.clock);
        final int total = request.entries().size();
        if (this.isHotPathHit(tenantId, idempotencyKey)) {
            LOG.info("ingest-batch dedupe-hot-path tenant={} accepted={}",
                    tenantId, total);
            return new IngestAcceptedResponse(total, receivedAt);
        }
        final Instant receivedAtInstant = receivedAt.toInstant();
        int persistedCount = 0;
        for (final LogEntry entry : request.entries()) {
            final String eventId = this.computeEventId(tenantId, entry);
            final RawLog raw = this.toRawLog(entry, tenantId, eventId, idempotencyKey,
                    receivedAtInstant);
            persistedCount += this.saveAbsorbingDuplicate(raw, tenantId, eventId);
        }
        if (persistedCount < total) {
            LOG.info("ingest-batch dedupe-absorbed tenant={} accepted={} duplicates={}",
                    tenantId, total, total - persistedCount);
        }
        return new IngestAcceptedResponse(total, receivedAt);
    }

    /**
     * Returns {@code true} when the hot-path dedupe layer has
     * already seen this {@code (tenantId, idempotencyKey)} pair
     * within its TTL window. Returns {@code false} when the header
     * is absent or blank, the dedupe bean is not wired in
     * ({@code cortex.ingest.dedupe.enabled=false}), or the key is
     * freshly claimed by this very call (in which case the caller
     * MUST proceed with persistence).
     *
     * @param tenantId       resolved tenant id
     * @param idempotencyKey raw {@code Idempotency-Key} header value
     *                       (may be {@code null} or blank)
     * @return {@code true} only when the call is a recognised replay
     */
    private boolean isHotPathHit(final String tenantId, final String idempotencyKey) {
        if (this.dedupeService.isEmpty()) {
            return false;
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        return !this.dedupeService.get().claim(tenantId, idempotencyKey);
    }

    /**
     * Persists one row, absorbing the cold-path duplicate signalled
     * by the {@code UNIQUE (tenant_id, event_id)} constraint.
     *
     * <p>Spring Data JDBC wraps the underlying
     * {@link DuplicateKeyException} in a
     * {@link DbActionExecutionException}; both shapes are unwrapped
     * here so dedupe is uniform regardless of which path the
     * relational module takes. Any other
     * {@link DbActionExecutionException} cause is rethrown so the
     * caller sees the real DB error.</p>
     *
     * @param raw      aggregate to insert
     * @param tenantId tenant id (logged on dedupe)
     * @param eventId  computed event id (logged on dedupe)
     * @return {@code 1} if the row was inserted, {@code 0} if a
     *         duplicate was absorbed
     * @throws DbActionExecutionException if the wrapped cause is not
     *                                    a {@link DuplicateKeyException}
     */
    private int saveAbsorbingDuplicate(final RawLog raw,
                                       final String tenantId,
                                       final String eventId) {
        try {
            this.repository.save(raw);
            return 1;
        } catch (DuplicateKeyException dup) {
            LOG.debug("ingest-batch dedupe-cold-path tenant={} eventId={}",
                    tenantId, eventId);
            return 0;
        } catch (DbActionExecutionException wrapped) {
            if (wrapped.getCause() instanceof DuplicateKeyException) {
                LOG.debug("ingest-batch dedupe-cold-path tenant={} eventId={}",
                        tenantId, eventId);
                return 0;
            }
            throw wrapped;
        }
    }

    /**
     * Maps a validated {@link LogEntry} to a new {@link RawLog}
     * insert aggregate. Extracted from {@link #acceptBatch} to keep
     * that loop under the 30-line Checkstyle ceiling.
     *
     * @param entry             validated inbound entry
     * @param tenantId          resolved tenant id
     * @param eventId           pre-computed SHA-256 hex event id
     * @param idempotencyKey    raw {@code Idempotency-Key} header value
     *                          (may be {@code null})
     * @param receivedAtInstant batch acceptance timestamp
     * @return new {@link RawLog} ready for {@code repository.save(...)}
     */
    private RawLog toRawLog(final LogEntry entry,
                            final String tenantId,
                            final String eventId,
                            final String idempotencyKey,
                            final Instant receivedAtInstant) {
        return new RawLog(
                null,
                tenantId,
                eventId,
                entry.timestamp(),
                entry.level().name(),
                entry.service(),
                entry.message(),
                entry.labels(),
                idempotencyKey,
                receivedAtInstant);
    }

    /**
     * Computes the server-side dedupe key for a single log entry.
     *
     * <p>The pre-image concatenates {@code tenantId}, {@code service},
     * the event timestamp in epoch microseconds, {@code message}, and
     * a canonical (sorted-key) JSON encoding of the labels. SHA-256
     * collapses the pre-image to a stable 64-char hex digest matching
     * the {@code raw_logs.event_id VARCHAR(64)} column width.</p>
     *
     * @param tenantId resolved tenant id; must not be {@code null}
     * @param entry    inbound log entry; must not be {@code null}
     * @return 64-char lowercase SHA-256 hex digest
     */
    private String computeEventId(final String tenantId, final LogEntry entry) {
        final Instant ts = entry.timestamp();
        final long micros = ts.getEpochSecond() * MICROS_PER_SECOND
                + ts.getNano() / NANOS_PER_MICRO;
        final String preimage = String.join(
                FIELD_SEPARATOR,
                tenantId,
                entry.service(),
                Long.toString(micros),
                entry.message(),
                this.canonicaliseLabels(entry.labels()));
        return sha256Hex(preimage);
    }

    /**
     * Renders the labels as a deterministic JSON object with keys in
     * lexical order.
     *
     * @param labels source label map; may be empty but never
     *               {@code null} (the {@link LogEntry} canonical
     *               constructor defensive-copies into an immutable
     *               map)
     * @return canonical JSON object string (e.g. {@code "{}"} for an
     *         empty map)
     * @throws IllegalStateException if Jackson cannot serialise the
     *                               sorted label map; this is a
     *                               programmer error (label keys and
     *                               values are bean-validated strings)
     *                               and is rethrown so the bean-creation
     *                               failure surfaces verbatim
     */
    private String canonicaliseLabels(final Map<String, String> labels) {
        if (labels.isEmpty()) {
            return "{}";
        }
        final Map<String, String> sorted = new TreeMap<>(labels);
        try {
            return this.objectMapper.writeValueAsString(sorted);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to canonicalise labels for event-id", ex);
        }
    }

    /**
     * Returns the lowercase SHA-256 hex digest of the UTF-8 encoding
     * of the supplied string.
     *
     * @param input non-null pre-image
     * @return 64-char lowercase hex digest
     * @throws IllegalStateException if the JVM does not advertise the
     *                               {@code SHA-256} {@link MessageDigest}
     *                               algorithm; this is impossible on
     *                               any compliant Java 17 runtime and
     *                               we rethrow so the bean wiring
     *                               surfaces the misconfiguration
     */
    private static String sha256Hex(final String input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * HEX_PER_BYTE);
            for (final byte b : digest) {
                final String byteHex = Integer.toHexString(b & BYTE_MASK);
                if (byteHex.length() == 1) {
                    hex.append('0');
                }
                hex.append(byteHex);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", ex);
        }
    }
}
