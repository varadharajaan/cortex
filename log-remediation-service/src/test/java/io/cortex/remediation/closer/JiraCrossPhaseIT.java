package io.cortex.remediation.closer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;

/**
 * P6.1a cross-phase closer IT for the Jira channel
 * ({@code cortex.remediation.dispatcher.provider=jira}). Mirrors
 * {@link SlackCrossPhaseIT} and {@link PagerDutyCrossPhaseIT} --
 * one happy + one transient envelope, per-test unique
 * {@code tenant_id} tag for counter scoping, on-the-wire POST shape
 * verified against the local WireMock at
 * {@code /rest/api/3/issue} (ADR-0035 D2 Jira Cloud REST v3 path),
 * DLT stays empty.
 *
 * <p>Topic ({@code cortex.anomalies.v1.cross-phase.jira}) is Jira-
 * scoped per the per-channel kafka topic isolation lesson
 * (LD125).</p>
 *
 * <p>Email + api-token are NEUTRAL per LD123 ({@code test@example.com}
 * + the literal string {@code placeholder-token-not-real}, NOT a
 * realistic {@code ATATT3xFfGF0...} Atlassian-format token prefix)
 * so GitGuardian does not flag the IT fixture.</p>
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("JiraCrossPhaseIT (P6.1a cross-phase closer Leg D)")
class JiraCrossPhaseIT extends AnomalyCrossPhaseBaseIT {

    /** Jira-scoped Kafka topic for this IT (per-channel isolation per LD125). */
    private static final String TOPIC = "cortex.anomalies.v1.cross-phase.jira";

    /** Jira Cloud REST v3 createIssue path (ADR-0035 D2). */
    private static final String ISSUE_PATH = "/rest/api/3/issue";

    /** Neutral email per LD123 -- never a realistic Atlassian account. */
    private static final String NEUTRAL_EMAIL = "test@example.com";

    /** Neutral api-token per LD123 -- never a realistic ATATT3xFfGF0 prefix. */
    private static final String NEUTRAL_API_TOKEN = "placeholder-token-not-real";

    /** Neutral Jira project key for the IT fixture. */
    private static final String NEUTRAL_PROJECT_KEY = "IT";

    /**
     * Registers the Jira-specific properties on the shared base
     * Spring context.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void jiraProperties(final DynamicPropertyRegistry registry) {
        registry.add("cortex.remediation.topic", () -> TOPIC);
        registry.add("cortex.remediation.dispatcher.provider", () -> "jira");
        registry.add("cortex.remediation.jira.base-url", WIRE_MOCK::baseUrl);
        registry.add("cortex.remediation.jira.email", () -> NEUTRAL_EMAIL);
        registry.add("cortex.remediation.jira.api-token", () -> NEUTRAL_API_TOKEN);
        registry.add("cortex.remediation.jira.request-timeout", () -> "5s");
        registry.add("cortex.remediation.jira.project-key", () -> NEUTRAL_PROJECT_KEY);
        registry.add("cortex.remediation.jira.issue-type", () -> "Bug");
        registry.add("cortex.remediation.jira.severity-label-prefix", () -> "anomaly-severity");
        registry.add("spring.kafka.consumer.group-id",
                () -> "cortex.remediation.cross-phase.jira-" + System.nanoTime());
    }

    /**
     * Pre-creates the Jira-scoped topic + the global DLT topic
     * before the Spring context boots.
     *
     * @throws ExecutionException   if the broker reports a non-recoverable error
     * @throws InterruptedException if the test thread is interrupted
     */
    @BeforeAll
    static void createTopics() throws ExecutionException, InterruptedException {
        preCreateTopic(TOPIC);
        preCreateTopic(DLT_TOPIC);
    }

    private final MeterRegistry meterRegistry;

    /**
     * Spring constructor injection.
     *
     * @param meterRegistry the autoconfigured registry hosting
     *                      {@code cortex.remediation.dispatched_total}
     */
    JiraCrossPhaseIT(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Happy-path leg: WireMock returns 201 Created with a synthetic
     * Jira issue body; counter ticks
     * {@code channel=jira outcome=dispatched tenant_id=<unique>}
     * exactly once.
     *
     * @throws Exception on publish or envelope serialization failure
     */
    @Test
    void happyPathTicksDispatched() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(ISSUE_PATH))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"10001\",\"key\":\"IT-1\","
                                + "\"self\":\"http://stub" + ISSUE_PATH + "/10001\"}")));

        final String tenantId = "tenant-cp-jira-happy-" + System.nanoTime();
        publish(TOPIC, buildEnvelope("evt-cp-jira-happy", tenantId));

        await().atMost(AWAIT).pollInterval(POLL).untilAsserted(() ->
                assertThat(counterValue(meterRegistry, "jira", "dispatched", tenantId))
                        .isEqualTo(1.0d));

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(ISSUE_PATH)));
        assertThat(readDltRecords())
                .as("DLT MUST stay empty -- P6.4 dead-letter writer not shipped")
                .isEmpty();
    }

    /**
     * Transient leg: WireMock returns 500; counter ticks
     * {@code channel=jira outcome=transient_failure
     * tenant_id=<unique>}. ADR-0035 D3 -- 5xx falls in the transient
     * bucket, no DLT write.
     *
     * @throws Exception on publish or envelope serialization failure
     */
    @Test
    void transientPathTicksTransientFailure() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(ISSUE_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final String tenantId = "tenant-cp-jira-transient-" + System.nanoTime();
        publish(TOPIC, buildEnvelope("evt-cp-jira-transient", tenantId));

        await().atMost(AWAIT).pollInterval(POLL).untilAsserted(() ->
                assertThat(counterValue(meterRegistry, "jira", "transient_failure", tenantId))
                        .isEqualTo(1.0d));

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(ISSUE_PATH)));
        assertThat(readDltRecords())
                .as("DLT MUST stay empty -- transient failures are retried, not dead-lettered")
                .isEmpty();
    }
}
