package io.cortex.processor.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.processor.classify.Classification;
import io.cortex.processor.parse.RawLogEvent;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
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
 * {@link ParsedEventSink} implementation for the Quickwit ingest API
 * (P5.3 / ADR-0030).
 *
 * <p>Posts a single NDJSON document per event to
 * {@code POST {base-url}/api/v1/{index}/ingest}. The body is the
 * full event flattened into a single JSON object plus the classifier
 * verdict. The Quickwit document {@code id} field is bound to
 * {@link RawLogEvent#eventId()} so a Kafka rebalance redelivery is
 * server-side de-duplicated on the search tier (ADR-0030 D6).</p>
 *
 * <p>Outbound HTTP is pinned to HTTP/1.1 via
 * {@link JdkClientHttpRequestFactory} for symmetry with the gateway
 * P3.3 + processor P5.2 patterns (LD42). Gated by
 * {@code cortex.processor.sinks.quickwit.enabled=true} so the default
 * dev boot does not even instantiate the bean.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.processor.sinks.quickwit",
        name = "enabled",
        havingValue = "true"
)
public class QuickwitSink implements ParsedEventSink {

    private static final Logger LOG = LoggerFactory.getLogger(QuickwitSink.class);

    /** Sink identifier reported in tag values + log lines. */
    private static final String NAME = "quickwit";

    private RestClient restClient;
    private final ObjectMapper mapper;
    private final SinkProperties.Quickwit quickwit;
    private final SinkMetrics metrics;
    private final String ingestPath;

    /**
     * Spring constructor.
     *
     * @param properties typed sinks config block
     * @param metrics    sink counter registry
     * @param mapper     shared Jackson mapper (autoconfigured by
     *                   Spring Boot)
     */
    public QuickwitSink(final SinkProperties properties,
                        final SinkMetrics metrics,
                        final ObjectMapper mapper) {
        this.quickwit = properties.quickwit();
        this.metrics = metrics;
        this.mapper = mapper;
        this.restClient = defaultRestClient(this.quickwit);
        this.ingestPath = ingestPath(this.quickwit.index());
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

    /**
     * Compute the ingest API path for the configured Quickwit index.
     *
     * @param index Quickwit index id
     * @return path segment to append to the base URL
     */
    static String ingestPath(final String index) {
        return "/api/v1/" + index + "/ingest";
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
        final String ndjson;
        try {
            ndjson = renderNdjson(event, verdict);
        } catch (final RuntimeException ex) {
            LOG.warn("quickwit sink body serialisation failed tenantId={} eventId={}: {}",
                    tenantId, event.eventId(), ex.getMessage());
            this.metrics.quickwitFailed(tenantId, SinkMetrics.Reason.SERIALIZATION);
            return;
        }
        try {
            this.restClient.post()
                    .uri(this.ingestPath)
                    .contentType(MediaType.APPLICATION_NDJSON)
                    .body(ndjson)
                    .retrieve()
                    .toBodilessEntity();
            this.metrics.quickwitPublished(tenantId);
        } catch (final RestClientResponseException ex) {
            LOG.warn("quickwit sink non-2xx tenantId={} eventId={} status={}: {}",
                    tenantId, event.eventId(), ex.getStatusCode().value(),
                    ex.getMessage());
            this.metrics.quickwitFailed(tenantId, SinkMetrics.Reason.HTTP_STATUS);
        } catch (final ResourceAccessException ex) {
            LOG.warn("quickwit sink transport failure tenantId={} eventId={}: {}",
                    tenantId, event.eventId(), ex.getMessage());
            this.metrics.quickwitFailed(tenantId, classifyTransport(ex));
        } catch (final RuntimeException ex) {
            LOG.warn("quickwit sink unexpected failure tenantId={} eventId={}: {}",
                    tenantId, event.eventId(), ex.getMessage());
            this.metrics.quickwitFailed(tenantId, SinkMetrics.Reason.UNKNOWN);
        }
    }

    /**
     * Serialises the rendered document map to an NDJSON line (trailing newline included).
     *
     * @param event   source event
     * @param verdict classifier verdict (may be none)
     * @return rendered NDJSON line
     * @throws IllegalStateException when Jackson serialisation fails
     */
    private String renderNdjson(final RawLogEvent event,
                                final Classification verdict) {
        try {
            return this.mapper.writeValueAsString(renderDoc(event, verdict)) + "\n";
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException(
                    "failed to serialise quickwit document", ex);
        }
    }

    /**
     * Render a single Quickwit document for an event.
     *
     * <p>Fields mirror the {@link RawLogEvent} record plus a flat
     * {@code anomaly} / {@code severity} / {@code reason} block
     * derived from the classifier verdict. {@code id} is bound to
     * the event id so Quickwit de-duplicates on its side.</p>
     *
     * @param event   source event
     * @param verdict classifier verdict (may be none)
     * @return ordered map suitable for Jackson encoding
     */
    static Map<String, Object> renderDoc(final RawLogEvent event,
                                         final Classification verdict) {
        final Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", StringUtils.defaultString(event.eventId()));
        doc.put("tenant_id", StringUtils.defaultString(event.tenantId()));
        doc.put("event_id", StringUtils.defaultString(event.eventId()));
        doc.put("ts", event.ts() == null ? null : event.ts().toString());
        doc.put("level", StringUtils.defaultString(event.level()));
        doc.put("service", StringUtils.defaultString(event.service()));
        doc.put("message", StringUtils.defaultString(event.message()));
        doc.put("labels", event.labels());
        doc.put("idempotency_key", StringUtils.defaultString(event.idempotencyKey()));
        doc.put("received_at", event.receivedAt() == null
                ? null : event.receivedAt().toString());
        doc.put("anomaly", verdict != null && verdict.anomaly());
        doc.put("severity", verdict == null ? "NONE" : verdict.severity());
        doc.put("reason", verdict == null ? "" : verdict.reason());
        return doc;
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
     * @param quickwit resolved Quickwit sink properties
     * @return configured RestClient
     */
    private static RestClient defaultRestClient(
            final SinkProperties.Quickwit quickwit) {
        final Duration timeout = quickwit.requestTimeout() != null
                ? quickwit.requestTimeout()
                : SinkProperties.DEFAULT_REQUEST_TIMEOUT;
        return RestClient.builder()
                .baseUrl(quickwit.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(timeout)
                                .build()))
                .build();
    }
}
