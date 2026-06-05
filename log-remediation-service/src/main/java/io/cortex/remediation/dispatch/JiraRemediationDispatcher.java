package io.cortex.remediation.dispatch;

import io.cortex.remediation.parse.AnomalyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link RemediationDispatcher} adapter for the Jira Cloud REST
 * API v3 create-issue endpoint (P6.3 / ADR-0032 D4 + ADR-0035).
 *
 * <p>Builds the Jira REST API v3 create-issue JSON envelope
 * ({@code fields.project.key} + {@code fields.summary} +
 * {@code fields.description} (ADF doc) + {@code fields.issuetype.name} +
 * {@code fields.labels}) from the typed {@link AnomalyEvent} and
 * POSTs it to {@code {baseUrl}/rest/api/3/issue} with an
 * {@code Authorization: Basic <Base64(email:apiToken)>} header per
 * the Jira Cloud Basic-auth-with-API-token scheme (ADR-0035 D2).
 * Jira returns {@code 201 Created} with a JSON body containing the
 * new issue {@code id} / {@code key} / {@code self} on accepted
 * creates; the adapter maps the HTTP outcome onto a typed
 * {@link DispatchResult} per the table below.</p>
 *
 * <table>
 *   <caption>Jira HTTP outcome &rarr; DispatchResult mapping (ADR-0035 D3)</caption>
 *   <tr><th>HTTP outcome</th><th>DispatchResult.outcome</th><th>reason</th></tr>
 *   <tr><td>2xx (201 Created)</td><td>{@code dispatched}</td><td>{@code ""}</td></tr>
 *   <tr><td>429</td><td>{@code transient_failure}</td><td>{@code jira:429}</td></tr>
 *   <tr><td>5xx</td><td>{@code transient_failure}</td><td>{@code jira:5xx:<n>}</td></tr>
 *   <tr><td>4xx (other)</td><td>{@code permanent_failure}</td><td>{@code jira:4xx:<n>}</td></tr>
 *   <tr>
 *     <td>Timeout / IO</td>
 *     <td>{@code transient_failure}</td>
 *     <td>{@code jira:timeout} / {@code jira:transport}</td>
 *   </tr>
 *   <tr>
 *     <td>blank baseUrl/email/apiToken/projectKey</td>
 *     <td>{@code skipped}</td>
 *     <td>{@code jira:unconfigured}</td>
 *   </tr>
 *   <tr><td>null event</td><td>{@code skipped}</td><td>{@code jira:null-event}</td></tr>
 * </table>
 *
 * <p>Per ADR-0032 D6 + D7 + LD117 the adapter NEVER throws on a
 * transient downstream failure. Returning a typed verdict (vs
 * throwing) keeps the offset moving, ticks the failed-outcome
 * counter for operator alerting, and preserves the future P6.4 DLQ
 * + retry-budget axis without breaking the SPI contract.</p>
 *
 * <p>Gated by {@code cortex.remediation.dispatcher.provider=jira}
 * (no {@code matchIfMissing} -- the default stays
 * {@link NoopRemediationDispatcher}). Outbound HTTP is pinned to
 * HTTP/1.1 via {@link JiraHttpConfig} (LD42 + LD121, symmetric with
 * P5.3 sinks and the P6.1 Slack + P6.2 PagerDuty adapters).</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "jira")
public class JiraRemediationDispatcher implements RemediationDispatcher {

    private static final Logger LOG =
            LoggerFactory.getLogger(JiraRemediationDispatcher.class);

    /** Jira rate-limit HTTP status (treated as transient per Atlassian docs). */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

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

    /**
     * Spring constructor.
     *
     * @param properties resolved Jira properties (binds at boot
     *                   from {@code cortex.remediation.jira.*})
     * @param restClient HTTP/1.1-pinned {@link RestClient} provided
     *                   by {@link JiraHttpConfig}; injected by bean
     *                   name so the no-op profile (where this
     *                   adapter is not loaded) is unaffected
     */
    public JiraRemediationDispatcher(final JiraProperties properties,
                                     final RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override                            
    public DispatchResult dispatch(final AnomalyEvent event) {
        if (event == null) {
            return DispatchResult.skipped("jira:null-event");
        }
        if (StringUtils.isBlank(this.properties.baseUrl())
                || StringUtils.isBlank(this.properties.email())
                || StringUtils.isBlank(this.properties.apiToken())
                || StringUtils.isBlank(this.properties.projectKey())) {
            // Per ADR-0035 D1: an unconfigured Jira profile boots
            // green; operator sees the skipped-outcome counter climb
            // instead of a CrashLoopBackOff. Real prod sets the four
            // env vars; the boot is intentionally tolerant.
            return DispatchResult.skipped("jira:unconfigured");
        }
        final Map<String, Object> body = renderBody(event);
        try {
            this.restClient.post()
                    .uri(this.properties.baseUrl() + CREATE_ISSUE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, buildAuthHeader())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            LOG.info("jira dispatched eventId={} tenantId={} severity={}",
                    event.eventId(), event.tenantId(), event.severity());
            return DispatchResult.dispatched(DispatchResult.CHANNEL_JIRA);
        } catch (final RestClientResponseException ex) {
            return classifyHttp(event, ex);
        } catch (final ResourceAccessException ex) {
            return classifyTransport(event, ex);
        } catch (final RuntimeException ex) {
            // Catch-all per ADR-0032 D6 -- adapter MUST NOT propagate
            // transient failures into the consumer; the catch-all log
            // gives the operator visibility while the counter ticks.
            LOG.warn("jira unexpected failure eventId={} tenantId={}: {}",
                    event.eventId(), event.tenantId(), ex.getMessage());
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_JIRA, "jira:unknown");
        }
    }

