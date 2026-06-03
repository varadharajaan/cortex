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
 * @param tenantId       optional tenant id sent as {@code X-Tenant-Id}
 *                       header; the ingest controller requires either
 *                       a service JWT or this header. {@code null} or
 *                       blank disables the header.
 */
public record HttpCortexClientOptions(
        Duration connectTimeout,
        Duration requestTimeout,
        int maxRetries,
        Duration retryBackoff,
        String tenantId) {

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

    /**
     * Convenience constructor preserving the original 4-arg signature
     * for callers that do not need a tenant header (e.g. callers using
     * a service JWT for auth).
     */
    public HttpCortexClientOptions(
            final Duration connectTimeout,
            final Duration requestTimeout,
            final int maxRetries,
            final Duration retryBackoff) {
        this(connectTimeout, requestTimeout, maxRetries, retryBackoff, null);
    }
}

