package io.cortex.agent;

import java.util.Collection;

/**
 * The send-side contract that host applications use to publish
 * {@link LogEntry} instances to a CORTEX cluster.
 *
 * <p>Implementations must be thread-safe. The contract is fail-soft:
 * implementations should swallow transport errors and log them through
 * SLF4J rather than propagating to the caller, so that an unreachable
 * CORTEX endpoint never crashes the host application.</p>
 *
 * <p>{@code AutoCloseable} is implemented so the client can be used
 * inside try-with-resources. {@link #close()} must drain pending events
 * before returning.</p>
 */
public interface CortexClient extends AutoCloseable {

    /**
     * Submits a single entry for delivery.
     *
     * <p>Implementations may buffer; the call is not required to perform
     * I/O synchronously.</p>
     *
     * @param entry entry to deliver; must not be {@code null}
     */
    void send(LogEntry entry);

    /**
     * Submits a batch of entries for delivery.
     *
     * @param entries entries to deliver; may be empty but must not be
     *                {@code null}
     */
    void sendBatch(Collection<LogEntry> entries);

    /**
     * Drains any buffered entries synchronously.
     *
     * <p>Returns once the underlying transport has either acknowledged
     * delivery or given up after the configured retries. Implementations
     * that do not buffer may treat this as a no-op.</p>
     */
    void flush();

    /**
     * Releases all transport resources and stops any background threads.
     *
     * <p>Must drain pending events before returning. Subsequent calls to
     * {@link #send(LogEntry)} after {@link #close()} have undefined
     * behaviour and may be silently discarded.</p>
     */
    @Override
    void close();
}
