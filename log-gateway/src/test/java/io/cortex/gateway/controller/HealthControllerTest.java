package io.cortex.gateway.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cortex.gateway.config.GatewayProperties;
import io.cortex.gateway.config.SecurityConfig;
import io.cortex.gateway.constants.ApiPaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Slice test for {@link HealthController}; exercises the JSON contract
 * served at {@code GET /api/v1/health} including the public access path
 * enforced by {@link SecurityConfig}.
 */
@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, HealthControllerTest.PropertiesConfig.class})
class HealthControllerTest {

    /** MockMvc auto-configured by {@code @WebMvcTest}. */
    @Autowired private MockMvc mockMvc;

    /**
     * Asserts the public health endpoint returns 200 with the expected
     * JSON shape.
     *
     * @throws Exception when MockMvc dispatch fails
     */
    @Test
    void returnsUpAndServiceLabelsAsPublicEndpoint() throws Exception {
        this.mockMvc.perform(get(ApiPaths.HEALTH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("log-gateway"))
                .andExpect(jsonPath("$.environment").value("test"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    /**
     * Supplies a fixed {@link GatewayProperties} bean to the slice test
     * since {@code @ConfigurationPropertiesScan} is not active under
     * {@code @WebMvcTest}.
     */
    @TestConfiguration
    static class PropertiesConfig {
        /**
         * Builds a deterministic gateway properties bean for the slice.
         *
         * @return a fixed-value gateway properties bean
         */
        @Bean
        GatewayProperties gatewayProperties() {
            return new GatewayProperties("log-gateway", "test");
        }
    }
}
