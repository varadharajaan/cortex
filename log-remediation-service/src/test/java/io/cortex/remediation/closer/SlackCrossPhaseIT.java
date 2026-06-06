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
 * P6.1a cross-phase closer IT for the Slack channel
 * ({@code cortex.remediation.dispatcher.provider=slack}). Boots a
 * full {@code @SpringBootTest} context wired to the shared
 * Testcontainers Kafka + WireMock owned by
 * {@link AnomalyCrossPhaseBaseIT}, publishes one happy + one
 * transient CloudEvents envelope to a Slack-scoped Kafka topic,
 * and asserts (a) the {@code cortex.remediation.dispatched_total}
 * counter ticks on the right outcome bucket scoped by a per-test
 * unique {@code tenant_id} tag; (b) WireMock recorded the matching
 * POST against the Slack webhook path; (c) the global DLT topic
 * stayed empty (P6.4 / ADR-0032 D4 hasn't shipped, so the consumer
 * MUST acknowledge parse failures without DLT writes in P6.1a).
 *
 * <p>Topic is Slack-scoped
 * ({@code cortex.anomalies.v1.cross-phase.slack}) so this IT does
 * NOT replay messages from the PagerDuty / Jira sibling ITs that
 * share the same singleton broker (per the per-channel kafka topic
 * isolation lesson captured in
 * {@code memory.md} LD125 / {@code memories/repo/smoke-kafka-topic-isolation.md}).</p>
 */
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("SlackCrossPhaseIT (P6.1a cross-phase closer Leg D)")
class SlackCrossPhaseIT extends AnomalyCrossPhaseBaseIT {

    /** Slack-scoped Kafka topic for this IT (per-channel isolation per LD125). */
    private static final String TOPIC = "cortex.anomalies.v1.cross-phase.slack";

    /** Neutral webhook path under WireMock; LD123 -- no realistic Slack webhook secret prefix. */
    private static final String WEBHOOK_PATH = "/services/IT/CROSS/PHASE";

    /**
     * Registers the Slack-specific properties on the shared base
     * Spring context. Inherits {@code spring.kafka.bootstrap-servers}
     * + eureka disablement from {@link AnomalyCrossPhaseBaseIT#baseProperties}.
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void slackProperties(final DynamicPropertyRegistry registry) {
        registry.add("cortex.remediation.topic", () -> TOPIC);
        registry.add("cortex.remediation.dispatcher.provider", () -> "slack");
        registry.add("cortex.remediation.slack.webhook-url",
                () -> WIRE_MOCK.baseUrl() + WEBHOOK_PATH);
        registry.add("cortex.remediation.slack.request-timeout", () -> "5s");
        registry.add("cortex.remediation.slack.username", () -> "cortex-remediation-it");
        registry.add("spring.kafka.consumer.group-id",
                () -> "cortex.remediation.cross-phase.slack-" + System.nanoTime());
    }

    /**
     * Pre-creates the Slack-scoped topic + the global DLT topic on
     * the shared broker before the Spring context boots so the
     * @KafkaListener finds the partition under KRaft auto-create
     * latency.
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
    SlackCrossPhaseIT(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Happy-path leg: WireMock returns 200 OK; the consumer ->
     * SlackRemediationDispatcher -> WireMock -> counter chain ticks
     * {@code channel=slack outcome=dispatched tenant_id=<unique>}
     * exactly once.
     *
     * @throws Exception on publish or envelope serialization failure
     */
    @Test
    void happyPathTicksDispatched() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        final String tenantId = "tenant-cp-slack-happy-" + System.nanoTime();
        publish(TOPIC, buildEnvelope("evt-cp-slack-happy", tenantId));

        await().atMost(AWAIT).pollInterval(POLL).untilAsserted(() ->
                assertThat(counterValue(meterRegistry, "slack", "dispatched", tenantId))
                        .isEqualTo(1.0d));

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(WEBHOOK_PATH)));
        assertThat(readDltRecords())
                .as("DLT MUST stay empty -- P6.4 dead-letter writer not shipped")
                .isEmpty();
    }

    /**
     * Transient leg: WireMock returns 500; the chain ticks
     * {@code channel=slack outcome=transient_failure
     * tenant_id=<unique>} exactly once. ADR-0033 D3 -- 5xx falls in
     * the transient bucket, no DLT write.
     *
     * @throws Exception on publish or envelope serialization failure
     */
    @Test
    void transientPathTicksTransientFailure() throws Exception {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(WEBHOOK_PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        final String tenantId = "tenant-cp-slack-transient-" + System.nanoTime();
        publish(TOPIC, buildEnvelope("evt-cp-slack-transient", tenantId));

        await().atMost(AWAIT).pollInterval(POLL).untilAsserted(() ->
                assertThat(counterValue(meterRegistry, "slack", "transient_failure", tenantId))
                        .isEqualTo(1.0d));

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(WEBHOOK_PATH)));
        assertThat(readDltRecords())
                .as("DLT MUST stay empty -- transient failures are retried, not dead-lettered")
                .isEmpty();
    }
}
