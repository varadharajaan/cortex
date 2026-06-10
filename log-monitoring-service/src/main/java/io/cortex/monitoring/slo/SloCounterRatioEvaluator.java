package io.cortex.monitoring.slo;

import io.cortex.monitoring.slo.PrometheusCounterFamilyParser.Sample;

/**
 * Shared Prometheus counter-family success/failure classifier used
 * by P8.3 counter-family and P8.7 OTel span-metric backends.
 */
final class SloCounterRatioEvaluator {

    private SloCounterRatioEvaluator() {
    }

    static SloSnapshot evaluate(final String backend,
                                final SloDefinition def,
                                final CounterRatioSource source,
                                final String body) {
        double successes = 0.0d;
        double failures = 0.0d;
        for (final Sample sample
                : PrometheusCounterFamilyParser.parse(body, source.metricName())) {
            if (sample.value() < 0.0d
                    || !PrometheusTargetScrapeSupport.requiredTagsMatch(
                    source.requiredTags(), sample.tags())) {
                continue;
            }
            if (source.success().matches(sample.tags())) {
                successes += sample.value();
            } else if (source.failure().matches(sample.tags())) {
                failures += sample.value();
            }
        }
        return SloBudgetMath.snapshotFromCounts(backend, def,
                successes, failures);
    }

    record CounterRatioSource(
            String metricName,
            SloDefinition.TagPredicate success,
            SloDefinition.TagPredicate failure,
            java.util.Map<String, String> requiredTags) {
    }
}
