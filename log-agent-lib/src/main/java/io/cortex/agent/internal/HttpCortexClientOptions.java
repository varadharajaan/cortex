package io.cortex.agent.internal;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable transport-tuning knobs for {@link HttpCortexClient}.
 *
 * <p>Bundled into a single value object so the client constructor
 * stays under the project-wide six-parameter limit.</p>
 *
 * @param connectTimeout TCP connect timeout; must not be {@code null}
 * @param requestTimeout per-request timeout; must not be {@code null}
 * @param maxRetries     retry attempts on failure; clamped to {@code >= 0}
 * @param retryBackoff   delay between retries; must not be {@code null}
 */
public record HttpCortexClientOptions(
        Duration connectTimeout,
        Duration requestTimeout,
        int maxRetries,
        Duration retryBackoff) {

    /**
     * Canonical constructor with null checks and retry clamping.
     */
    public HttpCortexClientOptions {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        Objects.requireNonNull(retryBackoff, "retryBackoff");
        if (maxRetries < 0) {
            maxRetries = 0;
        }
    }
}
