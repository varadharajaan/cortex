package io.cortex.remediation.dispatch;

import io.cortex.remediation.parse.AnomalyEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link RemediationDispatcher} adapter for the PagerDuty Events
 * API v2 enqueue endpoint (P6.2 / ADR-0032 D4 + ADR-0034).
 *
 * <p>Builds the PagerDuty Events API v2 JSON envelope
 * ({@code routing_key} + {@code event_action="trigger"} +
 * {@code dedup_key} + nested {@code payload} with {@code summary} +
 * {@code severity} + {@code source} + {@code custom_details}) from
 * the typed {@link AnomalyEvent} and POSTs it to the configured
 * {@link PagerDutyProperties#eventsUrl()}. PagerDuty's Events API v2
 * returns {@code 202 Accepted} (NOT {@code 200 OK}) with a JSON body
 * containing the deduped {@code dedup_key} on accepted enqueues; the
 * adapter maps the HTTP outcome onto a typed {@link DispatchResult}
 * per the table below.</p>
 *
 * <table>
 *   <caption>PagerDuty HTTP outcome &rarr; DispatchResult mapping (ADR-0034 D3)</caption>
 *   <tr><th>HTTP outcome</th><th>DispatchResult.outcome</th><th>reason</th></tr>
 *   <tr><td>2xx (202 Accepted)</td><td>{@code dispatched}</td><td>{@code ""}</td></tr>
 *   <tr><td>429</td><td>{@code transient_failure}</td><td>{@code pagerduty:429}</td></tr>
 *   <tr><td>5xx</td><td>{@code transient_failure}</td><td>{@code pagerduty:5xx:<n>}</td></tr>
 *   <tr><td>4xx (other)</td><td>{@code permanent_failure}</td><td>{@code pagerduty:4xx:<n>}</td></tr>
 *   <tr>
 *     <td>Timeout / IO</td>
 *     <td>{@code transient_failure}</td>
 *     <td>{@code pagerduty:timeout} / {@code pagerduty:transport}</td>
 *   </tr>
 *   <tr><td>blank routing key</td><td>{@code skipped}</td><td>{@code pagerduty:unconfigured}</td></tr>
 *   <tr><td>null event</td><td>{@code skipped}</td><td>{@code pagerduty:null-event}</td></tr>
 * </table>
 *
 * <p>Per ADR-0032 D6 + D7 + LD117 the adapter NEVER throws on a
 * transient downstream failure. Returning a typed verdict (vs
 * throwing) keeps the offset moving, ticks the failed-outcome
 * counter for operator alerting, and preserves the future P6.4 DLQ
 * + retry-budget axis without breaking the SPI contract.</p>
 *
 * <p>Gated by
 * {@code cortex.remediation.dispatcher.provider=pagerduty} (no
 * {@code matchIfMissing} -- the default stays {@link
 * NoopRemediationDispatcher}). Outbound HTTP is pinned to HTTP/1.1
 * via {@link PagerDutyHttpConfig} (LD42 + LD121, symmetric with
 * P5.3 sinks and P6.1 Slack adapter).</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "pagerduty")
public class PagerDutyRemediationDispatcher implements RemediationDispatcher {

    private static final Logger LOG =
            LoggerFactory.getLogger(PagerDutyRemediationDispatcher.class);

    /** PagerDuty rate-limit HTTP status (treated as transient per PD docs). */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** PagerDuty Events API v2 accepted severity values. */
    private static final Set<String> VALID_SEVERITIES =
            Set.of("critical", "error", "warning", "info");

    /** Trigger action emitted by every enqueue (we never ack/resolve here). */
    private static final String EVENT_ACTION_TRIGGER = "trigger";

    private final PagerDutyProperties properties;
    private final RestClient restClient;

