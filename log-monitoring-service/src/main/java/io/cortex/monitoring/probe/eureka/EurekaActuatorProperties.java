package io.cortex.monitoring.probe.eureka;

import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for {@link EurekaActuatorHealthProbe}
 * (P8.1 / ADR-0045 D2).
 *
 * <p>Bound to prefix {@code cortex.monitoring.eureka}. The
 * {@code request-timeout} controls both the connect + read
 * timeouts on the underlying JDK HTTP client per LD42 HTTP/1.1
 * pin + LD121 dual-timeout. The
 * {@code actuator-path} is the per-instance URI segment the
 * probe appends to {@code instance.getUri()}; the default
 * {@code /actuator/health} is correct for every cortex service
 * (each registers under Part 17's actuator exposure rules).</p>
 *
 * @param requestTimeout per-call advisory timeout for connect +
 *                       read (default 5 s); enforced by the JDK
 *                       HTTP client; on expiry the adapter
 *                       returns a transient-failure outcome with
 *                       {@code reason=eureka-actuator:timeout}
 * @param actuatorPath   URI segment appended to
 *                       {@code instance.getUri()}; null/blank
 *                       coerces to {@link #DEFAULT_ACTUATOR_PATH}
 *                       so a partially-filled yml still wires
 */
@Validated
@ConfigurationProperties(prefix = "cortex.monitoring.eureka")
public record EurekaActuatorProperties(Duration requestTimeout,
                                       String actuatorPath) {

    /** Default per-call advisory request timeout (LD42 HTTP/1.1 pin). */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Default URI segment appended to the per-instance
     * {@code getUri()} when building the actuator URL. Every
     * cortex service exposes {@code /actuator/health} per Part 17.
     */
    public static final String DEFAULT_ACTUATOR_PATH = "/actuator/health";

    /**
     * Defensive defaults so a single missing env-var doesn't
     * crash the boot -- the noop backend is still the production
     * default, so the only consumers of this class are tests +
     * dev-mode boots that opted into
     * {@code backend=eureka-actuator}.
     */
    public EurekaActuatorProperties {
        if (requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        actuatorPath = StringUtils.isBlank(actuatorPath)
                ? DEFAULT_ACTUATOR_PATH
                : actuatorPath;
    }
}
