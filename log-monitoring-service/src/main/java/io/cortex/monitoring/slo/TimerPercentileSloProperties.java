package io.cortex.monitoring.slo;

import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * HTTP scrape configuration for the P8.4 timer-percentile SLO
 * backend.
 *
 * @param requestTimeout connect + read timeout for target scrapes
 * @param actuatorPath target service Prometheus exposition path
 */
@Validated
@ConfigurationProperties(prefix = "cortex.monitoring.slo.timer-percentile")
public record TimerPercentileSloProperties(Duration requestTimeout,
                                           String actuatorPath) {

    /** Default per-call request timeout for remote metric scrapes. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default actuator path that exposes Prometheus text metrics. */
    public static final String DEFAULT_ACTUATOR_PATH = "/actuator/prometheus";

    /**
     * Defensive defaults for partially-filled operator config.
     */
    public TimerPercentileSloProperties {
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
