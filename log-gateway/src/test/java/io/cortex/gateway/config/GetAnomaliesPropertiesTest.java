package io.cortex.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GetAnomaliesProperties} default-coercion logic
 * (P9.3b / ADR-0049).
 */
class GetAnomaliesPropertiesTest {

    @Test
    void nullFieldsCoerceToDocumentedDefaults() {
        final GetAnomaliesProperties props = new GetAnomaliesProperties(
                true, null, null, null, null, null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.serviceId()).isEqualTo(GetAnomaliesProperties.DEFAULT_SERVICE_ID);
        assertThat(props.requestTimeout()).isEqualTo(GetAnomaliesProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.subBucketCapacity()).isEqualTo(GetAnomaliesProperties.DEFAULT_SUB_BUCKET_CAPACITY);
        assertThat(props.subBucketRefillPeriod()).isEqualTo(GetAnomaliesProperties.DEFAULT_SUB_BUCKET_REFILL);
        assertThat(props.subBucketKeyPrefix()).isEqualTo(GetAnomaliesProperties.DEFAULT_SUB_BUCKET_KEY_PREFIX);
    }

    @Test
    void blankServiceIdAndKeyPrefixCoerceToDefaults() {
        final GetAnomaliesProperties props = new GetAnomaliesProperties(
                true, "  ", Duration.ofSeconds(2), 5L, Duration.ofSeconds(30), "  ");

        assertThat(props.serviceId()).isEqualTo(GetAnomaliesProperties.DEFAULT_SERVICE_ID);
        assertThat(props.subBucketKeyPrefix()).isEqualTo(GetAnomaliesProperties.DEFAULT_SUB_BUCKET_KEY_PREFIX);
    }

    @Test
    void zeroOrNegativeTimeoutCoercesToDefault() {
        final GetAnomaliesProperties props = new GetAnomaliesProperties(
                true, "svc", Duration.ZERO, 5L, Duration.ofSeconds(30), "p:");

        assertThat(props.requestTimeout()).isEqualTo(GetAnomaliesProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    void explicitValuesArePreserved() {
        final GetAnomaliesProperties props = new GetAnomaliesProperties(
                false, "remediation-x", Duration.ofSeconds(3), 12L, Duration.ofSeconds(45), "cortex:rl:a:");

        assertThat(props.enabled()).isFalse();
        assertThat(props.serviceId()).isEqualTo("remediation-x");
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.subBucketCapacity()).isEqualTo(12L);
        assertThat(props.subBucketRefillPeriod()).isEqualTo(Duration.ofSeconds(45));
        assertThat(props.subBucketKeyPrefix()).isEqualTo("cortex:rl:a:");
    }
}
