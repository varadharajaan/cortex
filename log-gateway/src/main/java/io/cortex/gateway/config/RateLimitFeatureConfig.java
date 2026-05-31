package io.cortex.gateway.config;

import io.cortex.gateway.interceptor.RateLimitFeatureInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link RateLimitFeatureInterceptor} on the gateway's
 * {@code /api/**} surface (P3.4 / ADR-0021).
 *
 * <p>The interceptor is only active when the rate-limit subsystem is
 * enabled: the {@code @ConditionalOnProperty} gate matches the bean's
 * own gate so the registration is a no-op (the bean is absent) when
 * {@code cortex.gateway.rate-limit.enabled=false} on the test
 * classpath. Public health, actuator, OpenAPI, and Swagger endpoints
 * live outside {@code /api/**}, so they are excluded for free.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "cortex.gateway.rate-limit", name = "enabled", havingValue = "true")
public class RateLimitFeatureConfig implements WebMvcConfigurer {

    /** Interceptor that enforces {@code @RateLimitFeature} sub-buckets. */
    private final RateLimitFeatureInterceptor interceptor;

    /**
     * Constructor injection of the rate-limit feature interceptor.
     *
     * @param interceptor interceptor bean
     */
    public RateLimitFeatureConfig(final RateLimitFeatureInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(final InterceptorRegistry registry) {
        registry.addInterceptor(this.interceptor).addPathPatterns("/api/**");
    }
}
