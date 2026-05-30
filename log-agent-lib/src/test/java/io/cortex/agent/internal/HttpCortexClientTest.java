package io.cortex.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortex.agent.LogEntry;
import io.cortex.agent.LogLevel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link HttpCortexClient} using an in-process
 * JDK {@link HttpServer} as the target endpoint.
 */
class HttpCortexClientTest {

    /** In-process HTTP server backing each test. */
    private HttpServer server;

    /** Captured request count per test. */
    private AtomicInteger requestCount;

    /** Captured Authorization headers per test. */
    private ConcurrentLinkedQueue<String> authHeaders;

    /** Recording handler, configured per test via {@link #responses}. */
    private RecordingHandler handler;

    /** Per-test response plan (status codes in order; last value repeats). */
    private final java.util.List<Integer> responses = new java.util.ArrayList<>();

    /**
     * Starts a fresh server on a random port for each test.
     *
     * @throws IOException if the server cannot bind to a free port
     */
    @BeforeEach
    void startServer() throws IOException {
        this.requestCount = new AtomicInteger();
        this.authHeaders = new ConcurrentLinkedQueue<>();
        this.responses.clear();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.handler = new RecordingHandler(this.requestCount, this.authHeaders, this.responses);
        this.server.createContext("/ingest", this.handler);
        this.server.start();
    }

    /** Stops the server after each test. */
    @AfterEach
    void stopServer() {
        this.server.stop(0);
    }

    /**
     * Returns the base URI for the test server's ingest endpoint.
     *
     * @return the in-process server's ingest URI
     */
    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/ingest");
    }

    /**
     * Builds a client with the given API key and retry settings.
     *
     * @param apiKey     bearer token; {@code null} disables the header
     * @param maxRetries retry attempts on transport failure
     * @return a fresh {@link HttpCortexClient} pointed at the test server
     */
    private HttpCortexClient newClient(final String apiKey, final int maxRetries) {
        final HttpCortexClientOptions options = new HttpCortexClientOptions(
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                maxRetries,
                Duration.ofMillis(10));
        return new HttpCortexClient(this.endpoint(), apiKey, options, new JsonCodec());
    }

    /**
     * A sample log entry used throughout the suite.
     *
     * @return a fixed {@link LogEntry} suitable for assertions
     */
    private LogEntry sampleEntry() {
        return new LogEntry(
                Instant.parse("2026-01-02T03:04:05Z"),
                LogLevel.INFO,
                "svc",
                "msg",
                Map.of());
    }

    /** 2xx response: a single request, no retry. */
    @Test
    void successfulSendUsesOneRequest() {
        this.responses.add(200);
        final HttpCortexClient client = this.newClient(null, 3);
        try {
            client.send(this.sampleEntry());
            assertThat(this.requestCount.get()).isEqualTo(1);
        } finally {
            client.close();
        }
    }

    /** Retry kicks in on 500, then succeeds on the 2nd attempt. */
    @Test
    void retriesUntilSuccess() {
        this.responses.add(500);
        this.responses.add(200);
        final HttpCortexClient client = this.newClient(null, 3);
        try {
            client.send(this.sampleEntry());
            assertThat(this.requestCount.get()).isEqualTo(2);
        } finally {
            client.close();
        }
    }

    /** All 500 responses exhaust retries and the call returns silently. */
    @Test
    void retriesAreExhausted() {
        this.responses.add(500);
        final HttpCortexClient client = this.newClient(null, 2);
        try {
            client.send(this.sampleEntry());
            assertThat(this.requestCount.get()).isEqualTo(3);
        } finally {
            client.close();
        }
    }

    /** The {@code Authorization} header is sent when an API key is set. */
    @Test
    void authorizationHeaderSentWhenApiKeySet() {
        this.responses.add(200);
        final HttpCortexClient client = this.newClient("secret", 0);
        try {
            client.send(this.sampleEntry());
            assertThat(this.authHeaders).containsExactly("Bearer secret");
        } finally {
            client.close();
        }
    }

    /** Blank API keys do not produce an {@code Authorization} header. */
    @Test
    void noAuthorizationHeaderWhenApiKeyBlank() {
        this.responses.add(200);
        final HttpCortexClient client = this.newClient("  ", 0);
        try {
            client.send(this.sampleEntry());
            assertThat(this.authHeaders).isEmpty();
        } finally {
            client.close();
        }
    }

    /** Empty batch short-circuits without contacting the server. */
    @Test
    void emptyBatchIsNoOp() {
        final HttpCortexClient client = this.newClient(null, 0);
        try {
            client.sendBatch(List.of());
            assertThat(this.requestCount.get()).isZero();
        } finally {
            client.close();
        }
    }

    /** {@code null} entry and {@code null} batch raise NPE. */
    @Test
    void nullArgumentsRejected() {
        final HttpCortexClient client = this.newClient(null, 0);
        try {
            assertThatThrownBy(() -> client.send(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> client.sendBatch(null)).isInstanceOf(NullPointerException.class);
        } finally {
            client.close();
        }
    }

    /** {@link HttpCortexClient#flush()} and {@link HttpCortexClient#close()} are no-ops. */
    @Test
    void flushAndCloseAreNoOps() {
        final HttpCortexClient client = this.newClient(null, 0);
        client.flush();
        client.close();
        client.close();
    }

    /** Interrupting the calling thread mid back-off aborts the retry loop. */
    @Test
    void interruptDuringBackoffAbortsRetry() {
        this.responses.add(500);
        final HttpCortexClient client = this.newClient(null, 5);
        try {
            Thread.currentThread().interrupt();
            client.send(this.sampleEntry());
            assertThat(Thread.interrupted()).isTrue();
            // No exception bubbled and at most one transport attempt happened
            // (the JDK HttpClient may abort pre-flight on an interrupted thread).
            assertThat(this.requestCount.get()).isLessThanOrEqualTo(1);
        } finally {
            client.close();
        }
    }

    /** Recording HTTP handler that returns scripted status codes in order. */
    private static final class RecordingHandler implements HttpHandler {

        /** Total requests received. */
        private final AtomicInteger count;

        /** Captured Authorization headers. */
        private final ConcurrentLinkedQueue<String> auths;

        /** Scripted response codes (last value repeats). */
        private final java.util.List<Integer> codes;

        /**
         * Creates a handler bound to the test's shared collectors.
         *
         * @param count captured request counter
         * @param auths captured Authorization headers
         * @param codes scripted response codes
         */
        RecordingHandler(
                final AtomicInteger count,
                final ConcurrentLinkedQueue<String> auths,
                final java.util.List<Integer> codes) {
            this.count = count;
            this.auths = auths;
            this.codes = codes;
        }

        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            try {
                final int index = this.count.getAndIncrement();
                final String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null) {
                    this.auths.add(auth);
                }
                final int code = this.responseFor(index);
                final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(code, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        }

        /**
         * Returns the scripted status for the given attempt index. The
         * last entry in {@link #codes} repeats indefinitely.
         *
         * @param index zero-based attempt index
         * @return HTTP status code to respond with
         */
        private int responseFor(final int index) {
            if (this.codes.isEmpty()) {
                return 200;
            }
            if (index >= this.codes.size()) {
                return this.codes.get(this.codes.size() - 1);
            }
            return this.codes.get(index);
        }
    }
}
