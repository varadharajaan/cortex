package io.cortex.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.cortex.gateway.interceptor.RateLimitFeatureInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/**
 * Unit tests for {@link RateLimitFeatureConfig} (P3.4 / ADR-0021).
 *
 * <p>Asserts the interceptor is registered exclusively on the
 * {@code /api/**} surface so public actuator + auth paths are unaffected.</p>
 */
class RateLimitFeatureConfigTest {

    /** The configurer registers the interceptor on {@code /api/**}. */
    @Test
    void registersInterceptorOnApiPath() {
        final RateLimitFeatureInterceptor mockInterceptor = mock(RateLimitFeatureInterceptor.class);
        final RateLimitFeatureConfig config = new RateLimitFeatureConfig(mockInterceptor);

        final InterceptorRegistry registry = new InterceptorRegistry();
        config.addInterceptors(registry);

        final java.util.List<Object> registrations = extractRegistrations(registry);
        assertThat(registrations).hasSize(1);
        final InterceptorRegistration registration = (InterceptorRegistration) registrations.get(0);
        final java.util.List<String> patterns = extractPathPatterns(registration);
        assertThat(patterns).containsExactly("/api/**");
    }

    /**
     * Reflectively reads the private {@code registrations} field of
     * {@link InterceptorRegistry}; Spring exposes no public accessor.
     *
     * @param registry the registry to introspect
     * @return list of registered interceptors
     * @throws IllegalStateException if {@link InterceptorRegistry}'s internal layout has changed
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<Object> extractRegistrations(final InterceptorRegistry registry) {
        try {
            final java.lang.reflect.Field field = InterceptorRegistry.class.getDeclaredField("registrations");
            field.setAccessible(true);
            return (java.util.List<Object>) field.get(registry);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("InterceptorRegistry layout changed: " + e.getMessage(), e);
        }
    }

    /**
     * Reflectively reads the private {@code includePatterns} field of an
     * {@link InterceptorRegistration}; mirrors the technique used above.
     *
     * @param registration registration whose patterns to read
     * @return list of include path patterns
     * @throws IllegalStateException if {@link InterceptorRegistration}'s internal layout has changed
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> extractPathPatterns(final InterceptorRegistration registration) {
        try {
            final java.lang.reflect.Field field = InterceptorRegistration.class.getDeclaredField("includePatterns");
            field.setAccessible(true);
            return (java.util.List<String>) field.get(registration);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("InterceptorRegistration layout changed: " + e.getMessage(), e);
        }
    }
}
