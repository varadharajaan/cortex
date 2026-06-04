package io.cortex.processor.classify;

import io.cortex.processor.parse.RawLogEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Production {@link AnomalyClassifier} backed by Spring AI 1.0.0's
 * {@link ChatClient} (P5.2 / ADR-0029).
 *
 * <p>Gated by {@code cortex.processor.classifier=spring-ai}. The
 * default {@link NoopAnomalyClassifier} stays the fallback for any
 * profile that does not explicitly opt in.</p>
 *
 * <p>Provider is intentionally not named at the call site: a
 * {@link ChatClient.Builder} is injected and {@code build()}-ed
 * once at construction. Local dev wires the
 * {@code spring-ai-starter-model-ollama} starter (ADR-0006 +
 * ADR-0029 D1); the prod swap to Azure OpenAI is config-only per
 * ADR-0006 and is documented as a follow-up in ADR-0029 D6.</p>
 *
 * <p>Per-event flow:</p>
 * <ol>
 *   <li>Render the prompt template
 *       ({@code classpath:/prompts/anomaly-classifier.st}) with
 *       literal substring replacement on {@code {ts}},
 *       {@code {tenant_id}}, {@code {service}}, {@code {level}},
 *       {@code {labels}}, {@code {message}} placeholders. ST4
 *       templating is bypassed to dodge the ST4-vs-JSON-braces
 *       collision documented in memory.md LD42 (gateway P3.3 ships
 *       the same workaround).</li>
 *   <li>Call {@link ChatClient} with structured-output binding to
 *       {@link ModelClassification} (Spring AI's
 *       {@code BeanOutputConverter} appends JSON schema
 *       instructions automatically).</li>
 *   <li>Apply the
 *       {@link ClassifierProperties#confidenceThreshold() confidence
 *       threshold} gate. Below-threshold verdicts downgrade to
 *       {@link Classification#none()} (caller will count them as
 *       {@code outcome=low_confidence}).</li>
 *   <li>Map the surviving verdict to a {@link Classification} record
 *       handed back to {@code LogEventConsumer}.</li>
 * </ol>
 *
 * <p>Threading: {@link ChatClient} + the underlying
 * {@code OllamaApi} bean are thread-safe per Spring AI 1.0 contract.
 * This class holds no mutable state.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.processor.classifier",
        name = "provider",
        havingValue = "spring-ai"
)
@EnableConfigurationProperties(ClassifierProperties.class)
public class SpringAiAnomalyClassifier implements AnomalyClassifier {

    private static final Logger LOG =
            LoggerFactory.getLogger(SpringAiAnomalyClassifier.class);

    /** Default prompt template location (P5.2 / ADR-0029 D7). */
    private static final Resource DEFAULT_PROMPT_TEMPLATE =
            new ClassPathResource("prompts/anomaly-classifier.st");

    /** Severity values the prompt promises the model will emit. */
    private static final Set<String> ALLOWED_SEVERITIES =
            Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL", "NONE");

    /** Reason-string upper bound enforced by the prompt. */
    private static final int MAX_REASON_LENGTH = 256;

    /** Built once from the auto-configured Spring AI builder. */
    private final ChatClient chatClient;

    /** Typed classifier configuration (model, temperature, threshold). */
    private final ClassifierProperties properties;

    /** Pre-loaded prompt template text (see class Javadoc for rationale). */
    private final String promptTemplateText;

    /**
     * Spring constructor.
     *
     * @param chatClientBuilder Spring AI {@link ChatClient.Builder}
     *                          auto-configured by the active provider
     *                          starter (Ollama in dev)
     * @param properties        typed classifier configuration
     * @throws UncheckedIOException if the configured prompt template
     *                              cannot be read off the classpath
     */
    public SpringAiAnomalyClassifier(
            final ChatClient.Builder chatClientBuilder,
            final ClassifierProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        final Resource resource = properties.promptTemplate() != null
                ? properties.promptTemplate()
                : DEFAULT_PROMPT_TEMPLATE;
        try {
            this.promptTemplateText = resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new UncheckedIOException(
                    "failed to load anomaly classifier prompt template", ex);
        }
    }

    @Override
    public Classification classify(final RawLogEvent event) {
        final String rendered = renderPrompt(event);
        final ModelClassification raw;
        try {
            raw = this.chatClient.prompt()
                    .user(rendered)
                    .call()
                    .entity(ModelClassification.class);
        } catch (final RuntimeException ex) {
            // Surfaced to the consumer's classify-side catch which
            // ticks `outcome=error` and falls back to none() so the
            // Kafka offset still advances (ADR-0029 D4).
            LOG.warn("Spring AI anomaly classifier upstream call failed eventId={}: {}",
                    event.eventId(), ex.getMessage());
            throw ex;
        }
        if (raw == null) {
            LOG.warn("Spring AI anomaly classifier returned null entity eventId={}",
                    event.eventId());
            return Classification.none();
        }
        if (!raw.anomaly()) {
            return Classification.none();
        }
        if (raw.confidence() < this.properties.confidenceThreshold()) {
            LOG.debug("Anomaly verdict below confidence threshold eventId={} confidence={} threshold={}",
                    event.eventId(), raw.confidence(),
                    this.properties.confidenceThreshold());
            return Classification.none();
        }
        return new Classification(
                true,
                normaliseSeverity(raw.severity()),
                truncate(raw.reason()));
    }

    /**
     * Render the prompt template by literal placeholder substitution
     * (ST4 intentionally bypassed; see class Javadoc).
     *
     * @param event the validated log event under classification
     * @return fully rendered prompt string
     */
    private String renderPrompt(final RawLogEvent event) {
        return this.promptTemplateText
                .replace("{tenant_id}", StringUtils.defaultString(event.tenantId()))
                .replace("{ts}", event.ts() == null ? "" : event.ts().toString())
                .replace("{service}", StringUtils.defaultString(event.service()))
                .replace("{level}", StringUtils.defaultString(event.level()))
                .replace("{labels}", formatLabels(event.labels()))
                .replace("{message}", StringUtils.defaultString(event.message()));
    }

    /**
     * Render a label map as a compact {@code k1=v1, k2=v2} string
     * suitable for inclusion in the prompt body.
     *
     * @param labels event labels (never {@code null} per RawLogEvent
     *               defensive constructor)
     * @return one-line representation; empty string when no labels
     */
    private static String formatLabels(final Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (final Map.Entry<String, String> entry : labels.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    /**
     * Coerce a model-supplied severity string to the allowlist;
     * unknown values become {@code HIGH} (conservative bias for an
     * anomaly we already decided to surface).
     *
     * @param raw model-emitted severity string
     * @return one of {@link #ALLOWED_SEVERITIES}
     */
    private static String normaliseSeverity(final String raw) {
        if (raw == null) {
            return "HIGH";
        }
        final String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (ALLOWED_SEVERITIES.contains(upper)) {
            return "NONE".equals(upper) ? "HIGH" : upper;
        }
        return "HIGH";
    }

    /**
     * Clamp the reason string to the prompt-promised upper bound.
     *
     * @param raw model-emitted reason string
     * @return non-null, length-capped string
     */
    private static String truncate(final String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= MAX_REASON_LENGTH
                ? raw
                : raw.substring(0, MAX_REASON_LENGTH);
    }
}
