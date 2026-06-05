package io.cortex.remediation.dispatch;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration that publishes the {@code jiraRestClient}
 * bean consumed by {@link JiraRemediationDispatcher}
 * (P6.3 / ADR-0035 D3).
 *
 * <p>Active only when the dispatcher provider gate selects Jira
 * ({@code cortex.remediation.dispatcher.provider=jira}). Outbound
 * HTTP is pinned to HTTP/1.1 via {@link JdkClientHttpRequestFactory}
 * per LD42 + the symmetric P5.3 / P6.1 / P6.2 pattern. The configured
 * {@link JiraProperties#requestTimeout()} is applied as BOTH the
 * underlying JDK HTTP client connect timeout AND the per-request
 * read timeout per LD121 (forward rule captured during P6.1) so a
 * slow Jira Cloud REST endpoint still surfaces as ADR-0035 D3
 * {@code jira:timeout} instead of blocking forever.</p>
 *
 * <p>{@link EnableConfigurationProperties} wires
 * {@link JiraProperties} so the {@code cortex.remediation.jira.*}
 * yml block binds at boot.</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "jira")
@EnableConfigurationProperties(JiraProperties.class)
public class JiraHttpConfig {

    /**
     * Builds the HTTP/1.1-pinned {@link RestClient} the Jira
     * adapter posts REST API v3 create-issue envelopes through.
     *
     * @param properties resolved Jira properties (binds at boot from
     *                   {@code cortex.remediation.jira.*})
     * @return configured RestClient; never null
     */
    @Bean
    public RestClient jiraRestClient(final JiraProperties properties) {
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(properties.requestTimeout())
                        .build());
        // connectTimeout above only covers TCP connect; setReadTimeout
        // pins the per-request HttpRequest.timeout(...) so a Jira
        // endpoint that accepts but never responds still surfaces as
        // ADR-0035 D3 jira:timeout instead of blocking forever
        // (LD121 forward rule from P6.1).
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }
}
