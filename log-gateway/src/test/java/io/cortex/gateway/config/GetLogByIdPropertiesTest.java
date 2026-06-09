package io.cortex.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GetLogByIdProperties} default-coercion logic
 * (P9.2b / ADR-0049).
 */
class GetLogByIdPropertiesTest {

    @Test
    void nullFieldsCoerceToDocumentedDefaults() {
        final GetLogByIdProperties props = new GetLogByIdProperties(
                true, null, null, null, null, null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.serviceId()).isEqualTo(GetLogByIdProperties.DEFAULT_SERVICE_ID);
        assertThat(props.requestTimeout()).isEqualTo(GetLogByIdProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.subBucketCapacity()).isEqualTo(GetLogByIdProperties.DEFAULT_SUB_BUCKET_CAPACITY);
        assertThat(props.subBucketRefillPeriod()).isEqualTo(GetLogByIdProperties.DEFAULT_SUB_BUCKET_REFILL);
        assertThat(props.subBucketKeyPrefix()).isEqualTo(GetLogByIdProperties.DEFAULT_SUB_BUCKET_KEY_PREFIX);
    }

    @Test
    void blankServiceIdAndKeyPrefixCoerceToDefaults() {
        final GetLogByIdProperties props = new GetLogByIdProperties(
                true, "  ", Duration.ofSeconds(2), 5L, Duration.ofSeconds(30), "  ");

        assertThat(props.serviceId()).isEqualTo(GetLogByIdProperties.DEFAULT_SERVICE_ID);
        assertThat(props.subBucketKeyPrefix()).isEqualTo(GetLogByIdProperties.DEFAULT_SUB_BUCKET_KEY_PREFIX);
    }

    @Test
    void zeroOrNegativeTimeoutCoercesToDefault() {
        final GetLogByIdProperties props = new GetLogByIdProperties(
                true, "svc", Duration.ZERO, 5L, Duration.ofSeconds(30), "p:");

        assertThat(props.requestTimeout()).isEqualTo(GetLogByIdProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    void explicitValuesArePreserved() {
        final GetLogByIdProperties props = new GetLogByIdProperties(
                false, "ingest-x", Duration.ofSeconds(3), 12L, Duration.ofSeconds(45), "cortex:rl:g:");

        assertThat(props.enabled()).isFalse();
        assertThat(props.serviceId()).isEqualTo("ingest-x");
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.subBucketCapacity()).isEqualTo(12L);
        assertThat(props.subBucketRefillPeriod()).isEqualTo(Duration.ofSeconds(45));
        assertThat(props.subBucketKeyPrefix()).isEqualTo("cortex:rl:g:");
    }
}
