package io.cortex.remediation.dispatch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the {@link JiraRemediationDispatcher}
 * (P6.3 / ADR-0035).
 *
 * <p>Bound to prefix {@code cortex.remediation.jira}. Authentication
 * uses Jira Cloud's Basic-auth-with-API-token scheme: the adapter
 * builds an {@code Authorization: Basic <Base64(email:apiToken)>}
 * header per request. The {@code apiToken} is minted at
 * <a href="https://id.atlassian.com/manage-profile/security/api-tokens">id.atlassian.com</a>;
 * rotating it MUST be done via env-var redeploy (no hot-reload by
 * design -- a leaked API token is a privilege-escalation vector
 * that warrants a restart per Part 9.4). {@code requestTimeout}
 * controls BOTH connect + read timeouts on the underlying JDK HTTP
 * client (LD42 HTTP/1.1 pin + LD121 forward rule from P6.1).</p>
 *
 * @param baseUrl              Jira Cloud site base URL
 *                             ({@code https://<your-domain>.atlassian.net});
 *                             blank value forces the dispatcher into
 *                             the "skipped (unconfigured)" path so a
 *                             profile that selects the Jira provider
 *                             without supplying the secret still
 *                             boots green
 * @param email                Atlassian account email paired with
 *                             the API token for Basic auth; blank
 *                             value forces unconfigured skip
 * @param apiToken             Atlassian API token (NOT the account
 *                             password); blank value forces
 *                             unconfigured skip
 * @param requestTimeout       per-call advisory timeout for connect
 *                             + read (default 5 s); enforced by the
 *                             JDK HTTP client; on expiry the adapter
 *                             returns {@link
 *                             DispatchResult#transientFailure(String, String)}
 *                             with {@code reason=jira:timeout}
 * @param projectKey           target Jira project key
 *                             ({@code OPS}, {@code SRE}, ...);
 *                             blank value forces unconfigured skip
 * @param issueType            Jira issue type name to create
 *                             (default {@value #DEFAULT_ISSUE_TYPE});
 *                             MUST exist in the target project's
 *                             issue-type scheme
 * @param severityLabelPrefix  prefix prepended to the lowercased
 *                             severity to build a Jira label; e.g.
 *                             {@code anomaly-severity-high} when
 *                             severity is {@code HIGH}; default
 *                             {@value #DEFAULT_SEVERITY_LABEL_PREFIX}
 */
@Validated
@ConfigurationProperties(prefix = "cortex.remediation.jira")
public record JiraProperties(String baseUrl, String email, String apiToken,
                             Duration requestTimeout, String projectKey,
                             String issueType, String severityLabelPrefix) {

    /** Default per-call advisory request timeout (LD42 HTTP/1.1 pin + LD121). */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default Jira issue type name (MUST exist in target project's issue-type scheme). */
    public static final String DEFAULT_ISSUE_TYPE = "Bug";

    /** Default severity-label prefix joined to lowercased severity via {@code -}. */
    public static final String DEFAULT_SEVERITY_LABEL_PREFIX = "anomaly-severity";

    /**
     * Defensive defaults so a partially-filled yml block still wires.
     *
     * @param baseUrl             see record-level Javadoc; null/blank
     *                            coerces to empty string so the
     *                            dispatcher's unconfigured guard
     *                            fires deterministically
     * @param email               see record-level Javadoc; null/blank
     *                            coerces to empty string
     * @param apiToken            see record-level Javadoc; null/blank
     *                            coerces to empty string
     * @param requestTimeout      see record-level Javadoc; null
     *                            coerces to {@link #DEFAULT_REQUEST_TIMEOUT}
     * @param projectKey          see record-level Javadoc; null/blank
     *                            coerces to empty string
     * @param issueType           see record-level Javadoc; null/blank
     *                            coerces to {@link #DEFAULT_ISSUE_TYPE}
     * @param severityLabelPrefix see record-level Javadoc; null/blank
     *                            coerces to {@link #DEFAULT_SEVERITY_LABEL_PREFIX}
     */
    public JiraProperties {
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (email == null) {
            email = "";
        }
        if (apiToken == null) {
            apiToken = "";
        }
        if (requestTimeout == null) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        if (projectKey == null) {
            projectKey = "";
        }
        if (issueType == null || issueType.isBlank()) {
            issueType = DEFAULT_ISSUE_TYPE;
        }
        if (severityLabelPrefix == null || severityLabelPrefix.isBlank()) {
            severityLabelPrefix = DEFAULT_SEVERITY_LABEL_PREFIX;
        }
    }
}
