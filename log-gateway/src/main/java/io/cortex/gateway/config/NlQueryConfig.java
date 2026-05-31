package io.cortex.gateway.config;

import java.net.http.HttpClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the NL-to-LogQL feature beans (B20.1, P3.3 / ADR-0018).
 *
 * <p>The Spring AI 1.0.0 starter auto-registers a
 * {@link org.springframework.ai.chat.client.ChatClient.Builder
 * ChatClient.Builder} from whatever {@link org.springframework.ai.chat.model.ChatModel
 * ChatModel} is on the classpath; the service injects that builder
 * directly so this configuration only owns the explicit
 * {@link OllamaApi} override that pins the outbound HTTP client to
 * HTTP/1.1 (memory.md LD42).</p>
 *
 * <p>Per-principal NL sub-bucket enforcement that previously lived
 * here (a {@code nlQueryBucketConfiguration} {@link
 * io.github.bucket4j.BucketConfiguration BucketConfiguration} bean)
 * migrated to
 * {@link io.cortex.gateway.interceptor.RateLimitFeatureInterceptor}
 * in P3.4 / ADR-0021, driven by the
 * {@link io.cortex.gateway.annotation.RateLimitFeature
 * @RateLimitFeature} on the NL controller.</p>
 *
 * <p>The whole class is conditional on
 * {@code cortex.gateway.nl-query.enabled=true} so a deploy that wants the
 * gateway without the NL feature can disable it at the property layer.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "cortex.gateway.nl-query", name = "enabled", havingValue = "true")
public class NlQueryConfig {

    /**
     * Hand-builds the Spring AI {@link OllamaApi} bean so the outbound
     * Ollama call goes through a {@link RestClient} whose underlying JDK
     * {@link HttpClient} is pinned to HTTP/1.1.
     *
     * <p>Spring AI 1.0.0's
     * {@code OllamaApiAutoConfiguration#ollamaApi(...)} is gated on
     * {@link ConditionalOnMissingBean @ConditionalOnMissingBean}, so
     * declaring this bean fully replaces it. A
     * {@code RestClientCustomizer} cannot fix the h2c issue because the
     * auto-config resolves a fresh {@code RestClient.Builder} via
     * {@code ObjectProvider.getIfAvailable(RestClient::builder)} when no
     * builder bean is published; customizers are not applied to that
     * fallback. The only reliable override hook is the {@code OllamaApi}
     * bean itself.</p>
     *
     * <p>Why HTTP/1.1 matters: the JDK 17 {@link HttpClient} otherwise
     * negotiates an h2c upgrade on every outbound POST. WireMock 3.x
     * (HTTP/1.1-only on its default listener) cannot honour the upgrade
     * so it drops the chunked request body, the stub matcher never sees
     * the prompt, WireMock answers 404, and the gateway surfaces the
     * failure as {@code NL_QUERY_UPSTREAM_FAILED}. Production Ollama is
     * also HTTP/1.1, so pinning the version is safe for both
     * environments.</p>
     *
     * <p>Base URL is read directly from {@code spring.ai.ollama.base-url}
     * via {@link Value @Value} (defaulting to
     * {@code http://localhost:11434}, the same default the Spring AI
     * autoconfig uses). This intentionally avoids depending on the
     * {@code OllamaConnectionDetails} bean: the test classpath disables
     * the Ollama autoconfig via {@code spring.ai.model.chat=none}, so
     * that bean is absent in tests yet this override must still wire.</p>
     *
     * @param baseUrl resolved Ollama base URL
     * @return an {@link OllamaApi} backed by an HTTP/1.1-pinned
     *         {@link RestClient.Builder}
     */
    @Bean
    @ConditionalOnMissingBean
    public OllamaApi ollamaApi(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") final String baseUrl) {
        final RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .build()));
        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();
    }
}

