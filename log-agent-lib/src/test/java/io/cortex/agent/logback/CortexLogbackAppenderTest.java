package io.cortex.agent.logback;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.cortex.agent.CortexClient;
import io.cortex.agent.LogEntry;
import io.cortex.agent.LogLevel;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit tests for {@link CortexLogbackAppender}.
 */
class CortexLogbackAppenderTest {

    /** In-memory client recording every entry it receives. */
    private static final class RecordingClient implements CortexClient {

        /** Captured entries. */
        private final List<LogEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void send(final LogEntry entry) {
            this.entries.add(entry);
        }

        @Override
        public void sendBatch(final Collection<LogEntry> batch) {
            this.entries.addAll(batch);
        }

        @Override
        public void flush() {
            // No-op.
        }

        @Override
        public void close() {
            // No-op.
        }
    }

    /** Appender that swaps in a recording client instead of building HTTP. */
    private static final class TestAppender extends CortexLogbackAppender {

        /** Recording client returned by {@link #buildClient()}. */
        private final RecordingClient client = new RecordingClient();

        @Override
        CortexClient buildClient() {
            return this.client;
        }
    }

    /**
     * Builds a Logback event with the given level and message.
     *
     * @param level   Logback level for the event
     * @param message formatted message for the event
     * @return a populated {@link ILoggingEvent} suitable for tests
     */
    private static ILoggingEvent newEvent(final Level level, final String message) {
        final LoggerContext ctx = new LoggerContext();
        final Logger logger = ctx.getLogger("io.cortex.test");
        final LoggingEvent event = new LoggingEvent(
                "io.cortex.test", logger, level, message, null, new Object[0]);
        event.setTimeStamp(1_700_000_000_000L);
        event.setThreadName("test-thread");
        final Map<String, String> mdc = new HashMap<>();
        mdc.put("traceId", "trace-1");
        event.setMDCPropertyMap(mdc);
        return event;
    }

    /** Missing endpoint or service blocks start. */
    @Test
    void missingRequiredFieldsBlockStart() {
        final TestAppender appender = new TestAppender();
        appender.start();
        assertThat(appender.isStarted()).isFalse();

        appender.setEndpoint("http://x/ingest");
        appender.start();
        assertThat(appender.isStarted()).isFalse();
    }

    /** With endpoint and service set, the appender starts. */
    @Test
    void startsWhenConfigured() {
        final TestAppender appender = new TestAppender();
        appender.setEndpoint("http://x/ingest");
        appender.setService("svc");
        appender.start();
        assertThat(appender.isStarted()).isTrue();
        appender.stop();
    }

    /** A logged event is converted to a {@link LogEntry} and forwarded. */
    @Test
    void appendingForwardsEntry() {
        final TestAppender appender = new TestAppender();
        appender.setEndpoint("http://x/ingest");
        appender.setService("svc");
        appender.setTenantId("tenant-1");
        appender.start();
        try {
            appender.doAppend(newEvent(Level.WARN, "hello"));
            assertThat(appender.client.entries).hasSize(1);
            final LogEntry entry = appender.client.entries.get(0);
            assertThat(entry.level()).isEqualTo(LogLevel.WARN);
            assertThat(entry.message()).isEqualTo("hello");
            assertThat(entry.service()).isEqualTo("svc");
            assertThat(entry.tenantId()).isEqualTo("tenant-1");
            assertThat(entry.traceId()).isEqualTo("trace-1");
            assertThat(entry.labels()).containsKeys("logger", "thread");
        } finally {
            MDC.clear();
            appender.stop();
        }
    }

    /** Appending before {@link CortexLogbackAppender#start()} is a no-op. */
    @Test
    void appendBeforeStartIsNoOp() {
        final TestAppender appender = new TestAppender();
        appender.append(newEvent(Level.INFO, "ignored"));
        assertThat(appender.client.entries).isEmpty();
    }

    /** Every Logback level maps to the expected {@link LogLevel}. */
    @Test
    void levelMappingCoversAllValues() {
        assertThat(CortexLogbackAppender.toLogLevel(Level.TRACE)).isEqualTo(LogLevel.TRACE);
        assertThat(CortexLogbackAppender.toLogLevel(Level.DEBUG)).isEqualTo(LogLevel.DEBUG);
        assertThat(CortexLogbackAppender.toLogLevel(Level.INFO)).isEqualTo(LogLevel.INFO);
        assertThat(CortexLogbackAppender.toLogLevel(Level.WARN)).isEqualTo(LogLevel.WARN);
        assertThat(CortexLogbackAppender.toLogLevel(Level.ERROR)).isEqualTo(LogLevel.ERROR);
        assertThat(CortexLogbackAppender.toLogLevel(null)).isEqualTo(LogLevel.INFO);
        assertThat(CortexLogbackAppender.toLogLevel(Level.OFF)).isEqualTo(LogLevel.INFO);
    }

    /** Setter coverage so checkstyle is happy and config flows through. */
    @Test
    void settersAcceptAllValues() {
        final TestAppender appender = new TestAppender();
        appender.setEndpoint("http://x");
        appender.setService("svc");
        appender.setApiKey("token");
        appender.setTenantId("tenant");
        appender.setBatchSize(32);
        appender.setFlushIntervalMs(750L);
        appender.start();
        assertThat(appender.isStarted()).isTrue();
        appender.stop();
    }
}
