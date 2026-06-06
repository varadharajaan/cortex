package io.cortex.remediation.dispatch;

import io.cortex.remediation.parse.AnomalyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link RemediationDispatcher} adapter for Slack Incoming Webhooks
 * (P6.1 / ADR-0032 D4 + ADR-0033; refactored P6.0a / ADR-0036 to
 * delegate the shared try/catch + outcome-classification shell to
 * {@link RestDispatchTemplate}).
 *
 * <p>This adapter is responsible for exactly two things:
 * (a) declaring whether the Slack channel is configured (non-blank
 * webhook URL), and (b) rendering + POSTing the Slack webhook body.
 * Everything else -- HTTP outcome classification, transport-failure
 * mapping, no-throw-on-transient discipline -- is owned by the
 * shared template so adding a P6.4 channel does not duplicate
 * any of that logic.</p>
 *
 * <p>Outcome table (ADR-0033 D3) is preserved bit-for-bit by the
 * template: 2xx -&gt; dispatched; 429 -&gt; transient slack:429;
 * 5xx -&gt; transient slack:5xx:&lt;n&gt;; other 4xx -&gt;
 * permanent slack:4xx:&lt;n&gt;; timeout -&gt; transient
 * slack:timeout; IO -&gt; transient slack:transport; blank URL -&gt;
 * skipped slack:unconfigured; null event -&gt; skipped
 * slack:null-event.</p>
 *
 * <p>Gated by {@code cortex.remediation.dispatcher.provider=slack}
 * so the only {@link RestClient} bean in this profile is
 * {@code slackRestClient} from {@link SlackHttpConfig} -- by-type
 * autowiring resolves unambiguously without a qualifier.</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "slack")
public final class SlackRemediationDispatcher implements RemediationDispatcher {

    private final SlackProperties properties;
    private final RestClient restClient;
    private final RestDispatchTemplate template =
            new RestDispatchTemplate(DispatchResult.CHANNEL_SLACK);

    @Override
    public String channelId() {
        return DispatchResult.CHANNEL_SLACK;
    }

    @Override
    public DispatchResult dispatch(final AnomalyEvent event) {
        return template.dispatch(event, this::isConfigured, this::executePost);
    }

    private boolean isConfigured() {
        return StringUtils.isNotBlank(this.properties.webhookUrl());
    }

    private void executePost(final AnomalyEvent event) {
        this.restClient.post()
                .uri(this.properties.webhookUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(renderBody(event))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Renders the Slack webhook JSON body for one anomaly event.
     * Package-private so {@code SlackRemediationDispatcherTest} can
     * assert the rendered shape without a live HTTP round-trip.
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
}
