/**
 * Owns: Spring MVC {@link org.springframework.web.servlet.HandlerInterceptor}
 * implementations driving CORTEX cross-cutting features
 * (e.g. {@link io.cortex.gateway.interceptor.RateLimitFeatureInterceptor})
 * plus the matching Spring for GraphQL
 * {@link org.springframework.graphql.server.WebGraphQlInterceptor}
 * implementations (e.g.
 * {@link io.cortex.gateway.interceptor.RateLimitGraphQlInterceptor},
 * P9.0a / ADR-0049 Amendment 1) so the same cross-cutting policies
 * fire on both REST and GraphQL surfaces with identical bucket keys.
 *
 * <p>Interceptors here are registered via
 * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer}
 * in {@link io.cortex.gateway.config} (MVC variants) or auto-detected
 * as Spring beans (GraphQL variants); they intentionally avoid Spring
 * AOP / AspectJ -- the rationale is captured in ADR-0021 and
 * memory.md LD41.</p>
 */
package io.cortex.gateway.interceptor;
