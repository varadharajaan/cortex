package io.cortex.monitoring.slo;

import io.cortex.monitoring.slo.PrometheusCounterFamilyParser.Sample;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PrometheusCounterFamilyParser} (P8.3).
 */
class PrometheusCounterFamilyParserTest {

    @Test
    void parsesMatchingCounterSamplesAndSkipsComments() {
        final String body = """
                # HELP cortex_remediation_dispatched_total Dispatches
                # TYPE cortex_remediation_dispatched_total counter
                cortex_remediation_dispatched_total{channel="slack",outcome="dispatched"} 12.0
                cortex_other_total{outcome="dispatched"} 99.0
                """;

        final List<Sample> samples = PrometheusCounterFamilyParser.parse(
                body, "cortex.remediation.dispatched_total");

        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).metricName())
                .isEqualTo("cortex_remediation_dispatched_total");
        assertThat(samples.get(0).tags())
                .containsEntry("channel", "slack")
                .containsEntry("outcome", "dispatched");
        assertThat(samples.get(0).value()).isEqualTo(12.0d);
    }

    @Test
    void parsesSamplesWithoutTags() {
        final List<Sample> samples = PrometheusCounterFamilyParser.parse(
                "cortex_processor_events_consumed_total 3.0 12345",
                "cortex_processor_events_consumed_total");

        assertThat(samples).singleElement().satisfies(sample -> {
            assertThat(sample.tags()).isEmpty();
            assertThat(sample.value()).isEqualTo(3.0d);
        });
    }

    @Test
    void parsesEscapedTagValues() {
        final String body = """
                cortex_remediation_dispatched_total{channel="slack\\\"primary",outcome="dispatched"} 1
                """;

        final List<Sample> samples = PrometheusCounterFamilyParser.parse(
                body, "cortex_remediation_dispatched_total");

        assertThat(samples).singleElement().satisfies(sample ->
                assertThat(sample.tags())
                        .containsEntry("channel", "slack\"primary"));
    }

    @Test
    void invalidLinesReturnNoSamples() {
        final String body = """
                no-value
                cortex_remediation_dispatched_total{bad} 1
                cortex_remediation_dispatched_total{outcome="dispatched"} NaN
                cortex_remediation_dispatched_total{outcome="dispatched"} nope
                """;

        final List<Sample> samples = PrometheusCounterFamilyParser.parse(
                body, "cortex_remediation_dispatched_total");

        assertThat(samples).isEmpty();
    }
}
