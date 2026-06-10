package io.cortex.monitoring.slo;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration for the P8.4 timer-percentile SLO
 * backend.
 */
@Configuration
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'timer-percentile'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
@EnableConfigurationProperties(TimerPercentileSloProperties.class)
public class TimerPercentileSloHttpConfig {

    /**
     * Publishes the HTTP/1.1-pinned scrape client consumed by
     * {@link TimerPercentileSloBudgetEngine}.
     *
     * @param properties bound timer-percentile scrape properties
     * @return named {@link RestClient}
     */
    @Bean
    public RestClient timerPercentileSloRestClient(
            final TimerPercentileSloProperties properties) {
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
