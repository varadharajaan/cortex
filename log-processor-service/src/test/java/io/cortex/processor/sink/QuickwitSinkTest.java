package io.cortex.processor.sink;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.cortex.processor.classify.Classification;
import io.cortex.processor.parse.RawLogEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link QuickwitSink} against an in-process JDK
 * {@link HttpServer} stand-in for the Quickwit ingest API
 * (P5.3 / ADR-0030).
 *
 * <p>Mirrors the agent-side {@code HttpCortexClientTest} pattern so
 * tests stay JVM-local + WireMock-free.</p>
 */
class QuickwitSinkTest {

    private static final String INDEX = "cortex-logs";

    private HttpServer server;
    private AtomicInteger requestCount;
    private AtomicReference<String> lastBody;
    private AtomicReference<String> lastContentType;
    private int responseStatus;
    private SimpleMeterRegistry registry;
    private SinkMetrics metrics;
    private ObjectMapper mapper;

    /**
     * Boot a JDK HTTP server on a random port + reset counters before
     * every test.
     *
     * @throws IOException if the server cannot bind
     */
    @BeforeEach
    void start() throws IOException {
        this.requestCount = new AtomicInteger();
        this.lastBody = new AtomicReference<>();
        this.lastContentType = new AtomicReference<>();
        this.responseStatus = 200;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext(QuickwitSink.ingestPath(INDEX), exchange -> {
            try {
                this.requestCount.incrementAndGet();
                this.lastContentType.set(
                        exchange.getRequestHeaders().getFirst("Content-Type"));
                this.lastBody.set(new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(this.responseStatus, -1);
            } finally {
                exchange.close();
            }
        });
        this.server.start();
        this.registry = new SimpleMeterRegistry();
        this.metrics = new SinkMetrics(this.registry);
        this.mapper = new ObjectMapper();
    }

    /** Shut the server cleanly. */
    @AfterEach
    void stop() {
        this.server.stop(0);
    }

    /** Happy path ticks quickwitPublished + posts NDJSON shape. */
    @Test
    void happyPathTicksPublishedAndPostsNdjsonDoc() {
        final QuickwitSink sink = newSink();
        sink.send(sample("evt-1", "cortex-dev"), Classification.none());

        assertThat(this.requestCount.get()).isEqualTo(1);
        assertThat(this.lastContentType.get()).contains("application/x-ndjson");
        assertThat(this.lastBody.get())
                .contains("\"id\":\"evt-1\"")
                .contains("\"event_id\":\"evt-1\"")
                .contains("\"tenant_id\":\"cortex-dev\"")
                .contains("\"anomaly\":false")
                .endsWith("\n");
        assertThat(publishedCount("cortex-dev")).isEqualTo(1.0d);
    }

    /** Anomaly verdict surfaces in the flat doc fields. */
    @Test
    void anomalyVerdictIncludesSeverityAndReason() {
        final QuickwitSink sink = newSink();
        sink.send(sample("evt-2", "cortex-dev"),
                new Classification(true, "CRITICAL", "fatal stack"));

        assertThat(this.lastBody.get())
                .contains("\"anomaly\":true")
                .contains("\"severity\":\"CRITICAL\"")
                .contains("\"reason\":\"fatal stack\"");
    }

    /** Non-2xx response ticks failed{reason=http_status}. */
    @Test
    void serverErrorTicksFailedHttpStatus() {
        this.responseStatus = 500;
        final QuickwitSink sink = newSink();
        sink.send(sample("evt-3", "cortex-dev"), Classification.none());

        assertThat(this.requestCount.get()).isEqualTo(1);
        assertThat(publishedCount("cortex-dev")).isZero();
        assertThat(failedCount("cortex-dev", "http_status")).isEqualTo(1.0d);
    }

    /** Transport failure (unbound port) ticks failed{reason=transport|unknown}. */
    @Test
    void transportFailureTicksFailedTransportOrUnknown() {
        final SinkProperties props = new SinkProperties(
                new SinkProperties.Loki(false, null, null),
                new SinkProperties.Quickwit(true, "http://127.0.0.1:1", INDEX,
                        Duration.ofMillis(500)));
        final QuickwitSink sink = new QuickwitSink(props, this.metrics, this.mapper);
        sink.send(sample("evt-4", "cortex-dev"), Classification.none());

        assertThat(publishedCount("cortex-dev")).isZero();
        final double anyFailure = failedCount("cortex-dev", "transport")
                + failedCount("cortex-dev", "unknown")
                + failedCount("cortex-dev", "timeout")
                + failedCount("cortex-dev", "http_status");
        assertThat(anyFailure).isGreaterThanOrEqualTo(1.0d);
    }

    /** Null event short-circuits without contacting the server. */
    @Test
    void nullEventIsNoOp() {
        final QuickwitSink sink = newSink();
        sink.send(null, Classification.none());
        assertThat(this.requestCount.get()).isZero();
    }

    /** Quickwit doc_id equals the event id (server-side dedupe key). */
    @Test
    void docIdEqualsEventId() {
        final QuickwitSink sink = newSink();
        sink.send(sample("dedupe-key-77", "cortex-dev"), Classification.none());
        assertThat(this.lastBody.get()).contains("\"id\":\"dedupe-key-77\"");
    }

    /** Reports its sink name. */
    @Test
    void sinkNameIsQuickwit() {
        assertThat(newSink().name()).isEqualTo("quickwit");
    }

    /**
     * Builds a QuickwitSink wired to the in-process JDK HTTP server.
     *
     * @return configured sink
     */
    private QuickwitSink newSink() {
        final SinkProperties props = new SinkProperties(
                new SinkProperties.Loki(false, null, null),
                new SinkProperties.Quickwit(true,
                        "http://127.0.0.1:" + this.server.getAddress().getPort(),
                        INDEX, Duration.ofSeconds(2)));
        final RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort())
                .requestFactory(new JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                                .build()))
                .build();
        final QuickwitSink sink = new QuickwitSink(props, this.metrics, this.mapper);
        sink.useRestClientForTesting(client);
        return sink;
    }

    /**
     * Reads the current value of the per-tenant Quickwit published counter (0 if unregistered).
     *
     * @param tenantId tenant tag to look up
     * @return current counter value or 0
     */
    private double publishedCount(final String tenantId) {
        try {
            return this.registry.get(SinkMetrics.METRIC_QUICKWIT_PUBLISHED)
                    .tag(SinkMetrics.TAG_TENANT, tenantId)
                    .counter().count();
        } catch (RuntimeException ex) {
            return 0.0d;
        }
    }

    /**
     * Reads the current value of the per-tenant per-reason Quickwit failed counter (0 if unregistered).
     *
     * @param tenantId tenant tag to look up
     * @param reason   reason tag to look up
     * @return current counter value or 0
     */
    private double failedCount(final String tenantId, final String reason) {
        try {
            return this.registry.get(SinkMetrics.METRIC_QUICKWIT_FAILED)
                    .tag(SinkMetrics.TAG_TENANT, tenantId)
                    .tag(SinkMetrics.TAG_REASON, reason)
                    .counter().count();
        } catch (RuntimeException ex) {
            return 0.0d;
        }
    }

    /**
     * Builds a sample {@link RawLogEvent} keyed by event id + tenant for assertions.
     *
     * @param eventId  event id to embed
     * @param tenantId tenant id to embed
     * @return sample event
     */
    private static RawLogEvent sample(final String eventId, final String tenantId) {
        return new RawLogEvent(tenantId, eventId,
                Instant.parse("2026-06-03T12:00:00Z"),
                "ERROR", "checkout", "OOM heap exhausted",
                Map.of("env", "smoke"), "idk-" + eventId,
                Instant.parse("2026-06-03T12:00:01Z"));
    }
}
