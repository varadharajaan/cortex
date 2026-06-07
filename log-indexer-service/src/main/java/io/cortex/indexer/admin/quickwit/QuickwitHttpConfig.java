package io.cortex.indexer.admin.quickwit;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration class for the Quickwit HTTP admin adapter
 * (P7.1 / ADR-0039).
 *
 * <p>Activated only when {@code cortex.indexer.admin.backend=quickwit}
 * so the default-dev profile (which leaves the property unset, the
 * noop {@code matchIfMissing=true} variant wins) does not allocate
 * a JDK {@link HttpClient} or a {@link RestClient} pool.</p>
 *
 * <p>The published {@link RestClient} is pinned to HTTP/1.1 via
 * {@link JdkClientHttpRequestFactory} (LD42) so the wire-format
 * matches what the P5.3 {@code QuickwitSink} writer already sends
 * to the same Quickwit cluster. The {@code connect} and
 * {@code read} legs share the
 * {@link QuickwitProperties#requestTimeout()} value (LD121
 * dual-timeout). {@link RestClient.Builder#baseUrl(String)} is set
 * to {@link QuickwitProperties#baseUrl()} so call sites can use
 * path-only URIs ({@code /api/v1/indexes/&lt;id&gt;}).</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "cortex.indexer.admin",
        name = "backend",
        havingValue = "quickwit")
@EnableConfigurationProperties(QuickwitProperties.class)
public class QuickwitHttpConfig {

    /**
     * Publishes the HTTP/1.1-pinned {@link RestClient} consumed by
     * {@link QuickwitHttpAdmin}. The bean is created lazily by
     * Spring's standard bean lifecycle; the field references in
     * {@link QuickwitHttpAdmin} are constructor-injected so the
     * adapter is fully built before {@link IndexerMetrics
     * io.cortex.indexer.metrics.IndexerMetrics}'s OCP bootstrap loop
     * iterates over the {@code List&lt;QuickwitIndexAdmin&gt;}.
     *
     * @param properties bound configuration block
     * @return the production {@link RestClient} for Quickwit admin
     */
    @Bean
    public RestClient quickwitAdminRestClient(final QuickwitProperties properties) {
        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(properties.requestTimeout())
                                .build());
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
