package io.cortex.remediation.dispatch;

import io.cortex.remediation.parse.AnomalyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * {@link RemediationDispatcher} adapter for Slack Incoming Webhooks
 * (P6.1 / ADR-0032 D4 + ADR-0033).
 *
 * <p>Renders a plain-text Slack message from the typed
 * {@link AnomalyEvent} ({@code rotating_light} icon + severity +
 * service + tenant + reason) and POSTs it to the configured
 * {@link SlackProperties#webhookUrl()} as a single JSON body. Slack
 * returns {@code 200 OK} with body {@code "ok"} on accepted webhook
 * deliveries; the adapter maps the HTTP outcome onto a typed
 * {@link DispatchResult} per the table below.</p>
 *
 * <table>
 *   <caption>Slack HTTP outcome &rarr; DispatchResult mapping (ADR-0033 D3)</caption>
 *   <tr><th>HTTP outcome</th><th>DispatchResult.outcome</th><th>reason</th></tr>
 *   <tr><td>2xx</td><td>{@code dispatched}</td><td>{@code ""}</td></tr>
 *   <tr><td>429</td><td>{@code transient_failure}</td><td>{@code slack:429}</td></tr>
 *   <tr><td>5xx</td><td>{@code transient_failure}</td><td>{@code slack:5xx:<n>}</td></tr>
 *   <tr><td>4xx (other)</td><td>{@code permanent_failure}</td><td>{@code slack:4xx:<n>}</td></tr>
 *   <tr>
 *     <td>Timeout / IO</td>
 *     <td>{@code transient_failure}</td>
 *     <td>{@code slack:timeout} / {@code slack:transport}</td>
 *   </tr>
 *   <tr><td>blank webhook URL</td><td>{@code skipped}</td><td>{@code slack:unconfigured}</td></tr>
 *   <tr><td>null event</td><td>{@code skipped}</td><td>{@code slack:null-event}</td></tr>
 * </table>
 *
 * <p>Per ADR-0032 D6 + D7 + LD117 the adapter NEVER throws on a
 * transient downstream failure. Returning a typed verdict (vs
 * throwing) keeps the offset moving, ticks the failed-outcome
 * counter for operator alerting, and preserves the future P6.4 DLQ
 * + retry-budget axis without breaking the SPI contract.</p>
 *
 * <p>Gated by {@code cortex.remediation.dispatcher.provider=slack}
 * (no {@code matchIfMissing} -- the default stays {@link
 * NoopRemediationDispatcher}). Outbound HTTP is pinned to HTTP/1.1
 * via {@link SlackHttpConfig} (LD42, symmetric with P5.3 sinks).</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "slack")
public class SlackRemediationDispatcher implements RemediationDispatcher {

    private static final Logger LOG =
            LoggerFactory.getLogger(SlackRemediationDispatcher.class);

    /** Slack rate-limit HTTP status (treated as transient per Slack docs). */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final SlackProperties properties;
    private final RestClient restClient;

    /**
     * Spring constructor.
     *
     * @param properties resolved Slack properties (binds at boot
     *                   from {@code cortex.remediation.slack.*})
     * @param restClient HTTP/1.1-pinned {@link RestClient} provided
     *                   by {@link SlackHttpConfig}; injected by
     *                   bean name so the no-op profile (where this
     *                   adapter is not loaded) is unaffected
     */
    public SlackRemediationDispatcher(final SlackProperties properties,
                                      final RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public DispatchResult dispatch(final AnomalyEvent event) {
        if (event == null) {
            return DispatchResult.skipped("slack:null-event");
        }
        final String url = this.properties.webhookUrl();
        if (StringUtils.isBlank(url)) {
            // Per ADR-0033 D1: an unconfigured Slack profile boots
            // green; the operator sees the skipped-outcome counter
            // climb instead of a CrashLoopBackOff. Real prod sets
            // the env var; the boot is intentionally tolerant.
            return DispatchResult.skipped("slack:unconfigured");
        }
        final Map<String, Object> body = renderBody(event);
        try {
            this.restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            LOG.info("slack dispatched eventId={} tenantId={} severity={}",
                    event.eventId(), event.tenantId(), event.severity());
            return DispatchResult.dispatched(DispatchResult.CHANNEL_SLACK);
        } catch (final RestClientResponseException ex) {
            return classifyHttp(event, ex);
        } catch (final ResourceAccessException ex) {
            return classifyTransport(event, ex);
        } catch (final RuntimeException ex) {
            // Catch-all per ADR-0032 D6 -- adapter MUST NOT propagate
            // transient failures into the consumer; the catch-all log
            // gives the operator visibility while the counter ticks.
            LOG.warn("slack unexpected failure eventId={} tenantId={}: {}",
                    event.eventId(), event.tenantId(), ex.getMessage());
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_SLACK, "slack:unknown");
        }
    }

