package io.cortex.monitoring.slo;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration for the P8.7+ OTel SLO backend.
 */
@Configuration
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'otel'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
@EnableConfigurationProperties(OtelSloProperties.class)
public class OtelSloHttpConfig {

    /**
     * Publishes the HTTP/1.1-pinned scrape client consumed by
     * {@link OtelSloBudgetEngine}.
     *
     * @param properties bound OTel scrape properties
     * @return named {@link RestClient}
     */
    @Bean
    public RestClient otelSloRestClient(final OtelSloProperties properties) {
        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(properties.requestTimeout())
                                .build());
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
