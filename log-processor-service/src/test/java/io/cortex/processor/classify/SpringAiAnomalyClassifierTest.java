package io.cortex.processor.classify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.cortex.processor.parse.RawLogEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

/**
 * Pure unit test for {@link SpringAiAnomalyClassifier}
 * (P5.2 / ADR-0029).
 *
 * <p>Stubs the Spring AI 1.0 {@link ChatClient.Builder} -&gt;
 * {@link ChatClient} call chain with Mockito so the test never
 * issues an outbound HTTP request to Ollama. Each test scripts the
 * terminal {@code entity(ModelClassification.class)} call to return
 * a specific structured-output response and asserts the verdict
 * the classifier hands back to {@code LogEventConsumer}.</p>
 *
 * <p>Mirrors the gateway P3.3 / ADR-0018
 * {@code NlQueryIntegrationTest} test shape minus the
 * {@code @SpringBootTest} surface; the classifier has no Spring
 * collaborators beyond the {@link ChatClient.Builder} so a pure
 * Mockito test is enough.</p>
 */
class SpringAiAnomalyClassifierTest {

    /** Classpath resource backing the prompt template assertion. */
    private static final ClassPathResource PROMPT_TEMPLATE =
            new ClassPathResource("prompts/anomaly-classifier.st");

    /**
     * High-confidence anomaly verdict above the threshold returns a
     * populated {@link Classification} with the severity normalised
     * to the allowlist.
     */
    @Test
    void anomalyAboveThresholdReturnsPopulatedClassification() {
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(true, "high", "elevated 500s in payments", 0.85));

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict.anomaly()).isTrue();
        assertThat(verdict.severity()).isEqualTo("HIGH");
        assertThat(verdict.reason()).isEqualTo("elevated 500s in payments");
    }

    /** Below-threshold anomaly verdict downgrades to {@link Classification#none()}. */
    @Test
    void anomalyBelowThresholdDowngradesToNone() {
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(true, "MEDIUM", "noisy logs", 0.4));

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict).isSameAs(Classification.none());
    }

    /** Benign verdict (anomaly=false) always returns the none() singleton. */
    @Test
    void normalVerdictReturnsNoneSingleton() {
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(false, "NONE", "", 0.99));

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict).isSameAs(Classification.none());
    }

    /** Null entity from the model surfaces as none() (defensive guard). */
    @Test
    void nullEntityReturnsNone() {
        final ChatClient.CallResponseSpec callResponse =
                mock(ChatClient.CallResponseSpec.class);
        when(callResponse.entity(ModelClassification.class)).thenReturn(null);
        final SpringAiAnomalyClassifier classifier = newClassifierWithCallResponse(callResponse);

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict).isSameAs(Classification.none());
    }

    /**
     * Upstream {@link RuntimeException} from the chat client
     * rethrows so the consumer's classify-side catch can tick the
     * error outcome (ADR-0029 D4).
     */
    @Test
    void upstreamExceptionRethrowsForConsumerErrorCounter() {
        final ChatClient.CallResponseSpec callResponse =
                mock(ChatClient.CallResponseSpec.class);
        when(callResponse.entity(ModelClassification.class))
                .thenThrow(new RuntimeException("upstream model timeout"));
        final SpringAiAnomalyClassifier classifier = newClassifierWithCallResponse(callResponse);

        assertThatThrownBy(() -> classifier.classify(sampleEvent()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("upstream model timeout");
    }

    /**
     * Severity values outside the allowlist coerce to {@code HIGH}
     * (conservative bias for an anomaly the model already flagged).
     */
    @Test
    void unknownSeverityCoercesToHigh() {
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(true, "EXTREME", "unprecedented", 0.9));

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict.severity()).isEqualTo("HIGH");
    }

    /**
     * An anomaly-true verdict whose severity comes back as
     * {@code NONE} (contradictory; model glitch) coerces to
     * {@code HIGH}.
     */
    @Test
    void anomalyWithNoneSeverityCoercesToHigh() {
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(true, "NONE", "weird", 0.8));

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict.severity()).isEqualTo("HIGH");
    }

    /**
     * Reason strings longer than the prompt-promised 256-char bound
     * are truncated to keep the downstream P5.4 outbox payload
     * bounded.
     */
    @Test
    void overlongReasonIsTruncated() {
        final String over = "x".repeat(300);
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(true, "HIGH", over, 0.9));

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict.reason()).hasSize(256);
    }

    /**
     * {@code null} reason emitted by the model surfaces as an empty
     * string verdict (truncate guard branch).
     */
    @Test
    void nullReasonCoercesToEmptyString() {
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(true, "HIGH", null, 0.9));

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict.anomaly()).isTrue();
        assertThat(verdict.reason()).isEmpty();
    }

    /**
     * When {@link ClassifierProperties#promptTemplate()} is
     * {@code null} the classifier falls back to the bundled default
     * classpath template so the constructor's ternary branch is
     * covered.
     */
    @Test
    void nullPromptTemplatePropertyFallsBackToDefault() {
        final ChatClient.CallResponseSpec callResponse =
                mock(ChatClient.CallResponseSpec.class);
        when(callResponse.entity(ModelClassification.class)).thenReturn(
                new ModelClassification(false, "NONE", "", 0.99));
        final ChatClient.ChatClientRequestSpec requestSpec =
                mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponse);
        final ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        final ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        final ClassifierProperties propertiesWithNullTemplate =
                new ClassifierProperties(
                        "spring-ai", "mistral", 0.2d, 256, 0.7d,
                        Duration.ofSeconds(10), null);
        final SpringAiAnomalyClassifier classifier =
                new SpringAiAnomalyClassifier(builder, propertiesWithNullTemplate);

        final Classification verdict = classifier.classify(sampleEvent());

        assertThat(verdict).isSameAs(Classification.none());
    }

    /**
     * Render path with {@code null}/empty event fields hits the
     * {@code nullToEmpty}, {@code formatLabels(empty)}, and
     * {@code ts == null} defensive branches.
     */
    @Test
    void renderHandlesEmptyLabelsAndNullTimestampAndNullFields() {
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(false, "NONE", "", 0.99));
        final RawLogEvent sparse = new RawLogEvent(
                null, "evt-empty", null,
                null, null, null,
                Map.of(),
                null, null);

        final Classification verdict = classifier.classify(sparse);

        assertThat(verdict).isSameAs(Classification.none());
    }

    /**
     * Single-label rendering does not emit the {@code ", "}
     * separator (covers the {@code !first} branch in
     * {@code formatLabels}).
     */
    @Test
    void singleLabelRendersWithoutSeparator() {
        final SpringAiAnomalyClassifier classifier = newClassifier(
                new ModelClassification(false, "NONE", "", 0.99));
        final RawLogEvent oneLabel = new RawLogEvent(
                "cortex-dev", "evt-one-label", Instant.parse("2026-06-03T12:00:00Z"),
                "INFO", "checkout", "single-label",
                Map.of("region", "eastus"),
                null, null);

        final Classification verdict = classifier.classify(oneLabel);

        assertThat(verdict).isSameAs(Classification.none());
    }

    /**
     * Builds a SpringAiAnomalyClassifier whose chat client returns
     * the supplied structured-output entity.
     *
     * @param entity the {@link ModelClassification} the stubbed
     *               {@code .entity(ModelClassification.class)} call
     *               should hand back
     * @return classifier under test
     */
    private static SpringAiAnomalyClassifier newClassifier(final ModelClassification entity) {
        final ChatClient.CallResponseSpec callResponse =
                mock(ChatClient.CallResponseSpec.class);
        when(callResponse.entity(ModelClassification.class)).thenReturn(entity);
        return newClassifierWithCallResponse(callResponse);
    }

    /**
     * Builds the classifier wired to a pre-built terminal
     * {@code CallResponseSpec} mock so tests can script either an
     * entity response or an exception.
     *
     * @param callResponse the stubbed terminal response spec
     * @return classifier under test
     */
    private static SpringAiAnomalyClassifier newClassifierWithCallResponse(
            final ChatClient.CallResponseSpec callResponse) {
        final ChatClient.ChatClientRequestSpec requestSpec =
                mock(ChatClient.ChatClientRequestSpec.class);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponse);
        final ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        final ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        final ClassifierProperties properties = new ClassifierProperties(
                "spring-ai", "mistral", 0.2d, 256, 0.7d,
                Duration.ofSeconds(10), PROMPT_TEMPLATE);
        return new SpringAiAnomalyClassifier(builder, properties);
    }

    /**
     * Sample {@link RawLogEvent} the prompt renders against.
     *
     * @return well-formed event
     */
    private static RawLogEvent sampleEvent() {
        return new RawLogEvent(
                "cortex-dev", "evt-1", Instant.parse("2026-06-03T12:00:00Z"),
                "ERROR", "payments",
                "Connection reset by peer (upstream gateway)",
                Map.of("region", "eastus", "pod", "payments-7c"),
                "idk-1", Instant.parse("2026-06-03T12:00:01Z"));
    }
}