    /**
     * Renders the Slack webhook JSON body for one anomaly event.
     *
     * <p>Shape (ADR-0033 D2 plain-text):</p>
     * <pre>
     * { "text": ":rotating_light: HIGH anomaly on checkout
     *           (tenant=tenant-abc): checkout 5xx burst",
     *   "username": "cortex-remediation",   // optional override
     *   "channel": "#sre-incidents"         // optional override
     * }
     * </pre>
     *
     * @param event source anomaly event (non-null)
     * @return body map suitable for Jackson JSON encoding
     */
    Map<String, Object> renderBody(final AnomalyEvent event) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", renderText(event));
        final String user = this.properties.username();
        if (StringUtils.isNotBlank(user)) {
            body.put("username", user);
        }
        final String channel = this.properties.channelOverride();
        if (StringUtils.isNotBlank(channel)) {
            body.put("channel", channel);
        }
        return body;
    }

    /**
     * Renders the human-readable Slack message text for a single anomaly.
     *
     * @param event source anomaly event (non-null)
     * @return plain-text Slack message body
     */
    private static String renderText(final AnomalyEvent event) {
        final StringBuilder sb = new StringBuilder();
        sb.append(":rotating_light: ")
                .append(StringUtils.defaultIfBlank(event.severity(), "UNKNOWN"))
                .append(" anomaly on ")
                .append(StringUtils.defaultIfBlank(event.service(), "unknown-service"))
                .append(" (tenant=")
                .append(StringUtils.defaultIfBlank(event.tenantId(), "unknown"))
                .append(')');
        final String reason = event.reason();
        if (StringUtils.isNotBlank(reason)) {
            sb.append(": ").append(reason);
        }
        return sb.toString();
    }

    /**
     * Maps a Slack HTTP non-2xx response onto the right
     * {@link DispatchResult} outcome.
     *
     * @param event the event under dispatch (used for log context)
     * @param ex    caught {@link RestClientResponseException}
     * @return typed verdict per the ADR-0033 D3 outcome table
     */
    private static DispatchResult classifyHttp(final AnomalyEvent event,
                                               final RestClientResponseException ex) {
        final HttpStatusCode status = ex.getStatusCode();
        final int code = status.value();
        LOG.warn("slack non-2xx eventId={} tenantId={} status={}: {}",
                event.eventId(), event.tenantId(), code, ex.getMessage());
        if (code == HTTP_TOO_MANY_REQUESTS) {
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_SLACK, "slack:429");
        }
        if (status.is5xxServerError()) {
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_SLACK, "slack:5xx:" + code);
        }
        // 4xx (other than 429) is non-retriable: invalid body, revoked
        // webhook, unauthorized -- redeploy the env var, don't retry.
        return DispatchResult.permanentFailure(
                DispatchResult.CHANNEL_SLACK, "slack:4xx:" + code);
    }

    /**
     * Maps a Slack HTTP transport-layer failure onto the right
     * {@link DispatchResult} outcome.
     *
     * @param event the event under dispatch (used for log context)
     * @param ex    caught {@link ResourceAccessException} (timeout / IO)
     * @return typed verdict per the ADR-0033 D3 outcome table
     */
    private static DispatchResult classifyTransport(final AnomalyEvent event,
                                                    final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        final boolean timeout = cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException;
        LOG.warn("slack transport failure eventId={} tenantId={} timeout={}: {}",
                event.eventId(), event.tenantId(), timeout, ex.getMessage());
        return DispatchResult.transientFailure(
                DispatchResult.CHANNEL_SLACK,
                timeout ? "slack:timeout" : "slack:transport");
    }
}
