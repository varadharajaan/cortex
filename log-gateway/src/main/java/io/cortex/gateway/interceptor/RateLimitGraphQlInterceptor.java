package io.cortex.gateway.interceptor;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.exception.RateLimitedException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.annotation.PostConstruct;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

/**
 * Spring for GraphQL {@link WebGraphQlInterceptor} that enforces
 * per-feature Bucket4j sub-buckets declared via
 * {@link RateLimitFeature @RateLimitFeature}
 * (P9.0a / ADR-0049 Amendment 1).
 *
 * <p>This is the GraphQL counterpart to
 * {@link RateLimitFeatureInterceptor} (which serves the Spring MVC
 * controller surface). Both interceptors resolve the annotation
 * members through the active {@link Environment}, compute the same
 * Bucket4j key for the same JWT subject
 * ({@code <keyPrefix><name>:user:<principal>}), and consume one token
 * via the shared {@link ProxyManager}. The parity contract is: REST
 * and GraphQL share a single bucket per JWT subject per feature.</p>
 *
 * <p>At {@code @PostConstruct} the interceptor scans every
 * {@link Controller @Controller} bean for methods carrying both
 * {@link QueryMapping @QueryMapping} and
 * {@link RateLimitFeature @RateLimitFeature}, and indexes the
 * annotation by GraphQL field name (the {@code @QueryMapping} value
 * if present, otherwise the method name). At request time the
 * interceptor parses the GraphQL document, walks the top-level
 * selections, and for every {@link Field} that matches the registry
 * it acquires the per-feature bucket and consumes one token. On
 * exhaustion it throws a {@link RateLimitedException} carrying the
 * resolved {@link ErrorCodes} so the existing
 * {@link io.cortex.gateway.exception.GlobalExceptionHandler}
 * {@code @ExceptionHandler} emits the same RFC 7807 problem detail
 * body REST callers receive (HTTP 429 + {@code Retry-After}).</p>
 *
 * <p>{@code @ConditionalOnProperty} matches the MVC interceptor gate
 * (and the {@code RateLimitFilter} gate) so disabling
 * {@code cortex.gateway.rate-limit.enabled} flips off the global
 * bucket AND both per-feature surfaces in one switch
 * (consistent with LD44).</p>
 *
 * <p>Mutations are deliberately NOT scanned -- ADR-0004 RA5 forever
 * rejects GraphQL mutations on this gateway, and
 * {@link org.springframework.graphql.data.method.annotation.MutationMapping}
 * methods will never be wired. Nested
 * {@link org.springframework.graphql.data.method.annotation.SchemaMapping}
 * field resolvers are out of scope for the P9.0a contract -- only
 * top-level operation fields participate.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cortex.gateway.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitGraphQlInterceptor implements WebGraphQlInterceptor {

    /** Shared Bucket4j proxy manager (Redis-backed via Lettuce in prod). */
    private final ProxyManager<String> proxyManager;

    /** Spring {@link Environment} for resolving {@code ${...}} placeholders. */
    private final Environment environment;

    /** Application context used to scan {@code @Controller} beans at startup. */
    private final ApplicationContext applicationContext;

    /** Per-feature {@link BucketConfiguration} cache keyed by {@link RateLimitFeature#name()}. */
    private final ConcurrentHashMap<String, BucketConfiguration> configByName = new ConcurrentHashMap<>();

    /** Field-name -&gt; {@link RateLimitFeature} registry populated at startup. */
    private final Map<String, RateLimitFeature> registry = new HashMap<>();

    /** Re-usable GraphQL document parser; the parser itself is stateless. */
    private final Parser documentParser = new Parser();

    /**
     * Constructor injection of the rate-limit collaborators.
     *
     * @param proxyManager       shared Bucket4j proxy manager
     * @param environment        Spring environment for placeholder resolution
     * @param applicationContext context used to discover {@code @Controller} beans
     */
    public RateLimitGraphQlInterceptor(
            final ProxyManager<String> proxyManager,
            final Environment environment,
            final ApplicationContext applicationContext) {
        this.proxyManager = proxyManager;
        this.environment = environment;
        this.applicationContext = applicationContext;
    }

    /**
     * Builds the field-name to {@link RateLimitFeature} registry by
     * scanning every {@link Controller @Controller} bean in the
     * application context. Package-private so the Surefire slice test
     * can invoke it directly after manual instantiation.
     *
     * <p>The lookup logic is symmetric with Spring for GraphQL's own
     * {@code AnnotatedControllerConfigurer}: a method's GraphQL field
     * name comes from {@link QueryMapping#value()} when set, otherwise
     * from the bare Java method name.</p>
     */
    @PostConstruct
    void buildRegistry() {
        final Map<String, Object> controllers =
                this.applicationContext.getBeansWithAnnotation(Controller.class);
        for (final Object bean : controllers.values()) {
            final Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (final Method method : targetClass.getDeclaredMethods()) {
                final RateLimitFeature feature = AnnotatedElementUtils.findMergedAnnotation(
                        method, RateLimitFeature.class);
                if (feature == null) {
                    continue;
                }
                final QueryMapping query = AnnotatedElementUtils.findMergedAnnotation(
                        method, QueryMapping.class);
                if (query == null) {
                    continue;
                }
                final String fieldName = query.value().isEmpty() ? method.getName() : query.value();
                this.registry.put(fieldName, feature);
                log.info(
                        "RateLimitGraphQlInterceptor registered field='{}' feature='{}' from {}.{}",
                        fieldName,
                        feature.name(),
                        targetClass.getSimpleName(),
                        method.getName());
            }
        }
    }

    /**
     * Parses the incoming GraphQL document and applies the per-feature
     * sub-bucket to every annotated top-level field. Throws
     * {@link RateLimitedException} on exhaustion; otherwise delegates
     * to the next interceptor in the chain.
     *
     * @param request inbound GraphQL request
     * @param chain   downstream interceptor chain
     * @return the chain's response {@link Mono}, or an error
     *         {@link Mono} carrying {@link RateLimitedException} when a
     *         bucket is exhausted
     */
    @Override
    public Mono<WebGraphQlResponse> intercept(final WebGraphQlRequest request, final Chain chain) {
        if (this.registry.isEmpty()) {
            return chain.next(request);
        }
        try {
            this.applyRateLimits(request);
        } catch (final RateLimitedException ex) {
            return Mono.error(ex);
        }
        return chain.next(request);
    }

    /**
     * Walks the top-level fields of every {@link OperationDefinition}
     * in the document; for each field present in the registry,
     * consumes one token from the per-feature bucket.
     *
     * @param request inbound GraphQL request whose
     *                {@link WebGraphQlRequest#getDocument()} carries
     *                the raw query text
     */
    private void applyRateLimits(final WebGraphQlRequest request) {
        final Document document = this.documentParser.parseDocument(request.getDocument());
        for (final Definition<?> definition : document.getDefinitions()) {
            if (!(definition instanceof OperationDefinition operation)) {
                continue;
            }
            if (operation.getSelectionSet() == null) {
                continue;
            }
            for (final Selection<?> selection : operation.getSelectionSet().getSelections()) {
                if (!(selection instanceof Field field)) {
                    continue;
                }
                final RateLimitFeature feature = this.registry.get(field.getName());
                if (feature == null) {
                    continue;
                }
                this.consumeOrThrow(feature, request);
            }
        }
    }

    /**
     * Acquires the per-feature bucket and consumes one token; throws
     * {@link RateLimitedException} carrying the resolved error code if
     * the bucket is exhausted.
     *
     * @param feature {@link RateLimitFeature} read off the resolver method
     * @param request inbound GraphQL request (supplies caller key)
     */
    private void consumeOrThrow(final RateLimitFeature feature, final WebGraphQlRequest request) {
        final long capacity = this.parseLongPlaceholder(feature.capacity(), feature.name(), "capacity");
        final ErrorCodes errorCode = this.parseErrorCode(feature.errorCode(), feature.name());
        final BucketConfiguration config = this.configByName.computeIfAbsent(
                feature.name(), name -> this.buildConfig(name, capacity, feature.refill()));

        final String resolvedPrefix = this.environment.resolvePlaceholders(feature.keyPrefix());
        final String key = resolvedPrefix + feature.name() + ":" + resolveCallerKey(request);
        final Bucket bucket = this.proxyManager.builder().build(key, () -> config);
        final ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            final long retryAfter = Math.max(
                    TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()), 1L);
            log.warn(
                    "@RateLimitFeature[{}] rejected key={} retryAfterSeconds={} (graphql)",
                    feature.name(), key, retryAfter);
            throw new RateLimitedException(errorCode, capacity, 0L, retryAfter);
        }
    }

    /**
     * Resolves a {@code String} placeholder via {@link Environment} and parses
     * it as a {@code long}. Mirrors the MVC interceptor's logic verbatim so
     * the two surfaces resolve identical bucket configurations.
     *
     * @param expression  placeholder or literal text from the annotation
     * @param featureName feature name (used in error messages)
     * @param memberName  annotation member name (used in error messages)
     * @return parsed long value
     * @throws IllegalStateException when the resolved value is not a parsable long
     */
    private long parseLongPlaceholder(
            final String expression, final String featureName, final String memberName) {
        final String resolved = this.environment.resolveRequiredPlaceholders(expression);
        try {
            return Long.parseLong(resolved.trim());
        } catch (final NumberFormatException ex) {
            throw new IllegalStateException(
                    "@RateLimitFeature[" + featureName + "]." + memberName
                            + " did not resolve to a long: '" + resolved + "'",
                    ex);
        }
    }

    /**
     * Resolves and validates the {@link ErrorCodes} enum constant named by
     * {@link RateLimitFeature#errorCode()}.
     *
     * @param expression  placeholder or literal text from the annotation
     * @param featureName feature name (used in error messages)
     * @return resolved {@link ErrorCodes} constant
     * @throws IllegalStateException when the resolved name is not a known constant
     */
    private ErrorCodes parseErrorCode(final String expression, final String featureName) {
        final String resolved = this.environment.resolveRequiredPlaceholders(expression).trim();
        try {
            return ErrorCodes.valueOf(resolved);
        } catch (final IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "@RateLimitFeature[" + featureName + "].errorCode is not a known ErrorCodes constant: '"
                            + resolved + "'",
                    ex);
        }
    }

    /**
     * Builds a {@link BucketConfiguration} with a single greedy-refill
     * bandwidth matching the annotation members.
     *
     * @param featureName      feature name (used in error messages)
     * @param capacity         parsed bucket capacity (tokens per window)
     * @param refillExpression placeholder or literal ISO-8601 duration
     * @return immutable bucket configuration
     * @throws IllegalStateException when the resolved refill text is not a parsable {@link Duration}
     */
    private BucketConfiguration buildConfig(
            final String featureName, final long capacity, final String refillExpression) {
        final String resolved = this.environment.resolveRequiredPlaceholders(refillExpression).trim();
        final Duration refill;
        try {
            refill = Duration.parse(resolved);
        } catch (final java.time.format.DateTimeParseException ex) {
            throw new IllegalStateException(
                    "@RateLimitFeature[" + featureName + "].refill did not resolve to an ISO-8601 Duration: '"
                            + resolved + "'",
                    ex);
        }
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, refill)
                        .build())
                .build();
    }

    /**
     * Builds the principal-or-ip suffix for the bucket key. Authenticated
     * callers are keyed by Spring Security principal name; anonymous
     * callers fall back to {@code X-Forwarded-For} (first hop) or a
     * stable {@code unknown} marker when no IP header is present
     * ({@link WebGraphQlRequest} intentionally does not expose the raw
     * servlet remote address). Matches the MVC convention for
     * authenticated callers exactly so REST and GraphQL share their
     * bucket for the same JWT subject (P9.0a parity contract).
     *
     * @param request inbound GraphQL request
     * @return non-blank caller key
     */
    private static String resolveCallerKey(final WebGraphQlRequest request) {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final boolean authenticated = auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getName());
        if (authenticated) {
            return "user:" + auth.getName();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * Best-effort client IP resolution honouring {@code X-Forwarded-For}
     * (first hop only). When no forwarded header is set the method
     * returns the literal string {@code "unknown"} so the bucket key
     * remains stable across calls (acceptable because the only
     * resolver that opts into rate-limit today,
     * {@code NlQueryGraphQlController.nlToLogQL}, is
     * {@code @PreAuthorize("isAuthenticated()")} so anonymous callers
     * never reach this path).
     *
     * @param request inbound GraphQL request
     * @return non-null client IP marker
     */
    private static String clientIp(final WebGraphQlRequest request) {
        final HttpHeaders headers = request.getHeaders();
        final List<String> xff = headers.getOrEmpty("X-Forwarded-For");
        if (!xff.isEmpty()) {
            final String first = xff.get(0);
            if (first != null && !first.isBlank()) {
                final int comma = first.indexOf(',');
                return comma > 0 ? first.substring(0, comma).trim() : first.trim();
            }
        }
        return "unknown";
    }
}
