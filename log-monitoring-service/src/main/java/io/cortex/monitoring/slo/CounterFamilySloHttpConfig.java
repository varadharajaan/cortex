package io.cortex.monitoring.slo;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client configuration for the P8.3 counter-family SLO
 * backend.
 *
 * <p>Activated only when
 * {@code cortex.monitoring.slo.backend=counter-family}. The
 * published client is HTTP/1.1-pinned with the same dual
 * connect+read timeout posture used by the Eureka health probe,
 * but it is a separate named bean so a profile can safely enable
 * {@code probe.backend=eureka-actuator} and
 * {@code slo.backend=counter-family} at the same time.</p>
 */
@Configuration
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'counter-family'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
@EnableConfigurationProperties(CounterFamilySloProperties.class)
public class CounterFamilySloHttpConfig {

    /**
     * Publishes the HTTP/1.1-pinned scrape client consumed by
     * {@link CounterFamilySloBudgetEngine}.
     *
     * @param properties bound counter-family scrape properties
     * @return named {@link RestClient} for remote Prometheus
     *         exposition scrapes
     */
    @Bean
    public RestClient counterFamilySloRestClient(
            final CounterFamilySloProperties properties) {
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
