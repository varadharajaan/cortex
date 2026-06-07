package io.cortex.indexer.admin.quickwit;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QuickwitProperties} (P7.1 / ADR-0039 D2).
 *
 * <p>Pins the defensive defaults so a single missing env-var
 * doesn't crash the boot.</p>
 */
@DisplayName("QuickwitProperties")
class QuickwitPropertiesTest {

    /** Sample baseUrl distinct from the default, for the happy-path case. */
    private static final String SAMPLE_BASE_URL = "http://quickwit.svc:7280";

    /** Sample timeout distinct from the default, for the happy-path case. */
    private static final Duration SAMPLE_TIMEOUT = Duration.ofSeconds(11);

    /** Sample doc-mapping version distinct from the default. */
    private static final String SAMPLE_DOC_MAPPING_VERSION = "v3";

    @Test
    void happyPathPreservesEveryField() {
        final QuickwitProperties props = new QuickwitProperties(
                SAMPLE_BASE_URL, SAMPLE_TIMEOUT, SAMPLE_DOC_MAPPING_VERSION);

        assertThat(props.baseUrl()).isEqualTo(SAMPLE_BASE_URL);
        assertThat(props.requestTimeout()).isEqualTo(SAMPLE_TIMEOUT);
        assertThat(props.docMappingVersion())
                .isEqualTo(SAMPLE_DOC_MAPPING_VERSION);
    }

    @Test
    void nullBaseUrlCoercesToDefault() {
        final QuickwitProperties props = new QuickwitProperties(
                null, SAMPLE_TIMEOUT, SAMPLE_DOC_MAPPING_VERSION);
        assertThat(props.baseUrl())
                .isEqualTo(QuickwitProperties.DEFAULT_BASE_URL);
    }

    @Test
    void blankBaseUrlCoercesToDefault() {
        final QuickwitProperties props = new QuickwitProperties(
                "  ", SAMPLE_TIMEOUT, SAMPLE_DOC_MAPPING_VERSION);
        assertThat(props.baseUrl())
                .isEqualTo(QuickwitProperties.DEFAULT_BASE_URL);
    }

    @Test
    void nullRequestTimeoutCoercesToDefault() {
        final QuickwitProperties props = new QuickwitProperties(
                SAMPLE_BASE_URL, null, SAMPLE_DOC_MAPPING_VERSION);
        assertThat(props.requestTimeout())
                .isEqualTo(QuickwitProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    void zeroRequestTimeoutCoercesToDefault() {
        final QuickwitProperties props = new QuickwitProperties(
                SAMPLE_BASE_URL, Duration.ZERO, SAMPLE_DOC_MAPPING_VERSION);
        assertThat(props.requestTimeout())
                .isEqualTo(QuickwitProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    void negativeRequestTimeoutCoercesToDefault() {
        final QuickwitProperties props = new QuickwitProperties(
                SAMPLE_BASE_URL, Duration.ofSeconds(-3),
                SAMPLE_DOC_MAPPING_VERSION);
        assertThat(props.requestTimeout())
                .isEqualTo(QuickwitProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    @Test
    void nullDocMappingVersionCoercesToDefault() {
        final QuickwitProperties props = new QuickwitProperties(
                SAMPLE_BASE_URL, SAMPLE_TIMEOUT, null);
        assertThat(props.docMappingVersion())
                .isEqualTo(QuickwitProperties.DEFAULT_DOC_MAPPING_VERSION);
    }

    @Test
    void blankDocMappingVersionCoercesToDefault() {
        final QuickwitProperties props = new QuickwitProperties(
                SAMPLE_BASE_URL, SAMPLE_TIMEOUT, "\t");
        assertThat(props.docMappingVersion())
                .isEqualTo(QuickwitProperties.DEFAULT_DOC_MAPPING_VERSION);
    }

    @Test
    void allDefaultsCanCoexist() {
        final QuickwitProperties props =
                new QuickwitProperties(null, null, null);
        assertThat(props.baseUrl())
                .isEqualTo(QuickwitProperties.DEFAULT_BASE_URL);
        assertThat(props.requestTimeout())
                .isEqualTo(QuickwitProperties.DEFAULT_REQUEST_TIMEOUT);
        assertThat(props.docMappingVersion())
                .isEqualTo(QuickwitProperties.DEFAULT_DOC_MAPPING_VERSION);
    }
}
