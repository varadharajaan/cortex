package io.cortex.ingest.config;

import io.cortex.ingest.security.ServiceJwtProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Top-level configuration for log-ingest-service.
 *
 * <p>Enables typed configuration property binding and exposes a
 * customised {@link OpenAPI} bean so SpringDoc renders module
 * metadata on {@code /v3/api-docs} and {@code /swagger-ui.html}.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceJwtProperties.class)
public class IngestConfig {

    /** Module artifactId for the OpenAPI {@code info.title}. */
    private static final String API_TITLE = "CORTEX log-ingest-service API";

    /** SemVer for the OpenAPI {@code info.version} (P4.0 placeholder). */
    private static final String API_VERSION = "0.1.0";

    /** Default constructor used by Spring. */
    public IngestConfig() {
        // no state; Spring instantiates via reflection
    }

    /**
     * Customised OpenAPI document published at {@code /v3/api-docs}.
     *
     * @return OpenAPI document carrying module title, version, and
     *         license metadata
     */
    @Bean
    public OpenAPI cortexIngestOpenApi() {
        return new OpenAPI().info(new Info()
                .title(API_TITLE)
                .version(API_VERSION)
                .description("Validate, dedupe, enrich, queue, and persist log batches.")
                .license(new License().name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
