/**
 * Owns: unit tests for {@link io.cortex.gateway.interceptor.RateLimitFeatureInterceptor}
 * covering happy-path consume, exhaustion -&gt; 429, anonymous-IP key
 * fallback, unannotated handler skip, and placeholder resolution
 * (P3.4 / ADR-0021).
 */
package io.cortex.gateway.interceptor;
