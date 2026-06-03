package io.cortex.processor.config;

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
 * Pins the outbound Ollama HTTP client to HTTP/1.1 for the
 * processor's Spring AI 1.0 anomaly classifier (P5.2 / ADR-0029 D3).
 *
 * <p>This is the processor-side mirror of the gateway's
 * {@code NlQueryConfig} (P3.3 / ADR-0018). Spring AI's
 * {@code OllamaApiAutoConfiguration#ollamaApi(...)} is gated on
 * {@link ConditionalOnMissingBean @ConditionalOnMissingBean}, so
 * declaring this bean fully replaces the auto-configured one. A
 * {@code RestClientCustomizer} does NOT work because the autoconfig
 * resolves a fresh {@code RestClient.Builder} via
 * {@code ObjectProvider.getIfAvailable(RestClient::builder)} when no
 * builder bean is published; customizers are not applied to that
 * fallback. The only reliable override hook is the {@code OllamaApi}
 * bean itself.</p>
 *
 * <p>Why HTTP/1.1 matters: the JDK 17 {@link HttpClient} otherwise
 * negotiates an h2c upgrade on every outbound POST. WireMock 3.x
 * (HTTP/1.1-only on its default listener) cannot honour the upgrade
 * so it drops the chunked request body, the stub matcher never sees
 * the prompt, WireMock answers 404, and the smoke surfaces the
 * failure as a classifier error. Production Ollama is also HTTP/1.1,
 * so pinning the version is safe for both environments. See
 * memory.md LD42 for the original root-cause analysis from P3.3.</p>
 *
 * <p>Gated by {@code cortex.processor.classifier=spring-ai} so the
 * default no-op classifier profile does not pay the bean-creation
 * cost and does not load the Spring AI Ollama autoconfig.</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "cortex.processor.classifier",
        name = "provider",
        havingValue = "spring-ai"
)
public class OllamaHttpConfig {

    /**
     * Hand-builds the Spring AI {@link OllamaApi} bean so the
     * outbound Ollama call goes through a {@link RestClient} whose
     * underlying JDK {@link HttpClient} is pinned to HTTP/1.1.
     *
     * <p>Base URL is read directly from {@code spring.ai.ollama.base-url}
     * via {@link Value @Value} (defaulting to
     * {@code http://localhost:11434}, the Spring AI autoconfig
     * default). This intentionally avoids depending on the
     * {@code OllamaConnectionDetails} bean: the test classpath
     * disables the Ollama autoconfig via
     * {@code spring.ai.model.chat=none}, so that bean is absent in
     * tests yet this override must still wire when the
     * spring-ai profile is selected outside of tests.</p>
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
