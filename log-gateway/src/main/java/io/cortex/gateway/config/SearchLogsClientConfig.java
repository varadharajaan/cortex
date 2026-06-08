package io.cortex.gateway.config;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the downstream {@code searchLogs} HTTP client (P9.1b /
 * ADR-0049).
 *
 * <p>Provides a plain, timeout-bounded {@link RestClient} used by
 * {@link io.cortex.gateway.service.impl.SearchLogsServiceImpl} with an
 * absolute URI resolved through the blocking
 * {@link org.springframework.cloud.client.loadbalancer.LoadBalancerClient}.
 * The client is deliberately NOT a {@code @LoadBalanced
 * RestClient.Builder}: declaring such a builder bean would be picked up
 * by Spring AI's
 * {@code ObjectProvider.getIfAvailable(RestClient::builder)} lookup and
 * inadvertently load-balance the Ollama calls (ADR-0049 Amendment 3).</p>
 *
 * <p>HTTP/1.1 is pinned via {@link JdkClientHttpRequestFactory} (LD42 --
 * WireMock 3.x in the IT speaks HTTP/1.1 only) and a dual connect + read
 * timeout is applied from {@link SearchLogsProperties#requestTimeout()}
 * (LD121) so a slow indexer surfaces as a transient upstream failure
 * rather than blocking the request thread.</p>
 *
 * <p>Gated on {@code cortex.gateway.search-logs.enabled=true} so the
 * client is absent when the feature is disabled.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "cortex.gateway.search-logs", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SearchLogsProperties.class)
public class SearchLogsClientConfig {

    /**
     * Builds the timeout-bounded HTTP/1.1 {@link RestClient} for the
     * indexer search call.
     *
     * @param properties typed search configuration (supplies the timeout)
     * @return the configured {@link RestClient}
     */
    @Bean
    public RestClient indexerRestClient(final SearchLogsProperties properties) {
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
