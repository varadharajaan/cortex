package io.cortex.agent;

import io.cortex.agent.internal.BufferedSender;
import io.cortex.agent.internal.HttpCortexClient;
import io.cortex.agent.internal.HttpCortexClientOptions;
import io.cortex.agent.internal.JsonCodec;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

/**
 * Fluent builder for {@link CortexClient} instances.
 *
 * <p>Required settings:</p>
 * <ul>
 *   <li>{@link #endpoint(String)} - the ingest URL.</li>
 * </ul>
 *
 * <p>Optional settings have sensible defaults appropriate for a
 * server-side workload: 2-second connect timeout, 5-second request
 * timeout, 3 retries with 200&nbsp;ms back-off, batch size 256, flush
 * every second, buffering enabled.</p>
 *
 * <p>Instances of the builder are not thread-safe; share the built
 * {@link CortexClient}, not the builder.</p>
 */
public final class CortexClientBuilder {

    /** Default TCP connect timeout. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** Default per-request timeout. */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default retry back-off. */
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(200);

    /** Default flush interval for the buffered sender. */
    private static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(1);

    /** Default retry attempts on transport failure. */
    private static final int DEFAULT_MAX_RETRIES = 3;

    /** Default buffered-sender batch size. */
    private static final int DEFAULT_BATCH_SIZE = 256;

    /** Configured ingest endpoint; required. */
    private String endpoint;

    /** Configured bearer token; optional. */
    private String apiKey;

    /** TCP connect timeout. */
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

    /** Per-request timeout. */
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

    /** Retry back-off. */
    private Duration retryBackoff = DEFAULT_RETRY_BACKOFF;

    /** Buffered sender flush interval. */
    private Duration flushInterval = DEFAULT_FLUSH_INTERVAL;

    /** Maximum retry attempts on transport failure. */
    private int maxRetries = DEFAULT_MAX_RETRIES;

    /** Buffered sender batch size. */
    private int batchSize = DEFAULT_BATCH_SIZE;

    /** Whether the built client wraps the HTTP transport in a buffer. */
    private boolean buffered = true;

    /**
     * Sets the ingest endpoint URL. Required.
     *
     * @param endpointUrl absolute URL of the CORTEX ingest endpoint
     * @return this builder for chaining
     */
    public CortexClientBuilder endpoint(final String endpointUrl) {
        this.endpoint = endpointUrl;
        return this;
    }

    /**
     * Sets an optional bearer token sent in the {@code Authorization}
     * header.
     *
     * @param token bearer token; {@code null} or blank disables the header
     * @return this builder for chaining
     */
    public CortexClientBuilder apiKey(final String token) {
        this.apiKey = token;
        return this;
    }

    /**
     * Sets the TCP connect timeout.
     *
     * @param value timeout; must be positive
     * @return this builder for chaining
     */
    public CortexClientBuilder connectTimeout(final Duration value) {
        this.connectTimeout = value;
        return this;
    }

    /**
     * Sets the per-request timeout.
     *
     * @param value timeout; must be positive
     * @return this builder for chaining
     */
    public CortexClientBuilder requestTimeout(final Duration value) {
        this.requestTimeout = value;
        return this;
    }

    /**
     * Sets the back-off between retries.
     *
     * @param value back-off; must be non-negative
     * @return this builder for chaining
     */
    public CortexClientBuilder retryBackoff(final Duration value) {
        this.retryBackoff = value;
        return this;
    }

    /**
     * Sets the scheduled flush interval used by the buffered sender.
     *
     * @param value interval; must be positive
     * @return this builder for chaining
     */
    public CortexClientBuilder flushInterval(final Duration value) {
        this.flushInterval = value;
        return this;
    }

    /**
     * Sets the maximum retry attempts on transport failure.
     *
     * @param value retry count; clamped to {@code >= 0}
     * @return this builder for chaining
     */
    public CortexClientBuilder maxRetries(final int value) {
        this.maxRetries = value;
        return this;
    }

    /**
     * Sets the buffered-sender batch size.
     *
     * @param value batch size; must be {@code >= 1}
     * @return this builder for chaining
     */
    public CortexClientBuilder batchSize(final int value) {
        this.batchSize = value;
        return this;
    }

    /**
     * Toggles whether the built client wraps the HTTP transport in a
     * {@link BufferedSender}. Defaults to {@code true}; set to
     * {@code false} for low-volume or test scenarios that prefer
     * synchronous delivery.
     *
     * @param enable whether to wrap in a buffer
     * @return this builder for chaining
     */
    public CortexClientBuilder buffered(final boolean enable) {
        this.buffered = enable;
        return this;
    }

    /**
     * Builds a configured {@link CortexClient}.
     *
     * @return a new client; the caller owns its lifecycle and must
     *         {@link CortexClient#close()} it
     * @throws IllegalStateException    if required settings are missing
     * @throws IllegalArgumentException if the endpoint is malformed
     */
    public CortexClient build() {
        if (this.endpoint == null || this.endpoint.isBlank()) {
            throw new IllegalStateException("endpoint is required");
        }
        final URI uri = this.parseEndpoint(this.endpoint);
        final JsonCodec codec = new JsonCodec();
        final HttpCortexClientOptions options = new HttpCortexClientOptions(
                this.connectTimeout,
                this.requestTimeout,
                this.maxRetries,
                this.retryBackoff);
        final CortexClient transport = new HttpCortexClient(uri, this.apiKey, options, codec);
        if (!this.buffered) {
            return transport;
        }
        return new BufferedSender(transport, this.batchSize, this.flushInterval);
    }

    /**
     * Parses the endpoint URL, throwing a clear exception on failure.
     *
     * @param url candidate URL
     * @return parsed URI
     * @throws IllegalArgumentException if the URL is unparseable, relative,
     *                                  or missing a host component
     */
    private URI parseEndpoint(final String url) {
        try {
            final URI uri = new URI(url);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                throw new IllegalArgumentException(
                        "endpoint must be absolute with a host, got: " + url);
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("endpoint is not a valid URI: " + url, ex);
        }
    }
}
