package io.cortex.agent.internal;

import io.cortex.agent.CortexClient;
import io.cortex.agent.LogEntry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Synchronous HTTP transport for the CORTEX agent SDK.
 *
 * <p>Posts batches as JSON to a configured endpoint using the JDK
 * {@link HttpClient}. Retries on I/O errors and non-2xx responses up
 * to {@link HttpCortexClientOptions#maxRetries()} times with a fixed
 * back-off. Failures after all retries are logged at {@code WARN} but
 * never propagated to the caller, honoring the fail-soft contract of
 * {@link CortexClient}.</p>
 */
public final class HttpCortexClient implements CortexClient {

    /** SLF4J logger for diagnostic messages. */
    private static final Logger LOG = LoggerFactory.getLogger(HttpCortexClient.class);

    /** Lower bound (inclusive) of the HTTP success status range. */
    private static final int HTTP_OK_LOW = 200;

    /** Upper bound (exclusive) of the HTTP success status range. */
    private static final int HTTP_OK_HIGH = 300;

    /** Target ingest endpoint. */
    private final URI endpoint;

    /** Optional bearer token; {@code null} disables the header. */
    private final String apiKey;

    /** Transport tuning knobs. */
    private final HttpCortexClientOptions options;

    /** Shared JDK HTTP client. */
    private final HttpClient httpClient;

    /** JSON codec shared across calls. */
    private final JsonCodec codec;

    /**
     * Creates a new synchronous HTTP client.
     *
     * @param endpoint ingest endpoint URI; must not be {@code null}
     * @param apiKey   bearer token; {@code null} disables the header
     * @param options  transport tuning knobs; must not be {@code null}
     * @param codec    JSON codec used for serialization
     */
    public HttpCortexClient(
            final URI endpoint,
            final String apiKey,
            final HttpCortexClientOptions options,
            final JsonCodec codec) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.apiKey = apiKey;
        this.options = Objects.requireNonNull(options, "options");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(options.connectTimeout())
                .build();
    }

    @Override
    public void send(final LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        this.sendBatch(Collections.singletonList(entry));
    }

    @Override
    public void sendBatch(final Collection<LogEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            return;
        }
        final byte[] body = this.codec.encodeBatch(entries);
        final HttpRequest request = this.buildRequest(body);
        this.dispatchWithRetry(request, entries.size());
    }

    @Override
    public void flush() {
        // Synchronous client has no buffering; calls are flushed inline.
    }

    @Override
    public void close() {
        // The shared JDK HttpClient does not require explicit shutdown on
        // Java 17; resources are released when the instance is unreferenced.
    }

    /**
     * Builds the HTTP POST request with the configured headers.
     *
     * @param body serialized JSON body
     * @return prepared {@link HttpRequest}
     */
    private HttpRequest buildRequest(final byte[] body) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(this.endpoint)
                .timeout(this.options.requestTimeout())
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "cortex-agent-lib/0.1.0")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (this.apiKey != null && !this.apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + this.apiKey);
        }
        return builder.build();
    }

    /**
     * Sends the request, retrying up to
     * {@link HttpCortexClientOptions#maxRetries()} times on transport
     * errors or non-2xx responses.
     *
     * @param request   prepared HTTP request
     * @param batchSize number of entries in the batch (for logging)
     */
    private void dispatchWithRetry(final HttpRequest request, final int batchSize) {
        int attempt = 0;
        while (true) {
            if (this.dispatchOnce(request, batchSize, attempt)) {
                return;
            }
            if (attempt >= this.options.maxRetries()) {
                LOG.warn("CORTEX ingest gave up after {} attempts batch={}",
                        attempt + 1, batchSize);
                return;
            }
            attempt++;
            if (!this.sleepBackoff()) {
                return;
            }
        }
    }

    /**
     * Performs a single dispatch attempt. Returns {@code true} when
     * the attempt succeeded (2xx) or when the caller should stop
     * retrying (interrupt). Returns {@code false} when the caller
     * should consider a retry.
     *
     * @param request   prepared HTTP request
     * @param batchSize batch size (for logging)
     * @param attempt   zero-based attempt counter
     * @return {@code true} to stop, {@code false} to retry
     */
    private boolean dispatchOnce(
            final HttpRequest request,
            final int batchSize,
            final int attempt) {
        try {
            final HttpResponse<Void> response = this.httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= HTTP_OK_LOW
                    && response.statusCode() < HTTP_OK_HIGH) {
                return true;
            }
            LOG.warn("CORTEX ingest non-2xx status={} attempt={} batch={}",
                    response.statusCode(), attempt, batchSize);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn("CORTEX ingest interrupted; dropping batch size={}", batchSize);
            return true;
        } catch (java.io.IOException ex) {
            LOG.warn("CORTEX ingest I/O error attempt={} batch={}: {}",
                    attempt, batchSize, ex.getMessage());
        }
        return false;
    }

    /**
     * Sleeps for the configured back-off. Returns {@code false} if the
     * thread was interrupted while sleeping (and re-flags the interrupt).
     *
     * @return {@code true} if the sleep completed, {@code false} on interrupt
     */
    private boolean sleepBackoff() {
        try {
            Thread.sleep(this.options.retryBackoff().toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
