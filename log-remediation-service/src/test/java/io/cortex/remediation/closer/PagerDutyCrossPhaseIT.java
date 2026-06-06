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
 * P6.1a cross-phase closer IT for the PagerDuty channel
 * ({@code cortex.remediation.dispatcher.provider=pagerduty}).
 * Mirrors {@link SlackCrossPhaseIT} -- one happy + one transient
 * envelope, per-test unique {@code tenant_id} tag for counter
 * scoping, on-the-wire POST shape verified against the local
 * WireMock at {@code /v2/enqueue} (ADR-0034 D2 PagerDuty Events
 * API v2 path), DLT stays empty.
 *
 * <p>Topic
 * ({@code cortex.anomalies.v1.cross-phase.pagerduty}) is PagerDuty-
 * scoped so this IT does NOT replay messages from the Slack / Jira
 * sibling ITs that share the same singleton broker (per-channel
 * kafka topic isolation per LD125).</p>
 *
 * <p>Routing key {@code 00000000000000000000000000000000} is the
 * all-zeros placeholder pinned by LD123 (neutral test credentials)
 * so GitGuardian does not detect a realistic routing-key prefix.</p>
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("PagerDutyCrossPhaseIT (P6.1a cross-phase closer Leg D)")
class PagerDutyCrossPhaseIT extends AnomalyCrossPhaseBaseIT {

    /** PagerDuty-scoped Kafka topic for this IT (per-channel isolation per LD125). */
    private static final String TOPIC = "cortex.anomalies.v1.cross-phase.pagerduty";

    /** PagerDuty Events API v2 enqueue path (ADR-0034 D2). */
    private static final String ENQUEUE_PATH = "/v2/enqueue";

    /** All-zeros placeholder routing key; LD123 -- never a realistic prefix. */
    private static final String NEUTRAL_ROUTING_KEY = "00000000000000000000000000000000";

    /**
     * Registers the PagerDuty-specific properties on the shared
     * base Spring context.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void pagerDutyProperties(final DynamicPropertyRegistry registry) {
        registry.add("cortex.remediation.topic", () -> TOPIC);
        registry.add("cortex.remediation.dispatcher.provider", () -> "pagerduty");
        registry.add("cortex.remediation.pagerduty.routing-key", () -> NEUTRAL_ROUTING_KEY);
        registry.add("cortex.remediation.pagerduty.events-url",
                () -> WIRE_MOCK.baseUrl() + ENQUEUE_PATH);
        registry.add("cortex.remediation.pagerduty.request-timeout", () -> "5s");
        registry.add("cortex.remediation.pagerduty.source", () -> "cortex-remediation-it");
        registry.add("cortex.remediation.pagerduty.severity-default", () -> "error");
        registry.add("spring.kafka.consumer.group-id",
                () -> "cortex.remediation.cross-phase.pagerduty-" + System.nanoTime());
    }

    /**
     * Pre-creates the PagerDuty-scoped topic + the global DLT topic
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
    PagerDutyCrossPhaseIT(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Happy-path leg: WireMock returns 202 Accepted; counter ticks
     * {@code channel=pagerduty outcome=dispatched
     * tenant_id=<unique>} exactly once.
     *
     * @throws Exception on publish or envelope serialization failure
     */
    @Test
    void happyPathTicksDispatched() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(ENQUEUE_PATH))
                .willReturn(aResponse().withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\"}")));

        final String tenantId = "tenant-cp-pd-happy-" + System.nanoTime();
        publish(TOPIC, buildEnvelope("evt-cp-pd-happy", tenantId));

        await().atMost(AWAIT).pollInterval(POLL).untilAsserted(() ->
                assertThat(counterValue(meterRegistry, "pagerduty", "dispatched", tenantId))
                        .isEqualTo(1.0d));

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(ENQUEUE_PATH)));
        assertThat(readDltRecords())
                .as("DLT MUST stay empty -- P6.4 dead-letter writer not shipped")
                .isEmpty();
    }

    /**
     * Transient leg: WireMock returns 500; counter ticks
     * {@code channel=pagerduty outcome=transient_failure
     * tenant_id=<unique>}. ADR-0034 D3 -- 5xx falls in the transient
     * bucket, no DLT write.
     *
     * @throws Exception on publish or envelope serialization failure
     */
    @Test
    void transientPathTicksTransientFailure() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(ENQUEUE_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final String tenantId = "tenant-cp-pd-transient-" + System.nanoTime();
        publish(TOPIC, buildEnvelope("evt-cp-pd-transient", tenantId));

        await().atMost(AWAIT).pollInterval(POLL).untilAsserted(() ->
                assertThat(counterValue(meterRegistry, "pagerduty", "transient_failure", tenantId))
                        .isEqualTo(1.0d));

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(ENQUEUE_PATH)));
        assertThat(readDltRecords())
                .as("DLT MUST stay empty -- transient failures are retried, not dead-lettered")
                .isEmpty();
    }
}
