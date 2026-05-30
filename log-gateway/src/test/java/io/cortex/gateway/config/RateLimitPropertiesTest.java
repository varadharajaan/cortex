package io.cortex.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RateLimitProperties}: verifies the canonical
 * constructor's null-safe defaults so a partially specified yaml block
 * (e.g. test classpath with only {@code enabled: false}) still yields
 * a usable record.
 */
class RateLimitPropertiesTest {

    /**
     * Null {@code refillPeriod} defaults to one minute so a half-filled
     * test config does not NPE on the bucket configuration bean.
     */
    @Test
    void refillPeriodDefaultsToOneMinuteWhenNull() {
        final RateLimitProperties p = new RateLimitProperties(
                false, 0L, null, 0L, "redis://localhost:6379", "p:", List.of());

        assertThat(p.refillPeriod()).isEqualTo(Duration.ofMinutes(1));
    }

    /** Blank or null {@code keyPrefix} falls back to the documented constant. */
    @Test
    void keyPrefixDefaultsToConstantWhenBlank() {
        final RateLimitProperties blank = new RateLimitProperties(
                true, 10L, Duration.ofSeconds(30), 5L, "redis://localhost", "   ", List.of("/x"));
        final RateLimitProperties nullPrefix = new RateLimitProperties(
                true, 10L, Duration.ofSeconds(30), 5L, "redis://localhost", null, List.of("/x"));

        assertThat(blank.keyPrefix()).isEqualTo(RateLimitProperties.DEFAULT_KEY_PREFIX);
        assertThat(nullPrefix.keyPrefix()).isEqualTo(RateLimitProperties.DEFAULT_KEY_PREFIX);
    }

    /** Null or empty {@code excludedPaths} fall back to the actuator + Swagger list. */
    @Test
    void excludedPathsDefaultsToActuatorAndSwagger() {
        final RateLimitProperties nullList = new RateLimitProperties(
                true, 1L, Duration.ofSeconds(1), 1L, "redis://x", "p:", null);
        final RateLimitProperties emptyList = new RateLimitProperties(
                true, 1L, Duration.ofSeconds(1), 1L, "redis://x", "p:", List.of());

        assertThat(nullList.excludedPaths()).isEqualTo(RateLimitProperties.DEFAULT_EXCLUDED_PATHS);
        assertThat(emptyList.excludedPaths()).isEqualTo(RateLimitProperties.DEFAULT_EXCLUDED_PATHS);
    }

    /** Explicitly supplied values pass through unchanged. */
    @Test
    void explicitValuesPassThrough() {
        final RateLimitProperties p = new RateLimitProperties(
                true, 25L, Duration.ofSeconds(10), 5L, "redis://r:1",
                "ns:", List.of("/private"));

        assertThat(p.enabled()).isTrue();
        assertThat(p.capacity()).isEqualTo(25L);
        assertThat(p.refillPeriod()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.anonymousCapacity()).isEqualTo(5L);
        assertThat(p.redisUri()).isEqualTo("redis://r:1");
        assertThat(p.keyPrefix()).isEqualTo("ns:");
        assertThat(p.excludedPaths()).containsExactly("/private");
    }
}
