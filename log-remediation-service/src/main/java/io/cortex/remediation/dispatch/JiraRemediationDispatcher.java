package io.cortex.remediation.dispatch;

import io.cortex.remediation.parse.AnomalyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link RemediationDispatcher} adapter for the Jira Cloud REST
 * API v3 create-issue endpoint (P6.3 / ADR-0032 D4 + ADR-0035;
 * refactored P6.0a / ADR-0036 to delegate the shared try/catch +
 * outcome-classification shell to {@link RestDispatchTemplate}).
 *
 * <p>The adapter owns only the channel-specific concerns:
 * configured-check (all four credential fields non-blank),
 * Atlassian Document Format (ADF) body shape, label rendering, and
 * the Basic-auth header. The template handles HTTP + transport
 * classification, no-throw-on-transient discipline, and logging.</p>
 *
 * <p>Outcome table (ADR-0035 D3) is preserved bit-for-bit by the
 * template: 2xx (201 Created) -&gt; dispatched; 429 -&gt; transient
 * jira:429; 5xx -&gt; transient jira:5xx:&lt;n&gt;; other 4xx -&gt;
 * permanent jira:4xx:&lt;n&gt;; timeout -&gt; transient jira:timeout;
 * IO -&gt; transient jira:transport; blank credential -&gt; skipped
 * jira:unconfigured; null event -&gt; skipped jira:null-event.</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "jira")
public final class JiraRemediationDispatcher implements RemediationDispatcher {

    /** Jira REST API v3 create-issue path appended to {@link JiraProperties#baseUrl()}. */
    private static final String CREATE_ISSUE_PATH = "/rest/api/3/issue";

    /** Static label every Cortex-created Jira issue carries for operator filtering. */
    private static final String STATIC_LABEL = "cortex-remediation";

    /** Tenant label prefix joined to {@link AnomalyEvent#tenantId()} via {@code :}. */
    private static final String TENANT_LABEL_PREFIX = "tenant:";

    /** Max Jira summary length (Atlassian-imposed hard limit). */
    private static final int SUMMARY_MAX_LEN = 255;

    private final JiraProperties properties;
    private final RestClient restClient;
    private final RestDispatchTemplate template =
            new RestDispatchTemplate(DispatchResult.CHANNEL_JIRA);

    @Override
    public String channelId() {
        return DispatchResult.CHANNEL_JIRA;
    }

    @Override
    public DispatchResult dispatch(final AnomalyEvent event) {
        return template.dispatch(event, this::isConfigured, this::executePost);
    }

    private boolean isConfigured() {
        return StringUtils.isNotBlank(this.properties.baseUrl())
                && StringUtils.isNotBlank(this.properties.email())
                && StringUtils.isNotBlank(this.properties.apiToken())
                && StringUtils.isNotBlank(this.properties.projectKey());
    }

    private void executePost(final AnomalyEvent event) {
        this.restClient.post()
                .uri(this.properties.baseUrl() + CREATE_ISSUE_PATH)
                .header(HttpHeaders.AUTHORIZATION, buildAuthHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .body(renderBody(event))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Builds the {@code Authorization: Basic <Base64(email:apiToken)>}
     * header used on every Jira REST API v3 call per ADR-0035 D2.
     * Package-private so the unit test asserts the header without
     * a live HTTP round-trip.
     *
     * @return header value beginning with {@code Basic } and the
     *         Base64-encoded {@code email:apiToken} payload
     */
    String buildAuthHeader() {
        final String creds = this.properties.email() + ":" + this.properties.apiToken();
        return "Basic " + Base64.getEncoder()
                .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Renders the Jira REST API v3 create-issue JSON body for one
     * anomaly event. Package-private so the unit test asserts the
     * shape without a live HTTP round-trip.
     *
     * @param event source anomaly event (non-null)
     * @return body map suitable for Jackson JSON encoding
     */
    Map<String, Object> renderBody(final AnomalyEvent event) {
        final Map<String, Object> project = new LinkedHashMap<>();
        project.put("key", this.properties.projectKey());

        final Map<String, Object> issueType = new LinkedHashMap<>();
        issueType.put("name", this.properties.issueType());

        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", project);
        fields.put("summary", renderSummary(event));
        fields.put("description", renderDescription(event));
        fields.put("issuetype", issueType);
        fields.put("labels", renderLabels(event));

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("fields", fields);
        return body;
    }

    private static String renderSummary(final AnomalyEvent event) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[')
                .append(StringUtils.defaultIfBlank(event.severity(), "UNKNOWN"))
                .append("] ")
                .append(StringUtils.defaultIfBlank(event.service(), "unknown-service"));
        final String reason = event.reason();
        if (StringUtils.isNotBlank(reason)) {
            sb.append(": ").append(reason);
        }
        final String summary = sb.toString();
        return summary.length() > SUMMARY_MAX_LEN
                ? summary.substring(0, SUMMARY_MAX_LEN)
                : summary;
    }

    private static Map<String, Object> renderDescription(final AnomalyEvent event) {
        final List<Map<String, Object>> paragraphs = new ArrayList<>();
        appendParagraph(paragraphs, "eventId: ", event.eventId());
        appendParagraph(paragraphs, "tenantId: ", event.tenantId());
        appendParagraph(paragraphs, "severity: ", event.severity());
        appendParagraph(paragraphs, "reason: ", event.reason());
        if (event.ts() != null) {
            appendParagraph(paragraphs, "ts: ", event.ts().toString());
        }
        appendParagraph(paragraphs, "level: ", event.level());
        appendParagraph(paragraphs, "service: ", event.service());
        appendParagraph(paragraphs, "message: ", event.message());

        final Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "doc");
        doc.put("version", 1);
        doc.put("content", paragraphs);
        return doc;
    }

    private static void appendParagraph(final List<Map<String, Object>> paragraphs,
                                        final String label,
                                        final String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        final Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", label + value);

        final Map<String, Object> paragraph = new LinkedHashMap<>();
        paragraph.put("type", "paragraph");
        paragraph.put("content", List.of(text));
        paragraphs.add(paragraph);
    }

    private List<String> renderLabels(final AnomalyEvent event) {
        final List<String> labels = new ArrayList<>();
        labels.add(STATIC_LABEL);
        if (StringUtils.isNotBlank(event.tenantId())) {
            labels.add(TENANT_LABEL_PREFIX + event.tenantId());
        }
        if (StringUtils.isNotBlank(event.severity())) {
            labels.add(this.properties.severityLabelPrefix()
                    + "-"
                    + event.severity().toLowerCase(Locale.ROOT));
        }
        return labels;
    }
}
