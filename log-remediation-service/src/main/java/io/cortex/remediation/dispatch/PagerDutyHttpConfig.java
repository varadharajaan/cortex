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
 * {@code pagerDutyRestClient} bean consumed by
 * {@link PagerDutyRemediationDispatcher} (P6.2 / ADR-0034 D3).
 *
 * <p>Active only when the dispatcher provider gate selects
 * PagerDuty
 * ({@code cortex.remediation.dispatcher.provider=pagerduty}).
 * Outbound HTTP is pinned to HTTP/1.1 via
 * {@link JdkClientHttpRequestFactory} per LD42 + the symmetric
 * P5.3 / P6.1 pattern. The configured
 * {@link PagerDutyProperties#requestTimeout()} is applied as BOTH
 * the underlying JDK HTTP client connect timeout AND the per-request
 * read timeout per LD121 (forward rule captured during P6.1) so a
 * slow PagerDuty Events endpoint still surfaces as ADR-0034 D3
 * {@code pagerduty:timeout} instead of blocking forever.</p>
 *
 * <p>{@link EnableConfigurationProperties} wires
 * {@link PagerDutyProperties} so the
 * {@code cortex.remediation.pagerduty.*} yml block binds at boot.</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "cortex.remediation.dispatcher",
        name = "provider",
        havingValue = "pagerduty")
@EnableConfigurationProperties(PagerDutyProperties.class)
public class PagerDutyHttpConfig {

    /**
     * Builds the HTTP/1.1-pinned {@link RestClient} the PagerDuty
     * adapter posts Events API v2 envelopes through.
     *
     * @param properties resolved PagerDuty properties (binds at boot
     *                   from {@code cortex.remediation.pagerduty.*})
     * @return configured RestClient; never null
     */
    @Bean
    public RestClient pagerDutyRestClient(final PagerDutyProperties properties) {
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(properties.requestTimeout())
                        .build());
        // connectTimeout above only covers TCP connect; setReadTimeout
        // pins the per-request HttpRequest.timeout(...) so a PagerDuty
        // endpoint that accepts but never responds still surfaces as
        // ADR-0034 D3 pagerduty:timeout instead of blocking forever
        // (LD121 forward rule from P6.1).
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().requestFactory(factory).build();
    }
}
