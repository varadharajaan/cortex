package io.cortex.agent;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable structured log event shipped from a host application to a
 * CORTEX cluster.
 *
 * <p>Five canonical fields: timestamp, level, service, message, labels.
 * Anything else (tenant id, trace id, span id, custom dimensions)
 * travels inside {@link #labels()} as a flat string map. This matches
 * Loki / Quickwit / PG-GIN indexing conventions where labels are
 * indexable key / value pairs.</p>
 *
 * <p>{@code labels} is defensively copied and exposed as unmodifiable
 * so the record stays immutable when callers mutate the input map
 * after construction.</p>
 *
 * @param timestamp UTC instant the event occurred; must not be {@code null}
 * @param level     severity; must not be {@code null}
 * @param service   logical service / app name; must not be blank
 * @param message   human-readable message; must not be {@code null}
 * @param labels    additional structured key / value labels; may be
 *                  empty but never {@code null} after construction
 */
public record LogEntry(
        Instant timestamp,
        LogLevel level,
        String service,
        String message,
        Map<String, String> labels) {

    /** Conventional label key for the tenant identifier. */
    public static final String LABEL_TENANT = "tenant";

    /** Conventional label key for the distributed-trace identifier. */
    public static final String LABEL_TRACE_ID = "trace_id";

    /**
     * Canonical constructor with validation and defensive copy of
     * {@link #labels}.
     */
    public LogEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
        if (service == null || service.isBlank()) {
            throw new IllegalArgumentException("service must not be blank");
        }
        if (labels == null) {
            labels = Collections.emptyMap();
        } else {
            labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        }
    }

    /**
     * Convenience factory for the common case of an {@code INFO} event
     * with no extra labels.
     *
     * @param service logical service / app name
     * @param message human-readable message
     * @return a new {@link LogEntry} stamped with {@link Instant#now()}
     */
    public static LogEntry info(final String service, final String message) {
        return new LogEntry(
                Instant.now(),
                LogLevel.INFO,
                service,
                message,
                Map.of());
    }

    /**
     * Returns the value of the conventional {@code tenant} label, or
     * {@code null} if absent.
     *
     * @return tenant id label value, or {@code null}
     */
    public String tenantId() {
        return this.labels.get(LABEL_TENANT);
    }

    /**
     * Returns the value of the conventional {@code trace_id} label, or
     * {@code null} if absent.
     *
     * @return trace id label value, or {@code null}
     */
    public String traceId() {
        return this.labels.get(LABEL_TRACE_ID);
    }
}
