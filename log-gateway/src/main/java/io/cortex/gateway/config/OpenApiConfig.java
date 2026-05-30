package io.cortex.gateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the springdoc-openapi descriptor served at {@code /v3/api-docs}
 * (rule A12.8).
 */
@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    /** Typed gateway settings used to label the OpenAPI document. */
    private final GatewayProperties properties;

    /**
     * Builds the OpenAPI 3 document bean.
     *
     * @return an OpenAPI document with service identity and license
     */
    @Bean
    public OpenAPI cortexGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CORTEX log-gateway")
                        .version("0.1.0")
                        .description(
                                "Edge gateway for CORTEX: auth, correlation, "
                                + "rate limit, NL-LogQL routing. "
                                + "Environment: " + this.properties.environment() + ".")
                        .license(new License()
                                .name("Apache-2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
