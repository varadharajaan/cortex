package io.cortex.gateway.config;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the downstream {@code getLogById} HTTP client (P9.2b /
 * ADR-0049).
 *
 * <p>Provides a plain, timeout-bounded {@link RestClient} (bean name
 * {@code ingestRestClient}) used by
 * {@link io.cortex.gateway.service.impl.GetLogByIdServiceImpl} with an
 * absolute URI resolved through the blocking
 * {@link org.springframework.cloud.client.loadbalancer.LoadBalancerClient}.
 * The client is deliberately NOT a {@code @LoadBalanced
 * RestClient.Builder} (which would be picked up by Spring AI's builder
 * lookup and load-balance the Ollama calls -- ADR-0049 Amendment 3).
 * It is a distinct bean from P9.1b's {@code indexerRestClient} so the two
 * read features stay independently gated; by-name injection
 * disambiguates the two {@link RestClient} beans when both are active.</p>
 *
 * <p>HTTP/1.1 is pinned via {@link JdkClientHttpRequestFactory} (LD42)
 * and a dual connect + read timeout is applied from
 * {@link GetLogByIdProperties#requestTimeout()} (LD121).</p>
 *
 * <p>Gated on {@code cortex.gateway.get-log-by-id.enabled=true}.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "cortex.gateway.get-log-by-id", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GetLogByIdProperties.class)
public class GetLogByIdClientConfig {

    /**
     * Builds the timeout-bounded HTTP/1.1 {@link RestClient} for the
     * ingest get-by-id call.
     *
     * @param properties typed configuration (supplies the timeout)
     * @return the configured {@link RestClient}
     */
    @Bean
    public RestClient ingestRestClient(final GetLogByIdProperties properties) {
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.requestTimeout())
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
