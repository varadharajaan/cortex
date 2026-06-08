package io.cortex.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Unit tests for {@link GatewayRoutesConfig} (P3.0b + P3.4 / ADR-0014).
 *
 * <p>Asserts each route bean returns a non-null router function. The
 * end-to-end {@code lb://log-echo-service} resolution is covered by the
 * smoke harness and Postman because it requires a live Eureka.</p>
 *
 * <p>P9.1b retired the {@code searchServiceRoute} echo placeholder
 * ({@code /api/v1/search/**}) now that the real {@code searchLogs}
 * surface is gateway-owned (ADR-0049 Amendment 3), so only the echo and
 * logs proxy routes remain.</p>
 */
class GatewayRoutesConfigTest {

    /** Both remaining route beans publish a {@link RouterFunction}. */
    @Test
    void allRouteBeansAreNonNull() {
        final GatewayRoutesConfig config = new GatewayRoutesConfig();
        final RouterFunction<ServerResponse> echo = config.echoServiceRoute();
        final RouterFunction<ServerResponse> logs = config.logsServiceRoute();
        assertThat(echo).isNotNull();
        assertThat(logs).isNotNull();
    }

    /** Each route bean is a distinct instance (no accidental aliasing). */
    @Test
    void routeBeansAreDistinctInstances() {
        final GatewayRoutesConfig config = new GatewayRoutesConfig();
        assertThat(config.echoServiceRoute()).isNotSameAs(config.logsServiceRoute());
    }
}