    /**
     * Spring constructor.
     *
     * @param properties resolved PagerDuty properties (binds at
     *                   boot from {@code cortex.remediation.pagerduty.*})
     * @param restClient HTTP/1.1-pinned {@link RestClient} provided
     *                   by {@link PagerDutyHttpConfig}; injected by
     *                   bean name so the no-op profile (where this
     *                   adapter is not loaded) is unaffected
     */
    public PagerDutyRemediationDispatcher(final PagerDutyProperties properties,
                                          final RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public DispatchResult dispatch(final AnomalyEvent event) {
        if (event == null) {
            return DispatchResult.skipped("pagerduty:null-event");
        }
        final String key = this.properties.routingKey();
        if (StringUtils.isBlank(key)) {
            // Per ADR-0034 D1: an unconfigured PagerDuty profile
            // boots green; operator sees the skipped-outcome counter
            // climb instead of a CrashLoopBackOff. Real prod sets
            // the env var; the boot is intentionally tolerant.
            return DispatchResult.skipped("pagerduty:unconfigured");
        }
        final Map<String, Object> body = renderBody(event);
        try {
            this.restClient.post()
                    .uri(this.properties.eventsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            LOG.info("pagerduty dispatched eventId={} tenantId={} severity={}",
                    event.eventId(), event.tenantId(), event.severity());
            return DispatchResult.dispatched(DispatchResult.CHANNEL_PAGERDUTY);
        } catch (final RestClientResponseException ex) {
            return classifyHttp(event, ex);
        } catch (final ResourceAccessException ex) {
            return classifyTransport(event, ex);
        } catch (final RuntimeException ex) {
            // Catch-all per ADR-0032 D6 -- adapter MUST NOT propagate
            // transient failures into the consumer; the catch-all log
            // gives the operator visibility while the counter ticks.
            LOG.warn("pagerduty unexpected failure eventId={} tenantId={}: {}",
                    event.eventId(), event.tenantId(), ex.getMessage());
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_PAGERDUTY, "pagerduty:unknown");
        }
    }

    /**
     * Renders the PagerDuty Events API v2 JSON body for one anomaly
     * event.
     *
     * <p>Shape (ADR-0034 D2 trigger-only):</p>
     * <pre>
     * { "routing_key": "&lt;integration-key&gt;",
     *   "event_action": "trigger",
     *   "dedup_key":   "tenant-abc:evt-1",
     *   "payload": {
     *     "summary":  "HIGH anomaly on checkout: checkout 5xx burst",
     *     "severity": "error",
     *     "source":   "cortex-remediation",
     *     "custom_details": { eventId, tenantId, severity, reason,
     *                         ts, level, service, message }
     *   }
     * }
     * </pre>
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

    /**
     * Renders the dedup key by interpolating
     * {@code {tenantId}} + {@code {eventId}} placeholders into
     * {@link PagerDutyProperties#dedupKeyTemplate()}. Single-brace
     * placeholder syntax avoids collision with Spring's
     * {@code ${...}} property-placeholder substitution in yml.
     *
     * @param event source anomaly event (non-null)
     * @return dedup key string for the PagerDuty Events API v2 envelope
     */
    private String renderDedupKey(final AnomalyEvent event) {
        return this.properties.dedupKeyTemplate()
                .replace("{tenantId}",
                        StringUtils.defaultIfBlank(event.tenantId(), "unknown"))
                .replace("{eventId}",
                        StringUtils.defaultIfBlank(event.eventId(), "unknown"));
    }

    /**
     * Renders the PagerDuty Events API v2 {@code payload} object.
     *
     * @param event source anomaly event (non-null)
     * @return payload map (summary / severity / source / custom_details)
     */
    private Map<String, Object> renderPayload(final AnomalyEvent event) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", renderSummary(event));
        payload.put("severity", mapSeverity(event.severity()));
        payload.put("source", this.properties.source());
        payload.put("custom_details", renderCustomDetails(event));
        return payload;
    }

    /**
     * Renders the human-readable PagerDuty {@code payload.summary}
     * for a single anomaly.
     *
     * @param event source anomaly event (non-null)
     * @return human-readable summary string
     */
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

    /**
     * Renders the PagerDuty {@code payload.custom_details} block
     * with the full typed {@link AnomalyEvent} contents so the
     * incident card carries every queryable field.
     *
     * @param event source anomaly event (non-null)
     * @return custom-details map for Jackson JSON encoding
     */
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

    /**
     * Maps the AnomalyEvent severity to one of PagerDuty's accepted
     * severity values ({@code critical|error|warning|info}); falls
     * back to {@link PagerDutyProperties#severityDefault()} on any
     * unrecognised value (ADR-0034 D6).
     *
     * @param severity raw severity string from the AnomalyEvent
     *                 (may be null/blank/upper-case/mixed-case)
     * @return the canonical lowercase PagerDuty severity value
     */
    private String mapSeverity(final String severity) {
        if (StringUtils.isBlank(severity)) {
            return this.properties.severityDefault();
        }
        final String lower = severity.toLowerCase(Locale.ROOT);
        if (VALID_SEVERITIES.contains(lower)) {
            return lower;
        }
        return this.properties.severityDefault();
    }

    /**
     * Maps a PagerDuty HTTP non-2xx response onto the right
     * {@link DispatchResult} outcome.
     *
     * @param event the event under dispatch (used for log context)
     * @param ex    caught {@link RestClientResponseException}
     * @return typed verdict per the ADR-0034 D3 outcome table
     */
    private static DispatchResult classifyHttp(final AnomalyEvent event,
                                               final RestClientResponseException ex) {
        final HttpStatusCode status = ex.getStatusCode();
        final int code = status.value();
        LOG.warn("pagerduty non-2xx eventId={} tenantId={} status={}: {}",
                event.eventId(), event.tenantId(), code, ex.getMessage());
        if (code == HTTP_TOO_MANY_REQUESTS) {
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_PAGERDUTY, "pagerduty:429");
        }
        if (status.is5xxServerError()) {
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_PAGERDUTY, "pagerduty:5xx:" + code);
        }
        // 4xx (other than 429) is non-retriable: invalid routing key,
        // revoked integration, bad payload schema -- redeploy the env
        // var or fix the body shape, don't retry.
        return DispatchResult.permanentFailure(
                DispatchResult.CHANNEL_PAGERDUTY, "pagerduty:4xx:" + code);
    }

    /**
     * Maps a PagerDuty HTTP transport-layer failure onto the right
     * {@link DispatchResult} outcome.
     *
     * @param event the event under dispatch (used for log context)
     * @param ex    caught {@link ResourceAccessException} (timeout / IO)
     * @return typed verdict per the ADR-0034 D3 outcome table
     */
    private static DispatchResult classifyTransport(final AnomalyEvent event,
                                                    final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        final boolean timeout = cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException;
        LOG.warn("pagerduty transport failure eventId={} tenantId={} timeout={}: {}",
                event.eventId(), event.tenantId(), timeout, ex.getMessage());
        return DispatchResult.transientFailure(
                DispatchResult.CHANNEL_PAGERDUTY,
                timeout ? "pagerduty:timeout" : "pagerduty:transport");
    }
}
