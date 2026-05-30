package io.cortex.agent.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import io.cortex.agent.CortexClient;
import io.cortex.agent.CortexClientBuilder;
import io.cortex.agent.LogEntry;
import io.cortex.agent.LogLevel;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logback appender that translates each {@link ILoggingEvent} into a
 * {@link LogEntry} and dispatches it through a {@link CortexClient}.
 *
 * <p>Configuration in {@code logback.xml}:</p>
 *
 * <pre>{@code
 * <appender name="CORTEX" class="io.cortex.agent.logback.CortexLogbackAppender">
 *     <endpoint>https://cortex.example.com/ingest</endpoint>
 *     <service>my-app</service>
 *     <apiKey>${CORTEX_API_KEY}</apiKey>
 *     <batchSize>256</batchSize>
 *     <flushIntervalMs>1000</flushIntervalMs>
 * </appender>
 * }</pre>
 *
 * <p>Failures in the underlying client never propagate to Logback; the
 * SDK is fail-soft by contract.</p>
 */
public class CortexLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** CORTEX ingest endpoint; required. */
    private String endpoint;

    /** Logical service / app name; required. */
    private String service;

    /** Optional bearer token. */
    private String apiKey;

    /** Optional tenant identifier; copied verbatim onto every entry. */
    private String tenantId;

    /** Batch size used by the buffered sender. */
    private int batchSize = 256;

    /** Scheduled flush interval in milliseconds. */
    private long flushIntervalMs = 1000L;

    /** Built client, created on {@link #start()}. */
    private CortexClient client;

    /**
     * Sets the CORTEX ingest endpoint. Required.
     *
     * @param value absolute URL of the CORTEX ingest endpoint
     */
    public void setEndpoint(final String value) {
        this.endpoint = value;
    }

    /**
     * Sets the logical service / app name. Required.
     *
     * @param value service name stamped onto every emitted entry
     */
    public void setService(final String value) {
        this.service = value;
    }

    /**
     * Sets the optional bearer token.
     *
     * @param value bearer token; {@code null} disables the header
     */
    public void setApiKey(final String value) {
        this.apiKey = value;
    }

    /**
     * Sets the optional tenant identifier.
     *
     * @param value tenant id stamped onto every emitted entry
     */
    public void setTenantId(final String value) {
        this.tenantId = value;
    }

    /**
     * Sets the buffered-sender batch size.
     *
     * @param value batch size (>= 1)
     */
    public void setBatchSize(final int value) {
        this.batchSize = value;
    }

    /**
     * Sets the scheduled flush interval in milliseconds.
     *
     * @param value flush interval; must be positive
     */
    public void setFlushIntervalMs(final long value) {
        this.flushIntervalMs = value;
    }

    @Override
    public void start() {
        if (this.endpoint == null || this.endpoint.isBlank()) {
            this.addError("CortexLogbackAppender: endpoint is required");
            return;
        }
        if (this.service == null || this.service.isBlank()) {
            this.addError("CortexLogbackAppender: service is required");
            return;
        }
        this.client = this.buildClient();
        super.start();
    }

    @Override
    protected void append(final ILoggingEvent event) {
        if (this.client == null) {
            return;
        }
        this.client.send(this.toEntry(event));
    }

    @Override
    public void stop() {
        try {
            if (this.client != null) {
                this.client.close();
            }
        } finally {
            super.stop();
        }
    }

    /**
     * Builds the underlying {@link CortexClient} using the configured
     * settings. Exposed for tests via package-private subclassing.
     *
     * @return a configured {@link CortexClient}
     */
    CortexClient buildClient() {
        return new CortexClientBuilder()
                .endpoint(this.endpoint)
                .apiKey(this.apiKey)
                .batchSize(this.batchSize)
                .flushInterval(Duration.ofMillis(this.flushIntervalMs))
                .build();
    }

    /**
     * Converts a Logback event to a CORTEX {@link LogEntry}.
     *
     * @param event Logback event
     * @return corresponding {@link LogEntry}
     */
    LogEntry toEntry(final ILoggingEvent event) {
        final Map<String, String> mdc = event.getMDCPropertyMap();
        final Map<String, String> labels = new LinkedHashMap<>();
        labels.put("logger", event.getLoggerName());
        labels.put("thread", event.getThreadName());
        if (this.tenantId != null && !this.tenantId.isBlank()) {
            labels.put(LogEntry.LABEL_TENANT, this.tenantId);
        }
        if (mdc != null && mdc.get("traceId") != null) {
            labels.put(LogEntry.LABEL_TRACE_ID, mdc.get("traceId"));
        }
        return new LogEntry(
                Instant.ofEpochMilli(event.getTimeStamp()),
                toLogLevel(event.getLevel()),
                this.service,
                event.getFormattedMessage(),
                labels);
    }

    /**
     * Maps a Logback {@link Level} to a CORTEX {@link LogLevel}.
     *
     * @param level Logback level (may be {@code null})
     * @return matching {@link LogLevel}; never {@code null}
     */
    static LogLevel toLogLevel(final Level level) {
        if (level == null) {
            return LogLevel.INFO;
        }
        switch (level.toInt()) {
            case Level.TRACE_INT:
                return LogLevel.TRACE;
            case Level.DEBUG_INT:
                return LogLevel.DEBUG;
            case Level.INFO_INT:
                return LogLevel.INFO;
            case Level.WARN_INT:
                return LogLevel.WARN;
            case Level.ERROR_INT:
                return LogLevel.ERROR;
            default:
                return LogLevel.INFO;
        }
    }
}
