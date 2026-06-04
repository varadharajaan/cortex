package io.cortex.processor.sink;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SinkProperties} defensive defaults
 * (P5.3 / ADR-0030).
 */
class SinkPropertiesTest {

    /** Null nested blocks coerce to defaulted records. */
    @Test
    void nullNestedBlocksGetDefaulted() {
        final SinkProperties props = new SinkProperties(null, null);

        assertThat(props.loki()).isNotNull();
        assertThat(props.loki().enabled()).isFalse();
        assertThat(props.loki().baseUrl()).isEqualTo(SinkProperties.DEFAULT_LOKI_BASE_URL);
        assertThat(props.loki().requestTimeout())
                .isEqualTo(SinkProperties.DEFAULT_REQUEST_TIMEOUT);

        assertThat(props.quickwit()).isNotNull();
        assertThat(props.quickwit().enabled()).isFalse();
        assertThat(props.quickwit().baseUrl())
                .isEqualTo(SinkProperties.DEFAULT_QUICKWIT_BASE_URL);
        assertThat(props.quickwit().index())
                .isEqualTo(SinkProperties.DEFAULT_QUICKWIT_INDEX);
        assertThat(props.quickwit().requestTimeout())
                .isEqualTo(SinkProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    /** Loki blank base-url falls back to default. */
    @Test
    void lokiBlankBaseUrlFallsBackToDefault() {
        final SinkProperties.Loki loki = new SinkProperties.Loki(true, "  ", null);
        assertThat(loki.baseUrl()).isEqualTo(SinkProperties.DEFAULT_LOKI_BASE_URL);
        assertThat(loki.requestTimeout())
                .isEqualTo(SinkProperties.DEFAULT_REQUEST_TIMEOUT);
    }

    /** Quickwit blank index falls back to default. */
    @Test
    void quickwitBlankIndexFallsBackToDefault() {
        final SinkProperties.Quickwit qw = new SinkProperties.Quickwit(true,
                "http://qw:7280", "", Duration.ofSeconds(7));
        assertThat(qw.index()).isEqualTo(SinkProperties.DEFAULT_QUICKWIT_INDEX);
        assertThat(qw.baseUrl()).isEqualTo("http://qw:7280");
        assertThat(qw.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
    }

    /** Explicit values are preserved end-to-end. */
    @Test
    void explicitValuesArePreserved() {
        final SinkProperties props = new SinkProperties(
                new SinkProperties.Loki(true, "http://loki:3100", Duration.ofSeconds(3)),
                new SinkProperties.Quickwit(true, "http://qw:7280", "events",
                        Duration.ofSeconds(4)));

        assertThat(props.loki().enabled()).isTrue();
        assertThat(props.loki().baseUrl()).isEqualTo("http://loki:3100");
        assertThat(props.loki().requestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.quickwit().enabled()).isTrue();
        assertThat(props.quickwit().baseUrl()).isEqualTo("http://qw:7280");
        assertThat(props.quickwit().index()).isEqualTo("events");
        assertThat(props.quickwit().requestTimeout()).isEqualTo(Duration.ofSeconds(4));
    }
}
