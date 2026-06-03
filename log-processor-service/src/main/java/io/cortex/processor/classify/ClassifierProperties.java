package io.cortex.processor.classify;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Typed configuration for the AnomalyClassifier SPI
 * (P5.2 / ADR-0029).
 *
 * <p>Bound to the {@code cortex.processor.classifier} prefix. The
 * {@code provider} key is read by the {@code @ConditionalOnProperty}
 * on each classifier implementation; the remaining keys configure
 * the Spring AI variant (inert when {@code provider=noop}).</p>
 *
 * @param provider            classifier implementation selector;
 *                            {@code noop} (default) or {@code spring-ai}.
 *                            Bound here for type-safety; the same
 *                            value is read by {@code @ConditionalOnProperty}
 *                            on the classifier beans to pick which
 *                            implementation is active.
 * @param model               model identifier to request from the
 *                            chat provider (Ollama: {@code mistral},
 *                            {@code llama3.1:8b}; Azure OpenAI:
 *                            deployment name e.g. {@code gpt-4o-mini}).
 *                            Optional; when blank, the Spring AI
 *                            starter default for the active provider
 *                            is used.
 * @param temperature         model sampling temperature; lower
 *                            values give more deterministic verdicts
 *                            (default 0.2).
 * @param maxTokens           cap on response tokens; the classifier
 *                            response is a small JSON object so this
 *                            stays tight (default 256).
 * @param confidenceThreshold minimum confidence required for an
 *                            anomaly verdict to be returned to the
 *                            consumer. Verdicts with confidence
 *                            below this threshold are downgraded to
 *                            {@link Classification#none()}
 *                            (default 0.7).
 * @param requestTimeout      hard upper bound on a single LLM call;
 *                            currently advisory (Spring AI does not
 *                            yet expose a per-call timeout knob in
 *                            1.0.0 GA), wired into the metric label
 *                            and surfaced via {@code @Validated}
 *                            error messages (default 10s).
 * @param promptTemplate      classpath resource holding the prompt
 *                            template. Default
 *                            {@code classpath:/prompts/anomaly-classifier.st}.
 */
@ConfigurationProperties(prefix = "cortex.processor.classifier")
public record ClassifierProperties(
        String provider,
        String model,
        Double temperature,
        Integer maxTokens,
        Double confidenceThreshold,
        Duration requestTimeout,
        Resource promptTemplate) {

    /** Default provider selector used when none is configured. */
    public static final String DEFAULT_PROVIDER = "noop";

    /** Default sampling temperature used when none is configured. */
    public static final double DEFAULT_TEMPERATURE = 0.2d;

    /** Default response token cap used when none is configured. */
    public static final int DEFAULT_MAX_TOKENS = 256;

    /** Default confidence threshold used when none is configured. */
    public static final double DEFAULT_CONFIDENCE_THRESHOLD = 0.7d;

    /** Default per-call advisory timeout used when none is configured. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Defensive constructor that normalises {@code null} optional
     * fields to their documented defaults. Spring Boot binds the
     * properties first (per-field), then calls this canonical
     * constructor, so callers always observe a fully-populated
     * record.
     *
     * @param provider            see record-level Javadoc
     * @param model               see record-level Javadoc
     * @param temperature         see record-level Javadoc
     * @param maxTokens           see record-level Javadoc
     * @param confidenceThreshold see record-level Javadoc
     * @param requestTimeout      see record-level Javadoc
     * @param promptTemplate      see record-level Javadoc
     */
    public ClassifierProperties {
        if (provider == null || provider.isBlank()) {
            provider = DEFAULT_PROVIDER;
        }
        if (temperature == null) {
            temperature = DEFAULT_TEMPERATURE;
        }
        if (maxTokens == null) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
        if (confidenceThreshold == null) {
            confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD;
        }
        if (requestTimeout == null) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
    }
}
