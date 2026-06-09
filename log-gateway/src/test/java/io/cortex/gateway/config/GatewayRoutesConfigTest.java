package io.cortex.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Unit tests for {@link GatewayRoutesConfig} (P3.0b + P3.4 / ADR-0014).
 *
 * <p>Asserts each route bean returns a non-null router function, and
 * (P9.1c) that the logs proxy predicate matches only ingest WRITES so
 * gateway-owned reads under the same prefix fall through to their
 * annotated controllers. The end-to-end {@code lb://} resolution is
 * covered by the smoke harness because it requires a live Eureka.</p>
 *
 * <p>P9.1b retired the {@code searchServiceRoute} echo placeholder
 * ({@code /api/v1/search/**}) now that the real {@code searchLogs}
 * surface is gateway-owned (ADR-0049 Amendment 3), so only the echo and
 * logs proxy routes remain.</p>
 */
class GatewayRoutesConfigTest {

    /** Subject under test; the route table is stateless. */
    private final GatewayRoutesConfig config = new GatewayRoutesConfig();

    /** Both remaining route beans publish a {@link RouterFunction}. */
    @Test
    void allRouteBeansAreNonNull() {
        assertThat(this.config.echoServiceRoute()).isNotNull();
        assertThat(this.config.logsServiceRoute()).isNotNull();
    }

    /** Each route bean is a distinct instance (no accidental aliasing). */
    @Test
    void routeBeansAreDistinctInstances() {
        assertThat(this.config.echoServiceRoute()).isNotSameAs(this.config.logsServiceRoute());
    }

    /**
     * P9.1c / LD148 regression guard: the logs proxy predicate matches
     * ingest WRITES ({@code POST}) but NOT gateway-owned reads
     * ({@code GET}) under the shared {@code /api/v1/logs/**} prefix, so
     * the reads fall through to their annotated controllers
     * ({@code SearchLogsController} P9.1b, the {@code getLogById}
     * controller P9.2) instead of being swallowed by the gateway
     * {@code RouterFunction}.
     */
    @Test
    void logsProxyMatchesPostWritesButNotGetReads() {
        final RouterFunction<ServerResponse> logs = this.config.logsServiceRoute();

        // Ingest writes proxy to log-ingest-service (batch today, stream per ADR-0004).
        assertThat(matches(logs, "POST", "/api/v1/logs/batch"))
                .as("POST /api/v1/logs/batch must match the ingest proxy").isTrue();
        assertThat(matches(logs, "POST", "/api/v1/logs/stream"))
                .as("POST /api/v1/logs/stream must match the ingest proxy").isTrue();

        // Gateway-owned reads must NOT match -> they reach their controllers (LD148).
        assertThat(matches(logs, "GET", "/api/v1/logs/search"))
                .as("GET /api/v1/logs/search must fall through to SearchLogsController").isFalse();
        assertThat(matches(logs, "GET", "/api/v1/logs/abc-123-event-id"))
                .as("GET /api/v1/logs/{eventId} must fall through to the getLogById controller")
                .isFalse();
    }

    /**
     * Evaluates the route predicate for a synthetic request.
     *
     * @param routerFunction the route under test
     * @param method         HTTP method
     * @param path           request path
     * @return {@code true} when the predicate matches (the request would
     *         be proxied), {@code false} when it falls through
     */
    private static boolean matches(final RouterFunction<ServerResponse> routerFunction,
            final String method, final String path) {
        final MockHttpServletRequest httpRequest = new MockHttpServletRequest(method, path);
        final List<HttpMessageConverter<?>> converters = List.of();
        final ServerRequest request = ServerRequest.create(httpRequest, converters);
        return routerFunction.route(request).isPresent();
    }
}
