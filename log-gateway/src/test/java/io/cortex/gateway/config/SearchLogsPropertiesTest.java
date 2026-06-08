package io.cortex.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SearchLogsProperties} default-coercion logic
 * (P9.1b / ADR-0049).
 */
class SearchLogsPropertiesTest {

    @Test
    void nullFieldsCoerceToDocumentedDefaults() {
        final SearchLogsProperties props = new SearchLogsProperties(
                true, null, null, null, null, null, null, null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.serviceId()).isEqualTo(SearchLogsProperties.DEFAULT_SERVICE_ID);
        assertThat(props.requestTimeout()).isEqualTo(SearchLogsProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.defaultMaxHits()).isEqualTo(SearchLogsProperties.DEFAULT_MAX_HITS);
        assertThat(props.maxHitsCeiling()).isEqualTo(SearchLogsProperties.DEFAULT_MAX_HITS_CEILING);
        assertThat(props.subBucketCapacity()).isEqualTo(SearchLogsProperties.DEFAULT_SUB_BUCKET_CAPACITY);
        assertThat(props.subBucketRefillPeriod()).isEqualTo(SearchLogsProperties.DEFAULT_SUB_BUCKET_REFILL);
        assertThat(props.subBucketKeyPrefix()).isEqualTo(SearchLogsProperties.DEFAULT_SUB_BUCKET_KEY_PREFIX);
    }

    @Test
    void blankServiceIdAndKeyPrefixCoerceToDefaults() {
        final SearchLogsProperties props = new SearchLogsProperties(
                true, "  ", Duration.ofSeconds(2), 10, 200, 5L, Duration.ofSeconds(30), "  ");

        assertThat(props.serviceId()).isEqualTo(SearchLogsProperties.DEFAULT_SERVICE_ID);
        assertThat(props.subBucketKeyPrefix()).isEqualTo(SearchLogsProperties.DEFAULT_SUB_BUCKET_KEY_PREFIX);
    }

    @Test
    void zeroOrNegativeTimeoutAndCeilingsCoerceToDefaults() {
        final SearchLogsProperties props = new SearchLogsProperties(
                true, "svc", Duration.ZERO, 0, -1, 5L, Duration.ofSeconds(30), "p:");

        assertThat(props.requestTimeout()).isEqualTo(SearchLogsProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.defaultMaxHits()).isEqualTo(SearchLogsProperties.DEFAULT_MAX_HITS);
        assertThat(props.maxHitsCeiling()).isEqualTo(SearchLogsProperties.DEFAULT_MAX_HITS_CEILING);
    }

    @Test
    void explicitValuesArePreserved() {
        final SearchLogsProperties props = new SearchLogsProperties(
                false, "indexer-x", Duration.ofSeconds(3), 25, 500, 12L,
                Duration.ofSeconds(45), "cortex:rl:s:");

        assertThat(props.enabled()).isFalse();
        assertThat(props.serviceId()).isEqualTo("indexer-x");
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.defaultMaxHits()).isEqualTo(25);
        assertThat(props.maxHitsCeiling()).isEqualTo(500);
        assertThat(props.subBucketCapacity()).isEqualTo(12L);
        assertThat(props.subBucketRefillPeriod()).isEqualTo(Duration.ofSeconds(45));
        assertThat(props.subBucketKeyPrefix()).isEqualTo("cortex:rl:s:");
    }
}
