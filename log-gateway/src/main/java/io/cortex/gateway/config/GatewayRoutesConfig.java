package io.cortex.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.rewritePath;
import static org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions.lb;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Programmatic route table for the gateway (ADR-0014 / ADR-0016).
 *
 * <p>Routes are declared with the Spring Cloud Gateway MVC Java DSL
 * because the YAML route property prefix moved between 4.x and 5.x;
 * the Java DSL is stable across both. Each {@code lb://<service-id>}
 * URI is resolved by Spring Cloud LoadBalancer against the registry
 * the Eureka client fetches at startup (see {@code application.yml}).
 * If the service is unreachable, the global LoadBalancer filter
 * returns {@code 503 Service Unavailable}.</p>
 *
 * <p>P3.0b ships a single discovery route ({@code /echo/**} ->
 * {@code lb://log-echo-service}) so the verification triangle
 * (mvn verify + smoke + Newman) can prove end-to-end service
 * discovery before P4 brings in the real downstream services.
 * Auth still applies: {@link SecurityConfig} requires a bearer
 * JWT for any path that is not explicitly permitted.</p>
 */
@Configuration
public class GatewayRoutesConfig {

    /**
     * Discovery route that proxies {@code /echo/**} through the
     * load balancer to {@code log-echo-service} (registered in
     * Eureka). The throwaway {@code log-echo-service} stub mirrors
     * the request back as JSON so smoke tests can assert that
     * routing, headers, and {@code lb://} resolution all work end
     * to end.
     *
     * @return a router function carrying the echo route
     */
    @Bean
    public RouterFunction<ServerResponse> echoServiceRoute() {
        return route("log-echo-service")
                .route(path("/echo/**"), http())
                .filter(lb("log-echo-service"))
                .build();
    }

    /**
     * Logs proxy route: forwards {@code /api/v1/logs/**} through the
     * load balancer to {@code log-ingest-service} (P4.x flip from the
     * earlier P3.4 echo placeholder). The public path is rewritten to
     * the ingest service's internal path {@code /api/v1/ingest/**}
     * before forwarding because {@link io.cortex.ingest.controller.IngestController}
     * is mapped at {@code /api/v1/ingest/batch}, not
     * {@code /api/v1/logs/batch}. Auth + the global
     * {@code RateLimitFilter} apply because the path matches
     * {@link io.cortex.gateway.config.SecurityConfig}'s
     * {@code anyRequest().authenticated()} rule and the
     * {@code OncePerRequestFilter} chain.
     *
     * @return a router function carrying the logs proxy route
     */
    @Bean
    public RouterFunction<ServerResponse> logsServiceRoute() {
        return route("logs-service")
                .route(path("/api/v1/logs/**"), http())
                .before(rewritePath("/api/v1/logs/(?<segment>.*)", "/api/v1/ingest/${segment}"))
                .filter(lb("log-ingest-service"))
                .build();
    }

    /**
     * P3.4 search proxy route: forwards {@code /api/v1/search/**} via
     * the load balancer to {@code log-echo-service} (placeholder until
     * P7 brings up the real search service). Same auth + rate-limit
     * posture as {@link #logsServiceRoute()}.
     *
     * @return a router function carrying the search proxy route
     */
    @Bean
    public RouterFunction<ServerResponse> searchServiceRoute() {
        return route("search-service")
                .route(path("/api/v1/search/**"), http())
                .filter(lb("log-echo-service"))
                .build();
    }
}
