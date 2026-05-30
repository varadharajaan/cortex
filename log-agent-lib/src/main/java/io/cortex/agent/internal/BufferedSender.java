package io.cortex.agent.internal;

import io.cortex.agent.CortexClient;
import io.cortex.agent.LogEntry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory buffered decorator around another {@link CortexClient}.
 *
 * <p>Buffers entries in a bounded queue and flushes either when the
 * queue hits {@code batchSize} or when a scheduled tick fires every
 * {@code flushInterval}. When the queue is full, new entries are
 * dropped at the call site and a warning is logged: the SDK never
 * blocks the host application thread.</p>
 *
 * <p>Thread-safe. {@link #close()} drains pending entries and shuts
 * down the background scheduler.</p>
 */
public final class BufferedSender implements CortexClient {

    /** SLF4J logger for diagnostic messages. */
    private static final Logger LOG = LoggerFactory.getLogger(BufferedSender.class);

    /** Queue capacity headroom multiplier over {@code batchSize}. */
    private static final int QUEUE_CAPACITY_MULTIPLIER = 8;

    /** Delegate used to actually send drained batches. */
    private final CortexClient delegate;

    /** Bounded FIFO queue holding pending entries. */
    private final BlockingQueue<LogEntry> queue;

    /** Background scheduler firing periodic flushes. */
    private final ScheduledExecutorService scheduler;

    /** Target batch size; the queue flushes when it reaches this count. */
    private final int batchSize;

    /** Closed flag to suppress sends after {@link #close()}. */
    private final AtomicBoolean closed;

    /**
     * Creates a buffered sender wrapping the given delegate.
     *
     * @param delegate      underlying client that ships drained batches
     * @param batchSize     drain threshold (>= 1)
     * @param flushInterval scheduled drain interval (> 0)
     * @throws IllegalArgumentException if {@code batchSize} is below 1 or
     *                                  {@code flushInterval} is zero or negative
     */
    public BufferedSender(
            final CortexClient delegate,
            final int batchSize,
            final Duration flushInterval) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        Objects.requireNonNull(flushInterval, "flushInterval");
        if (flushInterval.isZero() || flushInterval.isNegative()) {
            throw new IllegalArgumentException("flushInterval must be > 0");
        }
        this.batchSize = batchSize;
        this.queue = new ArrayBlockingQueue<>(batchSize * QUEUE_CAPACITY_MULTIPLIER);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newDaemonThread);
        this.closed = new AtomicBoolean(false);
        this.scheduler.scheduleAtFixedRate(
                this::flushQuietly,
                flushInterval.toMillis(),
                flushInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void send(final LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (this.closed.get()) {
            return;
        }
        if (!this.queue.offer(entry)) {
            LOG.warn("CORTEX buffer full; dropping entry service={} level={}",
                    entry.service(), entry.level());
            return;
        }
        if (this.queue.size() >= this.batchSize) {
            this.flushQuietly();
        }
    }

    @Override
    public void sendBatch(final Collection<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        for (LogEntry entry : entries) {
            this.send(entry);
        }
    }

    @Override
    public void flush() {
        final List<LogEntry> drained = new ArrayList<>(this.batchSize);
        this.queue.drainTo(drained);
        if (drained.isEmpty()) {
            return;
        }
        this.delegate.sendBatch(drained);
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.scheduler.shutdown();
        try {
            if (!this.scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                this.scheduler.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            this.scheduler.shutdownNow();
        }
        this.flushQuietly();
        this.delegate.close();
    }

    /**
     * Flushes the queue and swallows any unchecked exception so a single
     * bad flush never kills the scheduler thread.
     */
    private void flushQuietly() {
        try {
            this.flush();
        } catch (RuntimeException ex) {
            LOG.warn("CORTEX flush failed: {}", ex.getMessage());
        }
    }

    /**
     * Thread factory that produces named daemon threads so the scheduler
     * never blocks JVM shutdown if {@link #close()} is forgotten.
     *
     * @param task scheduled task to wrap
     * @return daemon thread
     */
    private Thread newDaemonThread(final Runnable task) {
        final Thread thread = new Thread(task, "cortex-agent-flush");
        thread.setDaemon(true);
        return thread;
    }
}
