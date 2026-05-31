/**
 * Owns: Spring MVC {@link org.springframework.web.servlet.HandlerInterceptor}
 * implementations driving CORTEX cross-cutting features
 * (e.g. {@link io.cortex.gateway.interceptor.RateLimitFeatureInterceptor}).
 *
 * <p>Interceptors here are registered via
 * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer}
 * in {@link io.cortex.gateway.config}; they intentionally avoid Spring
 * AOP / AspectJ -- the rationale is captured in ADR-0021 and
 * memory.md LD41.</p>
 */
package io.cortex.gateway.interceptor;
