package io.cortex.monitoring.slo;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * HTTP configuration for the P8.5 PromQL SLO backend.
 *
 * @param requestTimeout connect + read timeout for Prometheus API
 * @param baseUrl base URL of Prometheus
 */
@Validated
@ConfigurationProperties(prefix = "cortex.monitoring.slo.promql")
public record PromQlSloProperties(Duration requestTimeout, URI baseUrl) {

    /** Default per-call request timeout. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default local Prometheus base URL. */
    public static final URI DEFAULT_BASE_URL =
            URI.create("http://localhost:9090");

    /**
     * Defensive defaults for partially-filled operator config.
     */
    public PromQlSloProperties {
        if (requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        if (baseUrl == null) {
            baseUrl = DEFAULT_BASE_URL;
        }
    }
}
