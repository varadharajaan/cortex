package io.cortex.gateway.service.impl;

import io.cortex.gateway.config.NlQueryProperties;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.request.NlQueryRequest;
import io.cortex.gateway.dto.response.NlQueryResponse;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.exception.RateLimitedException;
import io.cortex.gateway.service.NlQueryService;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Default {@link NlQueryService} that delegates to a Spring AI
 * {@link ChatClient} (B20.1, P3.3 / ADR-0018).
 *
 * <p>Flow per call:</p>
 * <ol>
 *   <li>Consume one token from the per-principal NL sub-bucket via
 *       the rate-limit {@link ProxyManager}. On exhaustion, throw a
 *       {@link RateLimitedException} carrying
 *       {@link ErrorCodes#NL_QUERY_RATE_LIMITED} so the handler
 *       renders 429 + {@code Retry-After}. The LLM call is NOT made.</li>
 *   <li>Render the prompt template from
 *       {@code classpath:/prompts/nl-to-logql.st} substituting
 *       {@code {prompt}} with the user input.</li>
 *   <li>Call {@link ChatClient} with a structured-output converter
 *       producing an internal {@link ModelResponse} record.</li>
 *   <li>Hand the parsed response to {@link NlQueryValidator}. Any
 *       contract violation becomes 422 NL_QUERY_INVALID or
 *       NL_QUERY_REFUSED.</li>
 *   <li>Return the validated {@link NlQueryResponse} to the controller.</li>
 * </ol>
 *
 * <p>When the rate-limit subsystem is disabled (test classpath,
 * {@code cortex.gateway.rate-limit.enabled=false}), the
 * {@link ProxyManager} bean is absent; sub-bucket enforcement no-ops
 * gracefully via {@link Optional}.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cortex.gateway.nl-query", name = "enabled", havingValue = "true")
public class NlQueryServiceImpl implements NlQueryService {

    /** Spring AI chat client built lazily from the auto-configured builder (B20.1). */
    private final ChatClient chatClient;

    /** Typed NL-query configuration. */
    private final NlQueryProperties properties;

    /** NL sub-bucket configuration (capacity + refill window). */
    private final BucketConfiguration subBucketConfig;

    /** Distributed bucket proxy manager; absent when rate-limit is disabled. */
    private final Optional<ProxyManager<String>> proxyManager;

    /** Validator that enforces the ADR-0018 output contract. */
    private final NlQueryValidator validator;

    /**
     * Pre-loaded prompt template text. Spring AI's default ST4
     * {@code PromptTemplate} renderer treats {@code {...}} as variable
     * delimiters, which conflicts with the literal LogQL grammar
     * examples ({@code {label="value"}}, {@code {selector}}) embedded
     * in the system prompt. We bypass ST4 entirely and substitute the
     * single {@code {prompt}} placeholder with a literal replace.
     */
    private final String promptTemplateText;

    /**
     * Constructor injection of all collaborators.
     *
     * @param chatClientBuilder Spring AI chat client builder (auto-configured by the starter)
     * @param properties        typed NL-query configuration
     * @param subBucketConfig   per-principal NL sub-bucket configuration
     * @param proxyManager      distributed bucket proxy manager (may be absent)
     * @param validator         output-contract validator
     * @param promptTemplate    prompt template resource
     * @throws UncheckedIOException when the prompt template resource cannot be read
     */
    public NlQueryServiceImpl(
            final ChatClient.Builder chatClientBuilder,
            final NlQueryProperties properties,
            @Qualifier("nlQueryBucketConfiguration") final BucketConfiguration subBucketConfig,
            final Optional<ProxyManager<String>> proxyManager,
            final NlQueryValidator validator,
            @org.springframework.beans.factory.annotation.Value("classpath:/prompts/nl-to-logql.st")
            final Resource promptTemplate) {
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
        this.subBucketConfig = subBucketConfig;
        this.proxyManager = proxyManager;
        this.validator = validator;
        try {
            this.promptTemplateText = promptTemplate.getContentAsString(StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("failed to load NL prompt template", e);
        }
    }

    @Override
    public NlQueryResponse translate(final NlQueryRequest request, final String principalName) {
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            throw new ApplicationException(ErrorCodes.VALIDATION_FAILED, "prompt is blank");
        }
        if (principalName == null || principalName.isBlank()) {
            throw new ApplicationException(ErrorCodes.UNAUTHENTICATED, "principal is required");
        }
        enforceSubBucket(principalName);
        final String renderedPrompt = renderPrompt(request.prompt());
        final ModelResponse parsed = callModel(renderedPrompt);
        final NlQueryResponse response = new NlQueryResponse(
                parsed.logql(), parsed.confidence(), parsed.explanation());
        return validator.validate(response, properties.confidenceFloor());
    }

    /**
     * Consumes one token from the per-principal NL sub-bucket. Throws a
     * {@link RateLimitedException} carrying
     * {@link ErrorCodes#NL_QUERY_RATE_LIMITED} when the bucket is empty.
     *
     * @param principalName authenticated principal name
     * @throws RateLimitedException when the sub-bucket is exhausted
     */
    private void enforceSubBucket(final String principalName) {
        if (proxyManager.isEmpty()) {
            log.debug("NL sub-bucket skipped: ProxyManager is absent (rate-limit disabled)");
            return;
        }
        final String key = properties.subBucketKeyPrefix() + "user:" + principalName;
        final Bucket bucket = proxyManager.get().builder().build(key, () -> subBucketConfig);
        final ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            final long capacity = subBucketConfig.getBandwidths()[0].getCapacity();
            final long retryAfter = Math.max(
                    TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()), 1L);
            throw new RateLimitedException(
                    ErrorCodes.NL_QUERY_RATE_LIMITED, capacity, 0L, retryAfter);
        }
    }

    /**
     * Renders the prompt template with the user-supplied prompt via
     * literal substitution of the {@code {prompt}} placeholder. ST4
     * templating is intentionally avoided here -- see
     * {@link #promptTemplateText} for the rationale.
     *
     * @param userPrompt validated, length-capped user input
     * @return fully rendered prompt string
     */
    private String renderPrompt(final String userPrompt) {
        return promptTemplateText.replace("{prompt}", userPrompt);
    }

    /**
     * Calls the Spring AI chat client and parses the structured response.
     * Any failure (transport, parse, timeout) becomes
     * {@link ErrorCodes#NL_QUERY_UPSTREAM_FAILED}.
     *
     * @param renderedPrompt fully rendered prompt
     * @return parsed model response
     * @throws ApplicationException carrying NL_QUERY_UPSTREAM_FAILED when the upstream call fails
     */
    private ModelResponse callModel(final String renderedPrompt) {
        try {
            final ModelResponse parsed = chatClient
                    .prompt()
                    .user(renderedPrompt)
                    .call()
                    .entity(ModelResponse.class);
            if (parsed == null) {
                throw new ApplicationException(ErrorCodes.NL_QUERY_UPSTREAM_FAILED, "model returned no body");
            }
            return parsed;
        } catch (final ApplicationException e) {
            throw e;
        } catch (final RuntimeException e) {
            log.warn("NL-to-LogQL upstream call failed: {}", e.getMessage());
            throw new ApplicationException(
                    ErrorCodes.NL_QUERY_UPSTREAM_FAILED, "upstream model call failed", e);
        }
    }

    /**
     * Internal record mirroring the schema we ask the model to produce.
     * Public {@link NlQueryResponse} is constructed from this after
     * validation so the model schema and the HTTP DTO can evolve
     * independently.
     *
     * @param logql       LogQL query string
     * @param confidence  model-reported confidence
     * @param explanation human-readable justification
     */
    record ModelResponse(String logql, double confidence, String explanation) {
    }
}
