package io.cortex.monitoring.slo;

import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * HTTP scrape configuration for the P8.3
 * {@link CounterFamilySloBudgetEngine}.
 *
 * <p>Bound to {@code cortex.monitoring.slo.counter-family}. The
 * backend discovers the target service by
 * {@link SloDefinition#serviceId()}, appends
 * {@link #actuatorPath()} to the selected instance URI, and reads
 * the Prometheus text exposition from that endpoint.</p>
 *
 * @param requestTimeout connect + read timeout for the scrape
 *                       client; defaults to
 *                       {@link #DEFAULT_REQUEST_TIMEOUT}
 * @param actuatorPath   target service actuator Prometheus path;
 *                       defaults to
 *                       {@link #DEFAULT_ACTUATOR_PATH}
 */
@Validated
@ConfigurationProperties(prefix = "cortex.monitoring.slo.counter-family")
public record CounterFamilySloProperties(Duration requestTimeout,
                                         String actuatorPath) {

    /** Default per-call request timeout for remote metric scrapes. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default actuator path that exposes Prometheus text metrics. */
    public static final String DEFAULT_ACTUATOR_PATH = "/actuator/prometheus";

    /**
     * Defensive defaults so a partially-filled operator config
     * still boots. The backend remains opt-in via
     * {@code cortex.monitoring.slo.backend=counter-family}.
     */
    public CounterFamilySloProperties {
        if (requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        actuatorPath = StringUtils.isBlank(actuatorPath)
                ? DEFAULT_ACTUATOR_PATH
                : actuatorPath;
        if (!actuatorPath.startsWith("/")) {
            actuatorPath = "/" + actuatorPath;
        }
    }
}
