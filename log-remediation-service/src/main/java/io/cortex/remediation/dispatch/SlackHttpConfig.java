package io.cortex.remediation.dispatch;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration that publishes the
 * {@code slackRestClient} bean consumed by
 * {@link SlackRemediationDispatcher} (P6.1 / ADR-0033 D3).
 *
 * <p>Active only when the dispatcher provider gate selects Slack
 * ({@code cortex.remediation.dispatcher.provider=slack}). Outbound
 * HTTP is pinned to HTTP/1.1 via {@link JdkClientHttpRequestFactory}
 * per LD42 + the symmetric P5.3 {@code LokiSink} /
 * {@code QuickwitSink} pattern. The configured
 * {@link SlackProperties#requestTimeout()} is applied as BOTH the
 * underlying JDK HTTP client connect timeout AND the per-request
 * read timeout so a slow Slack webhook still surfaces as ADR-0033
 * D3 {@code slack:timeout} instead of blocking forever.</p>
 *
 * <p>{@link EnableConfigurationProperties} wires
 * {@link SlackProperties} so the {@code cortex.remediation.slack.*}
 * yml block binds at boot.</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "slack")
@EnableConfigurationProperties(SlackProperties.class)
public class SlackHttpConfig {

    /**
     * Builds the HTTP/1.1-pinned {@link RestClient} the Slack
     * adapter posts webhook payloads through.
     *
     * @param properties resolved Slack properties (binds at boot
     *                   from {@code cortex.remediation.slack.*})
     * @return configured RestClient; never null
     */
    @Bean
    public RestClient slackRestClient(final SlackProperties properties) {
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(properties.requestTimeout())
                        .build());
        // connectTimeout above only covers TCP connect; setReadTimeout
        // pins the per-request HttpRequest.timeout(...) so a Slack
        // webhook that accepts but never responds still surfaces as
        // ADR-0033 D3 slack:timeout instead of blocking forever.
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }
}
