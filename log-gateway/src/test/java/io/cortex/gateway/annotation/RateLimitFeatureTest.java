package io.cortex.gateway.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link RateLimitFeature} annotation contract: targets, retention,
 * and member defaults (P3.4 / ADR-0021).
 */
class RateLimitFeatureTest {

    /** Annotation MUST be reflectively readable at runtime so the interceptor can find it. */
    @Test
    void retentionIsRuntime() {
        final Retention retention = RateLimitFeature.class.getAnnotation(Retention.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    /** Annotation MUST be placeable on both methods and types. */
    @Test
    void targetIsMethodAndType() {
        final Target target = RateLimitFeature.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactlyInAnyOrder(ElementType.METHOD, ElementType.TYPE);
    }

    /**
     * Member defaults match the documented constants.
     *
     * @throws Exception if reflection on the annotation members fails
     */
    @Test
    void memberDefaultsMatchConstants() throws Exception {
        assertThat(RateLimitFeature.class.getMethod("errorCode").getDefaultValue())
                .isEqualTo(RateLimitFeature.DEFAULT_ERROR_CODE);
        assertThat(RateLimitFeature.class.getMethod("keyPrefix").getDefaultValue())
                .isEqualTo(RateLimitFeature.DEFAULT_KEY_PREFIX);
        assertThat(RateLimitFeature.DEFAULT_KEY_PREFIX).endsWith(":");
    }
}
