package io.cortex.gateway.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative per-feature rate-limit annotation
 * (P3.4 / ADR-0021, memory.md LD41 candidate 1).
 *
 * <p>Place on a Spring MVC controller method (or class) to opt that
 * handler into an independent Bucket4j sub-bucket on top of the global
 * P3.2 {@code RateLimitFilter}. The
 * {@link io.cortex.gateway.interceptor.RateLimitFeatureInterceptor}
 * resolves the annotation at request dispatch time, consumes one token
 * from a Redis-backed bucket keyed on
 * {@code <keyPrefix><name>:<principalKey>}, and throws
 * {@link io.cortex.gateway.exception.RateLimitedException} (carrying
 * {@link #errorCode()}) when the bucket is empty.</p>
 *
 * <p>All numeric / duration members are {@link String} so callers can
 * pass either literal values ({@code capacity = "10"}) or Spring
 * property placeholders ({@code capacity = "${cortex.gateway.nl-query.
 * sub-bucket-capacity:10}"}). The interceptor resolves placeholders
 * via the active {@link org.springframework.core.env.Environment} on
 * first encounter and caches the parsed
 * {@link io.github.bucket4j.BucketConfiguration} per {@link #name()}.</p>
 *
 * <p>The annotation deliberately does NOT use Spring AOP / AspectJ.
 * Cross-cutting wiring lives in a {@code HandlerInterceptor} (Spring
 * MVC primitive) per LD41 -- no {@code spring-aop} starter, no
 * {@code aspectjweaver}, no {@code aspect/} package.</p>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimitFeature {

    /** Default key prefix when callers do not supply one. */
    String DEFAULT_KEY_PREFIX = "cortex:rl:feat:";

    /** Default {@link io.cortex.gateway.constants.ErrorCodes} name surfaced on 429. */
    String DEFAULT_ERROR_CODE = "RATE_LIMITED";

    /**
     * Stable feature name; used in the bucket cache key, the Redis key,
     * structured-log fields, and the ArchUnit allow-list. MUST be
     * unique across the application.
     *
     * @return non-blank feature name
     */
    String name();

    /**
     * Bucket capacity (tokens per refill window). Accepts a literal
     * (e.g. {@code "10"}) or a placeholder
     * (e.g. {@code "${cortex.gateway.nl-query.sub-bucket-capacity:10}"}).
     *
     * @return capacity expression (literal or placeholder)
     */
    String capacity();

    /**
     * Refill window as an ISO-8601 {@link java.time.Duration} string.
     * Accepts a literal (e.g. {@code "PT1M"}) or a placeholder
     * (e.g. {@code "${cortex.gateway.nl-query.sub-bucket-refill-period:PT1M}"}).
     *
     * @return refill window expression
     */
    String refill();

    /**
     * Name of an {@link io.cortex.gateway.constants.ErrorCodes} enum
     * constant to surface in the RFC 7807 problem body when the bucket
     * is exhausted. Defaults to {@link #DEFAULT_ERROR_CODE} which maps
     * to the generic 429 path.
     *
     * @return error-code enum constant name
     */
    String errorCode() default DEFAULT_ERROR_CODE;

    /**
     * Redis key namespace. The full bucket key is built as
     * {@code <keyPrefix><name>:<principalOrIp>}; keep it stable across
     * deploys so existing buckets are not orphaned.
     *
     * @return key prefix (must end with the {@code :} separator)
     */
    String keyPrefix() default DEFAULT_KEY_PREFIX;
}
