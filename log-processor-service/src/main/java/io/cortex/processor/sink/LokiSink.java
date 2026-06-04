package io.cortex.processor.sink;

import io.cortex.processor.classify.Classification;
import io.cortex.processor.parse.RawLogEvent;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link ParsedEventSink} implementation for the Grafana Loki push
 * API (P5.3 / ADR-0030).
 *
 * <p>Posts a single stream entry per event to
 * {@code POST {base-url}/loki/api/v1/push}. Stream labels are kept
 * intentionally small to stay inside Loki's recommended cardinality
 * envelope: {@code tenant_id, level, anomaly}. The log line carries
 * the enriched message + reason suffix.</p>
 *
 * <p>Loki has no native idempotency on the push API; on Kafka
 * rebalance redelivery the cluster will hold a duplicate chunk. The
 * P5.x roadmap mentions a per-event hash label as a follow-up; in
 * P5.3 we accept the duplicate exposure as documented in ADR-0030 D6.</p>
 *
 * <p>Outbound HTTP is pinned to HTTP/1.1 via
 * {@link JdkClientHttpRequestFactory} for symmetry with the gateway
 * P3.3 + processor P5.2 patterns (LD42). Gated by
 * {@code cortex.processor.sinks.loki.enabled=true} so the default
 * dev boot does not even instantiate the bean.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.processor.sinks.loki",
        name = "enabled",
        havingValue = "true"
)
public class LokiSink implements ParsedEventSink {

    private static final Logger LOG = LoggerFactory.getLogger(LokiSink.class);

    /** Loki push API path appended to the configured base URL. */
    static final String PUSH_PATH = "/loki/api/v1/push";

    /** Sink identifier reported in tag values + log lines. */
    private static final String NAME = "loki";

    private RestClient restClient;
    private final SinkProperties.Loki loki;
    private final SinkMetrics metrics;

    /**
     * Spring constructor.
     *
     * @param properties typed sinks config block
     * @param metrics    sink counter registry
     */
    public LokiSink(final SinkProperties properties, final SinkMetrics metrics) {
        this.loki = properties.loki();
        this.metrics = metrics;
        this.restClient = defaultRestClient(this.loki);
    }

    /**
     * Test hook: replace the production HTTP/1.1-pinned client with
     * one that targets an in-process JDK HttpServer or WireMock.
     *
     * @param client pre-built {@link RestClient} to swap in
     */
    void useRestClientForTesting(final RestClient client) {
        this.restClient = client;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void send(final RawLogEvent event, final Classification verdict) {
        if (event == null) {
            return;
        }
        final String tenantId = event.tenantId();
        final Map<String, Object> body;
        try {
            body = renderBody(event, verdict);
        } catch (final RuntimeException ex) {
            LOG.warn("loki sink body serialisation failed tenantId={} eventId={}: {}",
                    tenantId, event.eventId(), ex.getMessage());
            this.metrics.lokiFailed(tenantId, SinkMetrics.Reason.SERIALIZATION);
            return;
        }
        try {
            this.restClient.post()
                    .uri(PUSH_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            this.metrics.lokiPublished(tenantId);
        } catch (final RestClientResponseException ex) {
            LOG.warn("loki sink non-2xx tenantId={} eventId={} status={}: {}",
                    tenantId, event.eventId(), ex.getStatusCode().value(),
                    ex.getMessage());
            this.metrics.lokiFailed(tenantId, SinkMetrics.Reason.HTTP_STATUS);
        } catch (final ResourceAccessException ex) {
            LOG.warn("loki sink transport failure tenantId={} eventId={}: {}",
                    tenantId, event.eventId(), ex.getMessage());
            this.metrics.lokiFailed(tenantId, classifyTransport(ex));
        } catch (final RuntimeException ex) {
            LOG.warn("loki sink unexpected failure tenantId={} eventId={}: {}",
                    tenantId, event.eventId(), ex.getMessage());
            this.metrics.lokiFailed(tenantId, SinkMetrics.Reason.UNKNOWN);
        }
    }

    /**
     * Render the Loki push API body for a single event.
     *
     * <p>Shape:</p>
     * <pre>
     * { "streams": [
     *     { "stream": { tenant_id, level, anomaly },
     *       "values": [["&lt;ts-nanos&gt;", "&lt;message&gt;"]] }
     * ]}
     * </pre>
     *
     * @param event   source event
     * @param verdict classifier verdict (may be none)
     * @return body map suitable for Jackson encoding to JSON
     */
    static Map<String, Object> renderBody(final RawLogEvent event,
                                          final Classification verdict) {
        final Map<String, String> labels = new LinkedHashMap<>();
        labels.put("tenant_id", StringUtils.defaultIfBlank(event.tenantId(), "unknown"));
        labels.put("level", StringUtils.defaultIfBlank(event.level(), "unknown"));
        labels.put("anomaly", verdict != null && verdict.anomaly()
                ? "true" : "false");
        final String tsNanos = String.valueOf(
                event.ts() == null
                        ? System.currentTimeMillis() * 1_000_000L
                        : event.ts().getEpochSecond() * 1_000_000_000L
                                + event.ts().getNano());
        final String line = renderLine(event, verdict);
        final Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("stream", labels);
        stream.put("values", List.of(List.of(tsNanos, line)));
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("streams", List.of(stream));
        return body;
    }

    /**
     * Renders the human-readable Loki log line for the given event + verdict.
     *
     * @param event   source event
     * @param verdict classifier verdict (may be none)
     * @return rendered log line string
     */
    private static String renderLine(final RawLogEvent event,
                                     final Classification verdict) {
        final StringBuilder sb = new StringBuilder();
        sb.append(event.message() == null ? "" : event.message());
        if (verdict != null && verdict.anomaly()) {
            sb.append(" [anomaly=").append(verdict.severity()).append(']');
            if (verdict.reason() != null && !verdict.reason().isEmpty()) {
                sb.append(" reason=").append(verdict.reason());
            }
        }
        return sb.toString();
    }

    /**
     * Maps a transport-layer exception to the right {@link SinkMetrics.Reason} bucket.
     *
     * @param ex caught {@link ResourceAccessException}
     * @return matching reason enum
     */
    private static SinkMetrics.Reason classifyTransport(
            final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        if (cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException) {
            return SinkMetrics.Reason.TIMEOUT;
        }
        return SinkMetrics.Reason.TRANSPORT;
    }

    /**
     * Builds the default HTTP/1.1-pinned {@link RestClient} used by the production ctor.
     *
     * @param loki resolved Loki sink properties
     * @return configured RestClient
     */
    private static RestClient defaultRestClient(final SinkProperties.Loki loki) {
        final Duration timeout = loki.requestTimeout() != null
                ? loki.requestTimeout()
                : SinkProperties.DEFAULT_REQUEST_TIMEOUT;
        return RestClient.builder()
                .baseUrl(loki.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(timeout)
                                .build()))
                .build();
    }
}
