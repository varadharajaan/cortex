package io.cortex.remediation.dispatch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the {@link PagerDutyRemediationDispatcher}
 * (P6.2 / ADR-0034).
 *
 * <p>Bound to prefix {@code cortex.remediation.pagerduty}. The
 * {@code routing-key} is the "integration key" minted on a PagerDuty
 * Events API v2 integration attached to a service -- NOT an OAuth
 * token. Rotating it MUST be done via env-var redeploy (no
 * hot-reload by design -- a leaked routing key is a privilege-
 * escalation vector that warrants a restart per Part 9.4).
 * {@code request-timeout} controls BOTH connect + read timeouts on
 * the underlying JDK HTTP client (LD42 HTTP/1.1 pin + LD121 forward
 * rule from P6.1).</p>
 *
 * @param routingKey         PagerDuty Events API v2 integration key
 *                           (32-character hex); blank value forces
 *                           the dispatcher into the "skipped
 *                           (unconfigured)" path so a profile that
 *                           selects the PagerDuty provider without
 *                           supplying the secret still boots green
 * @param requestTimeout     per-call advisory timeout for connect +
 *                           read (default 5 s); enforced by the JDK
 *                           HTTP client; on expiry the adapter
 *                           returns {@link
 *                           DispatchResult#transientFailure(String, String)}
 *                           with {@code reason=pagerduty:timeout}
 * @param eventsUrl          PagerDuty Events API v2 enqueue endpoint
 *                           (default {@value #DEFAULT_EVENTS_URL});
 *                           override only for EU customers
 *                           ({@code https://events.eu.pagerduty.com/v2/enqueue})
 *                           or stub/test servers
 * @param dedupKeyTemplate   Pattern used to build the PagerDuty
 *                           {@code dedup_key} field; supports
 *                           {@code {tenantId}} and {@code {eventId}}
 *                           placeholders (single braces -- avoids
 *                           collision with Spring's {@code ${...}}
 *                           property-placeholder syntax in yml).
 *                           Default {@value #DEFAULT_DEDUP_KEY_TEMPLATE}
 *                           yields per-event natural idempotency so
 *                           a duplicate Kafka redelivery coalesces
 *                           on PagerDuty's side
 * @param source             Value placed in the PagerDuty payload
 *                           {@code source} field (default
 *                           {@value #DEFAULT_SOURCE}); appears on
 *                           the incident card
 * @param severityDefault    Fallback PagerDuty severity used when
 *                           the AnomalyEvent severity does not map
 *                           to one of PagerDuty's
 *                           {@code critical|error|warning|info}
 *                           (default {@value #DEFAULT_SEVERITY})
 */
@ConfigurationProperties(prefix = "cortex.remediation.pagerduty")
public record PagerDutyProperties(String routingKey, Duration requestTimeout,
                                  String eventsUrl, String dedupKeyTemplate,
                                  String source, String severityDefault) {

    /** Default per-call advisory request timeout (LD42 HTTP/1.1 pin + LD121). */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default PagerDuty Events API v2 enqueue endpoint (US region). */
    public static final String DEFAULT_EVENTS_URL =
            "https://events.pagerduty.com/v2/enqueue";

    /** Default dedup-key template -- per-event natural idempotency. */
    public static final String DEFAULT_DEDUP_KEY_TEMPLATE = "{tenantId}:{eventId}";

    /** Default PagerDuty payload {@code source} field. */
    public static final String DEFAULT_SOURCE = "cortex-remediation";

    /** Default PagerDuty severity fallback when the AnomalyEvent severity is unrecognised. */
    public static final String DEFAULT_SEVERITY = "error";

    /**
     * Defensive defaults so a partially-filled yml block still wires.
     *
     * @param routingKey        see record-level Javadoc; null/blank
     *                          coerces to empty string so the
     *                          dispatcher's unconfigured guard fires
     *                          deterministically
     * @param requestTimeout    see record-level Javadoc; null coerces
     *                          to {@link #DEFAULT_REQUEST_TIMEOUT}
     * @param eventsUrl         see record-level Javadoc; null/blank
     *                          coerces to {@link #DEFAULT_EVENTS_URL}
     * @param dedupKeyTemplate  see record-level Javadoc; null/blank
     *                          coerces to {@link #DEFAULT_DEDUP_KEY_TEMPLATE}
     * @param source            see record-level Javadoc; null/blank
     *                          coerces to {@link #DEFAULT_SOURCE}
     * @param severityDefault   see record-level Javadoc; null/blank
     *                          coerces to {@link #DEFAULT_SEVERITY}
     */
    public PagerDutyProperties {
        if (routingKey == null) {
            routingKey = "";
        }
        if (requestTimeout == null) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        if (eventsUrl == null || eventsUrl.isBlank()) {
            eventsUrl = DEFAULT_EVENTS_URL;
        }
        if (dedupKeyTemplate == null || dedupKeyTemplate.isBlank()) {
            dedupKeyTemplate = DEFAULT_DEDUP_KEY_TEMPLATE;
        }
        if (source == null || source.isBlank()) {
            source = DEFAULT_SOURCE;
        }
        if (severityDefault == null || severityDefault.isBlank()) {
            severityDefault = DEFAULT_SEVERITY;
        }
    }
}
