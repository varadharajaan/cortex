package io.cortex.remediation.dispatch;

import io.cortex.remediation.parse.AnomalyEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link RemediationDispatcher} adapter for the PagerDuty Events
 * API v2 enqueue endpoint (P6.2 / ADR-0032 D4 + ADR-0034; refactored
 * P6.0a / ADR-0036 to delegate the shared try/catch + outcome-
 * classification shell to {@link RestDispatchTemplate}).
 *
 * <p>The adapter owns only the channel-specific concerns: configured-
 * check (non-blank routing key), Events-API v2 body shape, and the
 * PagerDuty-specific severity mapping. The template handles HTTP +
 * transport classification, no-throw-on-transient discipline, and
 * logging.</p>
 *
 * <p>Outcome table (ADR-0034 D3) is preserved bit-for-bit by the
 * template: 2xx (202 Accepted) -&gt; dispatched; 429 -&gt; transient
 * pagerduty:429; 5xx -&gt; transient pagerduty:5xx:&lt;n&gt;;
 * other 4xx -&gt; permanent pagerduty:4xx:&lt;n&gt;; timeout -&gt;
 * transient pagerduty:timeout; IO -&gt; transient
 * pagerduty:transport; blank routing key -&gt; skipped
 * pagerduty:unconfigured; null event -&gt; skipped
 * pagerduty:null-event.</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "pagerduty")
public final class PagerDutyRemediationDispatcher implements RemediationDispatcher {

    /** PagerDuty Events API v2 accepted severity values. */
    private static final Set<String> VALID_SEVERITIES =
            Set.of("critical", "error", "warning", "info");

    /** Trigger action emitted by every enqueue (we never ack/resolve here). */
    private static final String EVENT_ACTION_TRIGGER = "trigger";

    private final PagerDutyProperties properties;
    private final RestClient restClient;
    private final RestDispatchTemplate template =
            new RestDispatchTemplate(DispatchResult.CHANNEL_PAGERDUTY);

    @Override
    public String channelId() {
        return DispatchResult.CHANNEL_PAGERDUTY;
    }

    @Override
    public DispatchResult dispatch(final AnomalyEvent event) {
        return template.dispatch(event, this::isConfigured, this::executePost);
    }

    private boolean isConfigured() {
        return StringUtils.isNotBlank(this.properties.routingKey());
    }

    private void executePost(final AnomalyEvent event) {
        this.restClient.post()
                .uri(this.properties.eventsUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(renderBody(event))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Renders the PagerDuty Events API v2 JSON body for one anomaly.
     * Package-private so the unit test asserts the shape without a
     * live HTTP round-trip.
     *
     * @param event source anomaly event (non-null)
     * @return body map suitable for Jackson JSON encoding
     */
    Map<String, Object> renderBody(final AnomalyEvent event) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("routing_key", this.properties.routingKey());
        body.put("event_action", EVENT_ACTION_TRIGGER);
        body.put("dedup_key", renderDedupKey(event));
        body.put("payload", renderPayload(event));
        return body;
    }

    private String renderDedupKey(final AnomalyEvent event) {
        return this.properties.dedupKeyTemplate()
                .replace("{tenantId}",
                        StringUtils.defaultIfBlank(event.tenantId(), "unknown"))
                .replace("{eventId}",
                        StringUtils.defaultIfBlank(event.eventId(), "unknown"));
    }

    private Map<String, Object> renderPayload(final AnomalyEvent event) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", renderSummary(event));
        payload.put("severity", mapSeverity(event.severity()));
        payload.put("source", this.properties.source());
        payload.put("custom_details", renderCustomDetails(event));
        return payload;
    }

    private static String renderSummary(final AnomalyEvent event) {
        final StringBuilder sb = new StringBuilder();
        sb.append(StringUtils.defaultIfBlank(event.severity(), "UNKNOWN"))
                .append(" anomaly on ")
                .append(StringUtils.defaultIfBlank(event.service(), "unknown-service"));
        final String reason = event.reason();
        if (StringUtils.isNotBlank(reason)) {
            sb.append(": ").append(reason);
        }
        return sb.toString();
    }

    private static Map<String, Object> renderCustomDetails(final AnomalyEvent event) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("eventId", StringUtils.defaultIfBlank(event.eventId(), ""));
        details.put("tenantId", StringUtils.defaultIfBlank(event.tenantId(), ""));
        details.put("severity", StringUtils.defaultIfBlank(event.severity(), ""));
        details.put("reason", StringUtils.defaultIfBlank(event.reason(), ""));
        if (event.ts() != null) {
            details.put("ts", event.ts().toString());
        }
        details.put("level", StringUtils.defaultIfBlank(event.level(), ""));
        details.put("service", StringUtils.defaultIfBlank(event.service(), ""));
        details.put("message", StringUtils.defaultIfBlank(event.message(), ""));
        return details;
    }

    private String mapSeverity(final String severity) {
        if (StringUtils.isBlank(severity)) {
            return this.properties.severityDefault();
        }
        final String lower = severity.toLowerCase(Locale.ROOT);
        return VALID_SEVERITIES.contains(lower) ? lower : this.properties.severityDefault();
    }
}
