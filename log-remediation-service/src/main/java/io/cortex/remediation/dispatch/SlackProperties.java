package io.cortex.remediation.dispatch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the {@link SlackRemediationDispatcher}
 * (P6.1 / ADR-0033).
 *
 * <p>Bound to prefix {@code cortex.remediation.slack}. The
 * {@code webhook-url} is the per-channel Slack Incoming Webhook
 * URL minted out of the Slack app config; rotating it MUST be done
 * via env-var redeploy (no hot-reload by design -- a leaked webhook
 * URL is a privilege-escalation vector that warrants a restart per
 * Part 9.4). {@code request-timeout} controls both connect + read
 * timeouts on the underlying JDK HTTP client (LD42 HTTP/1.1 pin).</p>
 *
 * @param webhookUrl     Slack Incoming-Webhook full URL
 *                       ({@code https://hooks.slack.com/services/T.../B.../X...});
 *                       blank URL forces the dispatcher into the
 *                       "skipped (unconfigured)" path so a profile
 *                       that selects the Slack provider without
 *                       supplying the secret still boots green
 * @param requestTimeout per-call advisory timeout for connect + read
 *                       (default 5 s); enforced by the JDK HTTP
 *                       client; on expiry the adapter returns
 *                       {@link DispatchResult#transientFailure(String, String)}
 *                       with {@code reason=slack:timeout}
 * @param username       optional override for the Slack message
 *                       {@code username} field (the avatar+name
 *                       shown to operators); blank = use the
 *                       webhook's default app identity
 * @param channelOverride optional override for the Slack message
 *                       {@code channel} field; webhook-bound
 *                       channels ignore this but Block-Kit-style
 *                       legacy hooks honour it; blank = use the
 *                       webhook's bound channel
 */
@ConfigurationProperties(prefix = "cortex.remediation.slack")
public record SlackProperties(String webhookUrl, Duration requestTimeout,
                              String username, String channelOverride) {

    /** Default per-call advisory request timeout (LD42 HTTP/1.1 pin). */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default Slack message username (blank = use webhook app identity). */
    public static final String DEFAULT_USERNAME = "";

    /** Default Slack channel override (blank = use webhook-bound channel). */
    public static final String DEFAULT_CHANNEL_OVERRIDE = "";

    /**
     * Defensive defaults so a partially-filled yml block still wires.
     *
     * @param webhookUrl      see record-level Javadoc; null/blank
     *                        coerces to empty string so the
     *                        dispatcher's unconfigured guard fires
     *                        deterministically
     * @param requestTimeout  see record-level Javadoc; null coerces
     *                        to {@link #DEFAULT_REQUEST_TIMEOUT}
     * @param username        see record-level Javadoc; null coerces
     *                        to empty string
     * @param channelOverride see record-level Javadoc; null coerces
     *                        to empty string
     */
    public SlackProperties {
        if (webhookUrl == null) {
            webhookUrl = "";
        }
        if (requestTimeout == null) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        if (username == null) {
            username = DEFAULT_USERNAME;
        }
        if (channelOverride == null) {
            channelOverride = DEFAULT_CHANNEL_OVERRIDE;
        }
    }
}
