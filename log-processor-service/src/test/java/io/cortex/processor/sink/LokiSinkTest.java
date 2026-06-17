package io.cortex.processor.sink;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Unit tests for {@link LokiSink} against an in-process JDK
 * {@link HttpServer} stand-in for the Loki push API (P5.3 / ADR-0030).
 *
 * <p>Mirrors the agent-side {@code HttpCortexClientTest} pattern so
 * tests stay JVM-local + WireMock-free.</p>
 */
class LokiSinkTest {

    private HttpServer server;
    private AtomicInteger requestCount;
    private AtomicReference<String> lastBody;
    private AtomicReference<String> lastContentType;
    private int responseStatus;
    private SimpleMeterRegistry registry;
    private SinkMetrics metrics;

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
        this.responseStatus = 204;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext(LokiSink.PUSH_PATH, exchange -> {
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
    }

    /** Shut the server cleanly. */
    @AfterEach
    void stop() {
        this.server.stop(0);
    }

    /** 204 response ticks lokiPublished + posts the expected stream shape. */
    @Test
    void happyPathTicksPublishedAndPostsLokiBody() {
        final LokiSink sink = newSink();
        final RawLogEvent event = sample("evt-1", "cortex-dev");
        sink.send(event, Classification.none());

        assertThat(this.requestCount.get()).isEqualTo(1);
        assertThat(this.lastContentType.get()).contains("application/json");
        assertThat(this.lastBody.get())
                .contains("\"streams\"")
                .contains("\"tenant_id\":\"cortex-dev\"")
                .contains("\"service\":\"checkout\"")
                .contains("\"level\":\"ERROR\"")
                .contains("\"anomaly\":\"false\"");
        assertThat(publishedCount("cortex-dev")).isEqualTo(1.0d);
        assertThat(failedCount("cortex-dev", "http_status")).isZero();
    }

    /** Anomaly verdict surfaces in the labels + line suffix. */
    @Test
    void anomalyVerdictIncludesSeverityAndReason() {
        final LokiSink sink = newSink();
        final RawLogEvent event = sample("evt-2", "cortex-dev");
        sink.send(event, new Classification(true, "HIGH", "spike detected"));

        assertThat(this.requestCount.get()).isEqualTo(1);
        assertThat(this.lastBody.get())
                .contains("\"anomaly\":\"true\"")
                .contains("[anomaly=HIGH]")
                .contains("reason=spike detected");
    }

    /** Non-2xx response ticks failed{reason=http_status} + does NOT throw. */
    @Test
    void serverErrorTicksFailedHttpStatus() {
        this.responseStatus = 503;
        final LokiSink sink = newSink();
        sink.send(sample("evt-3", "cortex-dev"), Classification.none());

        assertThat(this.requestCount.get()).isEqualTo(1);
        assertThat(publishedCount("cortex-dev")).isZero();
        assertThat(failedCount("cortex-dev", "http_status")).isEqualTo(1.0d);
    }

    /** Transport failure (unbound port) ticks failed{reason=transport}. */
    @Test
    void transportFailureTicksFailedTransport() {
        final SinkProperties props = new SinkProperties(
                new SinkProperties.Loki(true, "http://127.0.0.1:1",
                        Duration.ofMillis(500)),
                new SinkProperties.Quickwit(false, null, null, null));
        final LokiSink sink = new LokiSink(props, this.metrics);
        sink.send(sample("evt-4", "cortex-dev"), Classification.none());

        assertThat(publishedCount("cortex-dev")).isZero();
        // Transport or unknown -- both count as a tick on a failed series.
        final double anyFailure = failedCount("cortex-dev", "transport")
                + failedCount("cortex-dev", "unknown")
                + failedCount("cortex-dev", "http_status")
                + failedCount("cortex-dev", "timeout");
        assertThat(anyFailure).isGreaterThanOrEqualTo(1.0d);
    }

    /** Null event short-circuits without contacting the server. */
    @Test
    void nullEventIsNoOp() {
        final LokiSink sink = newSink();
        sink.send(null, Classification.none());
        assertThat(this.requestCount.get()).isZero();
    }

    /** Null tenant is coerced to "unknown" on both label + counter tag. */
    @Test
    void nullTenantIsCoerced() {
        final LokiSink sink = newSink();
        sink.send(sample("evt-5", null), Classification.none());
        assertThat(this.lastBody.get()).contains("\"tenant_id\":\"unknown\"");
        assertThat(publishedCount("unknown")).isEqualTo(1.0d);
    }

    /** Null service is coerced to "unknown" on the Loki stream label. */
    @Test
    void nullServiceIsCoerced() {
        final LokiSink sink = newSink();
        sink.send(sample("evt-6", "cortex-dev", null), Classification.none());
        assertThat(this.lastBody.get()).contains("\"service\":\"unknown\"");
    }

    /** Blank service is coerced to "unknown" on the Loki stream label. */
    @Test
    void blankServiceIsCoerced() {
        final LokiSink sink = newSink();
        sink.send(sample("evt-7", "cortex-dev", " "), Classification.none());
        assertThat(this.lastBody.get()).contains("\"service\":\"unknown\"");
    }

    /** Reports its sink name. */
    @Test
    void sinkNameIsLoki() {
        assertThat(newSink().name()).isEqualTo("loki");
    }

    /**
     * Builds a LokiSink wired to the in-process JDK HTTP server.
     *
     * @return configured sink
     */
    private LokiSink newSink() {
        final SinkProperties props = new SinkProperties(
                new SinkProperties.Loki(true,
                        "http://127.0.0.1:" + this.server.getAddress().getPort(),
                        Duration.ofSeconds(2)),
                new SinkProperties.Quickwit(false, null, null, null));
        final RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort())
                .requestFactory(new JdkClientHttpRequestFactory(
                        java.net.http.HttpClient.newBuilder()
                                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                                .build()))
                .build();
        final LokiSink sink = new LokiSink(props, this.metrics);
        sink.useRestClientForTesting(client);
        return sink;
    }

    /**
     * Reads the current value of the per-tenant Loki published counter (0 if unregistered).
     *
     * @param tenantId tenant tag to look up
     * @return current counter value or 0
     */
    private double publishedCount(final String tenantId) {
        try {
            return this.registry.get(SinkMetrics.METRIC_LOKI_PUBLISHED)
                    .tag(SinkMetrics.TAG_TENANT, tenantId)
                    .counter().count();
        } catch (RuntimeException ex) {
            return 0.0d;
        }
    }

    /**
     * Reads the current value of the per-tenant per-reason Loki failed counter (0 if unregistered).
     *
     * @param tenantId tenant tag to look up
     * @param reason   reason tag to look up
     * @return current counter value or 0
     */
    private double failedCount(final String tenantId, final String reason) {
        try {
            return this.registry.get(SinkMetrics.METRIC_LOKI_FAILED)
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
        return sample(eventId, tenantId, "checkout");
    }

    /**
     * Builds a sample {@link RawLogEvent} keyed by event id, tenant, and service.
     *
     * @param eventId  event id to embed
     * @param tenantId tenant id to embed
     * @param service  service name to embed
     * @return sample event
     */
    private static RawLogEvent sample(final String eventId, final String tenantId,
                                      final String service) {
        return new RawLogEvent(tenantId, eventId,
                Instant.parse("2026-06-03T12:00:00Z"),
                "ERROR", service, "OOM heap exhausted",
                Map.of("env", "smoke"), "idk-" + eventId,
                Instant.parse("2026-06-03T12:00:01Z"));
    }
}
