package io.cortex.monitoring.probe.eureka;

import io.cortex.monitoring.constants.MonitoringHttp;
import io.cortex.monitoring.probe.HealthSnapshot;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Channel-agnostic outcome classification template for
 * {@link io.cortex.monitoring.probe.ServiceHealthProbe} adapters
 * (P8.1 / ADR-0045 D3; mirror of the P7.1
 * {@code RestAdminTemplate} composition pattern, ADR-0039 D3).
 *
 * <p>Effective Java item 18 -- favour composition over inheritance.
 * The single {@link EurekaActuatorHealthProbe} caller orchestrates
 * the HTTP flow itself (since the happy paths return {@code
 * healthy} / {@code degraded} / {@code unhealthy} verdicts that
 * vary per {@code status} field value rather than per HTTP status
 * code) and delegates the failure catch arms to the three
 * {@code classify*} helpers below. When the next probe backend
 * lands (e.g. K8s-native readiness in a hypothetical P9.x), it can
 * reuse the same template by passing its own {@code backendId} --
 * the outcome table is identical.</p>
 *
 * <p><strong>Outcome table</strong> (ADR-0045 D3):</p>
 * <ul>
 *   <li>HTTP {@code 429} -&gt; transient
 *       {@code &lt;backend&gt;:429}</li>
 *   <li>HTTP {@code 5xx} -&gt; transient
 *       {@code &lt;backend&gt;:5xx:&lt;n&gt;}</li>
 *   <li>HTTP other {@code 4xx} -&gt; permanent
 *       {@code &lt;backend&gt;:4xx:&lt;n&gt;}</li>
 *   <li>{@link HttpTimeoutException}/{@link TimeoutException}
 *       cause -&gt; transient
 *       {@code &lt;backend&gt;:timeout}</li>
 *   <li>other {@link ResourceAccessException} -&gt; transient
 *       {@code &lt;backend&gt;:transport}</li>
 *   <li>unexpected {@link RuntimeException} -&gt; transient
 *       {@code &lt;backend&gt;:unknown}</li>
 * </ul>
 *
 * <p>The template intentionally does <strong>not</strong> handle
 * the HTTP {@code 2xx} bucket -- that's caller-specific
 * (the probe must parse the {@code status} field out of the body
 * before mapping to {@code healthy} / {@code degraded} /
 * {@code unhealthy}). Pulling the body parse into the template
 * would force every future probe to accept the same JSON shape.</p>
 */
final class RestProbeTemplate {

    /** Backend identifier tag stamped into every result. */
    private final String backendId;

    /**
     * Build a template bound to a specific backend id (e.g.
     * {@link HealthSnapshot#BACKEND_EUREKA_ACTUATOR}).
     *
     * @param backendId the backend tag stamped into every result
     */
    RestProbeTemplate(final String backendId) {
        this.backendId = backendId;
    }

    /**
     * Classify a non-2xx HTTP response surfaced as
     * {@link RestClientResponseException} into a
     * {@link HealthSnapshot}.
     *
     * @param ex the response exception thrown by {@code .retrieve()}
     * @return transient outcome for 429/5xx, permanent for other
     *         4xx
     */
    HealthSnapshot classifyHttp(final RestClientResponseException ex) {
        final int code = ex.getStatusCode().value();
        if (code == MonitoringHttp.TOO_MANY_REQUESTS) {
            return HealthSnapshot.transientFailure(
                    this.backendId, this.backendId + ":429");
        }
        if (code >= MonitoringHttp.SERVER_ERROR_FLOOR) {
            return HealthSnapshot.transientFailure(
                    this.backendId, this.backendId + ":5xx:" + code);
        }
        return HealthSnapshot.permanentFailure(
                this.backendId, this.backendId + ":4xx:" + code);
    }

    /**
     * Classify a transport-layer failure surfaced as
     * {@link ResourceAccessException} into a transient
     * {@link HealthSnapshot}. Cause-based discrimination separates
     * {@code timeout} (JDK {@link HttpTimeoutException} or
     * {@link TimeoutException}) from generic {@code transport}
     * (connection reset, DNS, TLS handshake, etc.).
     *
     * @param ex the transport exception thrown by {@code .retrieve()}
     * @return transient outcome tagged {@code timeout} or
     *         {@code transport}
     */
    HealthSnapshot classifyTransport(final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        if (cause instanceof HttpTimeoutException
                || cause instanceof TimeoutException) {
            return HealthSnapshot.transientFailure(
                    this.backendId, this.backendId + ":timeout");
        }
        return HealthSnapshot.transientFailure(
                this.backendId, this.backendId + ":transport");
    }

    /**
     * Classify any unexpected {@link RuntimeException} (e.g.
     * Jackson parsing error, NullPointerException from a bug) into
     * a transient outcome so the SPI contract holds -- the SPI
     * must never propagate an exception to the caller.
     *
     * @param ex the unexpected exception (captured by the caller's
     *           log line; this method only produces the result
     *           envelope)
     * @return transient outcome tagged {@code unknown}
     */
    HealthSnapshot classifyUnknown(final RuntimeException ex) {
        return HealthSnapshot.transientFailure(
                this.backendId, this.backendId + ":unknown");
    }
}
