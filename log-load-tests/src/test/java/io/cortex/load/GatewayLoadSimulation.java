package io.cortex.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SLO-driven load simulation for the CORTEX edge (log-gateway).
 *
 * <p>A single bootstrap login (run once in the constructor, before injection)
 * mints an access token that every virtual user reuses, so the measured load
 * stays on the two read surfaces a tenant hits most -- the NL-to-LogQL
 * translate endpoint ({@code POST /api/v1/query/nl}) and the searchLogs proxy
 * ({@code GET /api/v1/logs/search}). Authenticating once mirrors a real client
 * session and keeps the login throttle (a security control, functionally
 * covered by the Newman suite) out of the read-path SLO measurement.</p>
 *
 * <p>Assertions encode the platform SLOs from {@code plan.md} section 6
 * (gateway round-trip budget) so a latency or error-rate regression fails the
 * build. Under concurrent load the per-user NL/search sub-buckets may shed
 * traffic with HTTP 429; a fast, well-formed 429 is the rate limiter working
 * as designed, so it is accepted alongside 200/404 -- only a 5xx, a timeout,
 * or a p95 regression fails the run.</p>
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
    private static final String NL_PROMPT = prop(
        "cortex.load.nlPrompt", "trigger:HAPPY find error logs in payment service in last 1h");
    private static final String SEARCH_INDEX =
        prop("cortex.load.searchIndex", "cortex-cortex-dev-app");
    private static final String SEARCH_QUERY = prop("cortex.load.searchQuery", "level:ERROR");
    private static final int SEARCH_MAX_HITS = intProp("cortex.load.searchMaxHits", 20);
    private static final int P95_MILLIS = intProp("cortex.load.p95Millis", 1000);
    private static final double SUCCESS_PERCENT =
        doubleProp("cortex.load.successPercent", 99.0);

    /** Extracts {@code accessToken} from the bootstrap login JSON response. */
    private static final Pattern ACCESS_TOKEN_PATTERN =
        Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpProtocolBuilder httpProtocol = http
        .baseUrl(BASE_URL)
        .acceptHeader("application/json")
        .contentTypeHeader("application/json")
        .header("X-Tenant-Id", TENANT)
        .userAgentHeader("cortex-gatling/0.1.0");

    /**
     * Bootstrap access token, minted once by {@link #fetchAccessToken()} in the
     * constructor before injection starts and then replayed by every virtual
     * user. {@code volatile} so the injector threads observe the constructor's
     * write.
     */
    private volatile String accessToken;

    private final ChainBuilder nlToLogQl = http("nl to logql")
        .post("/api/v1/query/nl")
        .header("Authorization", "Bearer #{jwt}")
        .body(StringBody("{\"prompt\":\"" + NL_PROMPT + "\"}"))
        // 200 = translated, 404 = NL surface feature-gated off, 429 = per-user
        // sub-bucket shedding load. All are fast, well-formed gateway responses;
        // only a 5xx or a timeout is a real read-path regression.
        .check(status().in(200, 404, 429))
        .toChainBuilder();

    private final ChainBuilder searchLogs = http("search logs")
        .get("/api/v1/logs/search")
        .queryParam("index", SEARCH_INDEX)
        .queryParam("q", SEARCH_QUERY)
        .queryParam("maxHits", Integer.toString(SEARCH_MAX_HITS))
        .header("Authorization", "Bearer #{jwt}")
        // 200 = hits, 404 = index not yet materialised on a fresh stack,
        // 429 = sub-bucket shedding load. A 5xx or timeout fails the run.
        .check(status().in(200, 404, 429))
        .toChainBuilder();

    // Every virtual user replays the one bootstrap token, then drives the two
    // read surfaces back to back. The token is minted in the constructor before
    // injection starts, so #{jwt} is always populated here.
    private final ScenarioBuilder scenario = scenario("gateway read path")
        .exec(session -> session.set("jwt", this.accessToken))
        .exec(nlToLogQl)
        .pause(Duration.ofMillis(200))
        .exec(searchLogs);

    public GatewayLoadSimulation() {
        this.accessToken = fetchAccessToken();
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

    private static String fetchAccessToken() {
        final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .header("X-Tenant-Id", TENANT)
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}"))
            .build();
        try {
            final HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                    "load-test bootstrap login failed: HTTP " + response.statusCode()
                        + " body=" + response.body());
            }
            final Matcher matcher = ACCESS_TOKEN_PATTERN.matcher(response.body());
            if (!matcher.find()) {
                throw new IllegalStateException(
                    "load-test bootstrap login response carried no accessToken: "
                        + response.body());
            }
            return matcher.group(1);
        } catch (final IOException ex) {
            throw new IllegalStateException("load-test bootstrap login I/O failure", ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("load-test bootstrap login interrupted", ex);
        }
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
