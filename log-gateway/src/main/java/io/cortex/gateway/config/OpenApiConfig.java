package io.cortex.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the springdoc-openapi descriptor served at {@code /v3/api-docs}
 * (rule A12.8) and registers the {@code bearerAuth} JWT security scheme so
 * every protected endpoint can reference it via {@code @SecurityRequirement}
 * (rule B7.4).
 */
@Configuration
@RequiredArgsConstructor
public class OpenApiConfig {

    /** Security scheme name referenced by {@code @SecurityRequirement}. */
    public static final String BEARER_SCHEME_NAME = "bearerAuth";

    /** Typed gateway settings used to label the OpenAPI document. */
    private final GatewayProperties properties;

    /**
     * Builds the OpenAPI 3 document bean.
     *
     * @return an OpenAPI document with service identity, license, and bearer scheme
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
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access JWT obtained from POST /api/v1/auth/login.")));
    }
}
