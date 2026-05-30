package io.cortex.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CortexClientBuilder}.
 */
class CortexClientBuilderTest {

    /** Build without an endpoint should fail fast with a clear message. */
    @Test
    void missingEndpointIsRejected() {
        assertThatThrownBy(() -> new CortexClientBuilder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint");
    }

    /** Blank endpoint is treated the same as missing. */
    @Test
    void blankEndpointIsRejected() {
        assertThatThrownBy(() -> new CortexClientBuilder().endpoint("   ").build())
                .isInstanceOf(IllegalStateException.class);
    }

    /** A malformed URL must surface as {@link IllegalArgumentException}. */
    @Test
    void malformedEndpointIsRejected() {
        assertThatThrownBy(() -> new CortexClientBuilder()
                .endpoint("ht tp://bad url")
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Relative URIs (no scheme / host) are rejected at build time. */
    @Test
    void relativeEndpointIsRejected() {
        assertThatThrownBy(() -> new CortexClientBuilder()
                .endpoint("/ingest")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> new CortexClientBuilder()
                .endpoint("file:///tmp/x")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    /** Default build produces a buffered, closeable client. */
    @Test
    void defaultsProduceBufferedClient() {
        try (CortexClient client = new CortexClientBuilder()
                .endpoint("http://localhost:0/ingest")
                .build()) {
            assertThat(client).isNotNull();
        }
    }

    /** {@code buffered(false)} returns a synchronous client. */
    @Test
    void unbufferedProducesSyncClient() {
        try (CortexClient client = new CortexClientBuilder()
                .endpoint("http://localhost:0/ingest")
                .buffered(false)
                .build()) {
            assertThat(client).isNotNull();
        }
    }

    /** All fluent setters return the same builder instance. */
    @Test
    void allSettersReturnSameBuilder() {
        final CortexClientBuilder builder = new CortexClientBuilder();
        assertThat(builder.endpoint("http://x")).isSameAs(builder);
        assertThat(builder.apiKey("k")).isSameAs(builder);
        assertThat(builder.connectTimeout(Duration.ofSeconds(1))).isSameAs(builder);
        assertThat(builder.requestTimeout(Duration.ofSeconds(1))).isSameAs(builder);
        assertThat(builder.retryBackoff(Duration.ofMillis(50))).isSameAs(builder);
        assertThat(builder.flushInterval(Duration.ofMillis(500))).isSameAs(builder);
        assertThat(builder.maxRetries(2)).isSameAs(builder);
        assertThat(builder.batchSize(64)).isSameAs(builder);
        assertThat(builder.buffered(true)).isSameAs(builder);
    }

    /** Full configuration builds a working client. */
    @Test
    void fullyConfiguredBuilds() {
        try (CortexClient client = new CortexClientBuilder()
                .endpoint("http://localhost:0/ingest")
                .apiKey("token")
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(1))
                .retryBackoff(Duration.ofMillis(10))
                .flushInterval(Duration.ofMillis(500))
                .maxRetries(1)
                .batchSize(8)
                .build()) {
            assertThat(client).isNotNull();
        }
    }
}
