/**
 * Owns: declarative annotations driving CORTEX cross-cutting features
 * (e.g. {@link io.cortex.gateway.annotation.RateLimitFeature}).
 *
 * <p>Annotations declared here have RUNTIME retention and are read by
 * companion Spring MVC interceptors in
 * {@link io.cortex.gateway.interceptor}. The pattern intentionally
 * avoids Spring AOP / AspectJ so the gateway pom stays free of
 * {@code spring-aop} and {@code aspectjweaver}; see ADR-0021 and
 * memory.md LD41.</p>
 */
package io.cortex.gateway.annotation;
