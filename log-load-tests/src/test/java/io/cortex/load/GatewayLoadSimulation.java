package io.cortex.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.jmesPath;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;

/**
 * SLO-driven load simulation for the CORTEX edge (log-gateway).
 *
 * <p>The closed-loop scenario authenticates against the gateway, then exercises
 * the two read surfaces a tenant hits most: the NL-to-LogQL translate endpoint
 * and the searchLogs proxy. Assertions encode the platform SLOs from
 * {@code plan.md} section 6 (gateway round-trip budget) so a latency or
 * error-rate regression fails the build.</p>
 *
 * <p>All knobs are {@code -D} system properties so the same simulation runs as
 * a quick smoke (default) or a heavier soak without code changes.</p>
 */
public class GatewayLoadSimulation extends Simulation {

    private static final String BASE_URL =
        prop("cortex.load.baseUrl", "http://localhost:8090");
    private static final int USERS = intProp("cortex.load.users", 20);
    private static final int RAMP_SECONDS = intProp("cortex.load.rampSeconds", 20);
    private static final String USERNAME = prop("cortex.load.username", "admin");
    private static final String PASSWORD = prop("cortex.load.password", "dev-admin-pass");
    private static final String TENANT = prop("cortex.load.tenant", "cortex-dev");
    private static final int P95_MILLIS = intProp("cortex.load.p95Millis", 1000);
    private static final double SUCCESS_PERCENT =
        doubleProp("cortex.load.successPercent", 99.0);

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(BASE_URL)
        .acceptHeader("application/json")
        .contentTypeHeader("application/json")
        .header("X-Tenant-Id", TENANT)
        .userAgentHeader("cortex-gatling/0.1.0");

    private final ChainBuilder login = http("auth login")
        .post("/api/v1/auth/login")
        .body(StringBody("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
        .check(status().is(200))
        .check(jmesPath("accessToken").saveAs("jwt"))
        .toChainBuilder();

    private final ChainBuilder nlToLogQl = http("nl to logql")
        .post("/api/v1/nl/translate")
        .header("Authorization", "Bearer #{jwt}")
        .body(StringBody("{\"prompt\":\"errors in the last hour for checkout\"}"))
        // The endpoint may be feature-gated off (404) in a given deploy; accept
        // either a successful translation or a clean not-found, never a 5xx.
        .check(status().in(200, 404))
        .toChainBuilder();

    private final ChainBuilder searchLogs = http("search logs")
        .get("/api/v1/logs/search?query=level%3DERROR&page=0&size=20")
        .header("Authorization", "Bearer #{jwt}")
        .check(status().in(200, 404))
        .toChainBuilder();

    private final ScenarioBuilder scenario = scenario("gateway read path")
        .exec(login)
        .pause(Duration.ofMillis(200))
        .exec(nlToLogQl)
        .pause(Duration.ofMillis(200))
        .exec(searchLogs);

    public GatewayLoadSimulation() {
        setUp(
            scenario.injectOpen(
                atOnceUsers(1),
                rampUsers(USERS).during(Duration.ofSeconds(RAMP_SECONDS))
            )
        ).protocols(httpProtocol)
         .assertions(
            global().responseTime().percentile(95.0).lt(P95_MILLIS),
            global().successfulRequests().percent().gte(SUCCESS_PERCENT)
         );
    }

    private static String prop(final String key, final String fallback) {
        final String value = System.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intProp(final String key, final int fallback) {
        try {
            return Integer.parseInt(prop(key, Integer.toString(fallback)));
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    private static double doubleProp(final String key, final double fallback) {
        try {
            return Double.parseDouble(prop(key, Double.toString(fallback)));
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }
}