    /**
     * Builds the {@code Authorization: Basic <Base64(email:apiToken)>}
     * header value used on every Jira REST API v3 call per
     * ADR-0035 D2.
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
     * anomaly event.
     *
     * <p>Shape (ADR-0035 D2):</p>
     * <pre>
     * { "fields": {
     *     "project":   { "key": "&lt;projectKey&gt;" },
     *     "summary":   "[HIGH] checkout: checkout 5xx burst",
     *     "description": { ADF doc },
     *     "issuetype": { "name": "Bug" },
     *     "labels":    [ "cortex-remediation",
     *                    "tenant:tenant-abc",
     *                    "anomaly-severity-high" ]
     * }}
     * </pre>
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

    /**
     * Renders the human-readable Jira {@code fields.summary} for a
     * single anomaly; truncated to {@value #SUMMARY_MAX_LEN}
     * characters to honour Atlassian's hard summary limit.
     *
     * @param event source anomaly event (non-null)
     * @return human-readable summary string
     */
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
        if (summary.length() > SUMMARY_MAX_LEN) {
            return summary.substring(0, SUMMARY_MAX_LEN);
        }
        return summary;
    }

    /**
     * Renders the Atlassian Document Format (ADF) doc for
     * {@code fields.description}: one paragraph node per non-blank
     * {@link AnomalyEvent} field so Jira's UI renders each detail
     * on its own line (a single paragraph with embedded {@code \n}
     * would be flattened by Jira's renderer).
     *
     * @param event source anomaly event (non-null)
     * @return ADF document map (type=doc, version=1, content=[paragraphs])
     */
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

    /**
     * Appends one ADF {@code paragraph} node carrying a single
     * {@code text} node whose value is {@code label + value} -- but
     * only when {@code value} is non-blank, so the rendered card
     * stays compact.
     *
     * @param paragraphs accumulating list of ADF paragraph nodes
     * @param label      human-readable field label (e.g. {@code "eventId: "})
     * @param value      field value (may be null/blank; if so the
     *                   paragraph is suppressed)
     */
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

    /**
     * Renders the Jira {@code fields.labels} list. Always emits the
     * static {@value #STATIC_LABEL} label; conditionally appends
     * {@code tenant:<tenantId>} (when non-blank) and
     * {@code <severityLabelPrefix>-<severity-lowercased>} (when
     * non-blank). Jira labels MUST NOT contain spaces; the
     * severity value is lowercased via {@link Locale#ROOT}.
     *
     * @param event source anomaly event (non-null)
     * @return list of Jira labels for the create-issue body
     */
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

    /**
     * Maps a Jira HTTP non-2xx response onto the right
     * {@link DispatchResult} outcome.
     *
     * @param event the event under dispatch (used for log context)
     * @param ex    caught {@link RestClientResponseException}
     * @return typed verdict per the ADR-0035 D3 outcome table
     */
    private static DispatchResult classifyHttp(final AnomalyEvent event,
                                               final RestClientResponseException ex) {
        final HttpStatusCode status = ex.getStatusCode();
        final int code = status.value();
        LOG.warn("jira non-2xx eventId={} tenantId={} status={}: {}",
                event.eventId(), event.tenantId(), code, ex.getMessage());
        if (code == HTTP_TOO_MANY_REQUESTS) {
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_JIRA, "jira:429");
        }
        if (status.is5xxServerError()) {
            return DispatchResult.transientFailure(
                    DispatchResult.CHANNEL_JIRA, "jira:5xx:" + code);
        }
        // 4xx (other than 429) is non-retriable: invalid API token,
        // unknown project key, malformed issue body -- redeploy the
        // env vars or fix the body shape, don't retry.
        return DispatchResult.permanentFailure(
                DispatchResult.CHANNEL_JIRA, "jira:4xx:" + code);
    }

    /**
     * Maps a Jira HTTP transport-layer failure onto the right
     * {@link DispatchResult} outcome.
     *
     * @param event the event under dispatch (used for log context)
     * @param ex    caught {@link ResourceAccessException} (timeout / IO)
     * @return typed verdict per the ADR-0035 D3 outcome table
     */
    private static DispatchResult classifyTransport(final AnomalyEvent event,
                                                    final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        final boolean timeout = cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException;
        LOG.warn("jira transport failure eventId={} tenantId={} timeout={}: {}",
                event.eventId(), event.tenantId(), timeout, ex.getMessage());
        return DispatchResult.transientFailure(
                DispatchResult.CHANNEL_JIRA,
                timeout ? "jira:timeout" : "jira:transport");
    }
}
