package io.cortex.agent.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpCortexClientOptions}.
 */
class HttpCortexClientOptionsTest {

    /** Happy path: every field round-trips. */
    @Test
    void happyPathPopulatesAllFields() {
        final HttpCortexClientOptions options = new HttpCortexClientOptions(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                3,
                Duration.ofMillis(100));
        assertThat(options.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(options.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(options.maxRetries()).isEqualTo(3);
        assertThat(options.retryBackoff()).isEqualTo(Duration.ofMillis(100));
    }

    /** Negative retry counts are clamped to zero. */
    @Test
    void negativeMaxRetriesIsClampedToZero() {
        final HttpCortexClientOptions options = new HttpCortexClientOptions(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                -5,
                Duration.ofMillis(100));
        assertThat(options.maxRetries()).isZero();
    }

    /** {@code null} duration arguments are rejected. */
    @Test
    void nullDurationsRejected() {
        assertThatThrownBy(() -> new HttpCortexClientOptions(
                null, Duration.ofSeconds(1), 0, Duration.ofMillis(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpCortexClientOptions(
                Duration.ofSeconds(1), null, 0, Duration.ofMillis(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpCortexClientOptions(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 0, null))
                .isInstanceOf(NullPointerException.class);
    }
}
