package io.cortex.monitoring.slo;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration for the P8.5 PromQL SLO backend.
 */
@Configuration
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'promql'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
@EnableConfigurationProperties(PromQlSloProperties.class)
public class PromQlSloHttpConfig {

    /**
     * Publishes the HTTP/1.1-pinned Prometheus API client consumed
     * by {@link PromQlSloBudgetEngine}.
     *
     * @param properties bound PromQL HTTP properties
     * @return named {@link RestClient}
     */
    @Bean
    public RestClient promQlSloRestClient(
            final PromQlSloProperties properties) {
        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(properties.requestTimeout())
                                .build());
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(factory)
                .build();
    }
}
