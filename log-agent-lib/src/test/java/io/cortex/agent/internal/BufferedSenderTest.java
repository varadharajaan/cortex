package io.cortex.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.cortex.agent.CortexClient;
import io.cortex.agent.LogEntry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BufferedSender}.
 */
class BufferedSenderTest {

    /** Recording delegate that captures every batch it receives. */
    private static final class RecordingDelegate implements CortexClient {

        /** Captured batches in order received. */
        private final List<List<LogEntry>> batches = Collections.synchronizedList(new ArrayList<>());

        /** Number of times {@link #close()} was invoked. */
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public void send(final LogEntry entry) {
            this.batches.add(List.of(entry));
        }

        @Override
        public void sendBatch(final Collection<LogEntry> entries) {
            this.batches.add(new ArrayList<>(entries));
        }

        @Override
        public void flush() {
            // Recording delegate has no buffer.
        }

        @Override
        public void close() {
            this.closeCount.incrementAndGet();
        }

        /**
         * Returns the total entries received across all batches.
         *
         * @return entry count summed across recorded batches
         */
        int totalEntries() {
            synchronized (this.batches) {
                return this.batches.stream().mapToInt(List::size).sum();
            }
        }
    }

    /** Constructor rejects bad parameters fast. */
    @Test
    void constructorRejectsBadArguments() {
        final RecordingDelegate delegate = new RecordingDelegate();
        assertThatThrownBy(() -> new BufferedSender(null, 1, Duration.ofMillis(100)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BufferedSender(delegate, 0, Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BufferedSender(delegate, 1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BufferedSender(delegate, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BufferedSender(delegate, 1, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Reaching the batch size triggers an immediate flush. */
    @Test
    void batchSizeTriggersFlush() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final BufferedSender sender = new BufferedSender(delegate, 3, Duration.ofMinutes(1));
        try {
            sender.send(LogEntry.info("svc", "a"));
            sender.send(LogEntry.info("svc", "b"));
            sender.send(LogEntry.info("svc", "c"));
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> delegate.totalEntries() == 3);
        } finally {
            sender.close();
        }
    }

    /** Scheduled flush drains entries even when batch size is not reached. */
    @Test
    void scheduledFlushDrainsEntries() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final BufferedSender sender = new BufferedSender(delegate, 100, Duration.ofMillis(50));
        try {
            sender.send(LogEntry.info("svc", "only"));
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> delegate.totalEntries() == 1);
        } finally {
            sender.close();
        }
    }

    /** {@link BufferedSender#sendBatch(Collection)} routes each entry through send. */
    @Test
    void sendBatchForwardsEachEntry() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final BufferedSender sender = new BufferedSender(delegate, 5, Duration.ofMillis(50));
        try {
            sender.sendBatch(List.of(
                    LogEntry.info("svc", "1"),
                    LogEntry.info("svc", "2")));
            await().atMost(2, TimeUnit.SECONDS)
                    .until(() -> delegate.totalEntries() == 2);
        } finally {
            sender.close();
        }
    }

    /** {@link BufferedSender#close()} drains the queue and closes the delegate. */
    @Test
    void closeDrainsQueueAndClosesDelegate() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final BufferedSender sender = new BufferedSender(delegate, 100, Duration.ofMinutes(1));
        sender.send(LogEntry.info("svc", "x"));
        sender.close();
        assertThat(delegate.totalEntries()).isEqualTo(1);
        assertThat(delegate.closeCount.get()).isEqualTo(1);
    }

    /** Calling close twice does not double-close the delegate. */
    @Test
    void closeIsIdempotent() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final BufferedSender sender = new BufferedSender(delegate, 10, Duration.ofMinutes(1));
        sender.close();
        sender.close();
        assertThat(delegate.closeCount.get()).isEqualTo(1);
    }

    /** After {@link BufferedSender#close()} new sends are silently dropped. */
    @Test
    void sendAfterCloseIsNoOp() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final BufferedSender sender = new BufferedSender(delegate, 10, Duration.ofMinutes(1));
        sender.close();
        sender.send(LogEntry.info("svc", "post-close"));
        assertThat(delegate.totalEntries()).isZero();
    }

    /** Null arguments are rejected on the send paths. */
    @Test
    void nullArgumentsRejected() {
        final RecordingDelegate delegate = new RecordingDelegate();
        final BufferedSender sender = new BufferedSender(delegate, 10, Duration.ofMinutes(1));
        try {
            assertThatThrownBy(() -> sender.send(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> sender.sendBatch(null)).isInstanceOf(NullPointerException.class);
        } finally {
            sender.close();
        }
    }

    /**
     * A flush failure in the delegate must not kill the scheduler.
     *
     * @throws InterruptedException if the test is interrupted while waiting
     *                              for the latch
     */
    @Test
    void delegateFailureIsSwallowed() throws InterruptedException {
        final CountDownLatch firstFlush = new CountDownLatch(1);
        final CortexClient delegate = new CortexClient() {

            @Override
            public void send(final LogEntry entry) {
                // Unused: BufferedSender always routes through sendBatch.
            }

            @Override
            public void sendBatch(final Collection<LogEntry> entries) {
                firstFlush.countDown();
                throw new IllegalStateException("simulated transport failure");
            }

            @Override
            public void flush() {
                // No-op.
            }

            @Override
            public void close() {
                // No-op.
            }
        };
        final BufferedSender sender = new BufferedSender(delegate, 1, Duration.ofMillis(50));
        try {
            sender.send(LogEntry.info("svc", "boom"));
            assertThat(firstFlush.await(2, TimeUnit.SECONDS)).isTrue();
            // Sender must still be alive: a second send should reach the delegate.
            sender.send(LogEntry.info("svc", "still-alive"));
        } finally {
            sender.close();
        }
    }
}
