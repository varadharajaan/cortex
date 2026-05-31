package io.cortex.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NlQueryProperties}: verifies the canonical
 * constructor's null-safe defaults so a partially specified yaml block
 * (e.g. {@code cortex.gateway.nl-query.enabled=false} in test resources)
 * still yields a usable record without NPE on the bucket configuration
 * bean.
 */
class NlQueryPropertiesTest {

    /** Null / blank {@code model} falls back to the documented constant. */
    @Test
    void modelDefaultsToConstantWhenBlank() {
        final NlQueryProperties nullModel = new NlQueryProperties(
                true, null, null, null, null, null, null, null);
        final NlQueryProperties blankModel = new NlQueryProperties(
                true, "   ", null, null, null, null, null, null);

        assertThat(nullModel.model()).isEqualTo(NlQueryProperties.DEFAULT_MODEL);
        assertThat(blankModel.model()).isEqualTo(NlQueryProperties.DEFAULT_MODEL);
    }

    /** Null numeric defaults match the documented constants. */
    @Test
    void numericDefaultsApplyWhenNull() {
        final NlQueryProperties p = new NlQueryProperties(
                true, "mistral", null, null, null, null, null, null);

        assertThat(p.temperature()).isEqualTo(NlQueryProperties.DEFAULT_TEMPERATURE);
        assertThat(p.maxTokens()).isEqualTo(NlQueryProperties.DEFAULT_MAX_TOKENS);
        assertThat(p.confidenceFloor()).isEqualTo(NlQueryProperties.DEFAULT_CONFIDENCE_FLOOR);
        assertThat(p.subBucketCapacity()).isEqualTo(NlQueryProperties.DEFAULT_SUB_BUCKET_CAPACITY);
        assertThat(p.subBucketRefillPeriod()).isEqualTo(NlQueryProperties.DEFAULT_SUB_BUCKET_REFILL);
    }

    /** Null / blank {@code subBucketKeyPrefix} falls back to the documented constant. */
    @Test
    void subBucketKeyPrefixDefaultsWhenBlank() {
        final NlQueryProperties nullPrefix = new NlQueryProperties(
                true, "mistral", 0.1, 64, 0.5, 5L, Duration.ofSeconds(30), null);
        final NlQueryProperties blankPrefix = new NlQueryProperties(
                true, "mistral", 0.1, 64, 0.5, 5L, Duration.ofSeconds(30), "  ");

        assertThat(nullPrefix.subBucketKeyPrefix()).isEqualTo(NlQueryProperties.DEFAULT_SUB_BUCKET_KEY_PREFIX);
        assertThat(blankPrefix.subBucketKeyPrefix()).isEqualTo(NlQueryProperties.DEFAULT_SUB_BUCKET_KEY_PREFIX);
    }

    /** Explicitly supplied values pass through unchanged. */
    @Test
    void explicitValuesPassThrough() {
        final NlQueryProperties p = new NlQueryProperties(
                true, "llama3.2", 0.7, 1024, 0.5,
                25L, Duration.ofSeconds(15), "ns:");

        assertThat(p.enabled()).isTrue();
        assertThat(p.model()).isEqualTo("llama3.2");
        assertThat(p.temperature()).isEqualTo(0.7);
        assertThat(p.maxTokens()).isEqualTo(1024);
        assertThat(p.confidenceFloor()).isEqualTo(0.5);
        assertThat(p.subBucketCapacity()).isEqualTo(25L);
        assertThat(p.subBucketRefillPeriod()).isEqualTo(Duration.ofSeconds(15));
        assertThat(p.subBucketKeyPrefix()).isEqualTo("ns:");
    }
}
