package io.cortex.monitoring.slo;

import io.cortex.monitoring.slo.PrometheusCounterFamilyParser.Sample;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * P8.4 backend that evaluates latency SLOs from Micrometer timer
 * Prometheus histogram buckets exposed by the target service.
 */
@Component
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'timer-percentile'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
public final class TimerPercentileSloBudgetEngine implements SloBudgetEngine {

    private static final String LE_TAG = "le";
    private static final String INFINITE_BUCKET = "+Inf";
    private static final String BUCKET_SUFFIX = "_bucket";

    private final DiscoveryClient discoveryClient;
    private final RestClient restClient;
    private final TimerPercentileSloProperties properties;

    /**
     * Spring constructor.
     *
     * @param discoveryClient Spring Cloud discovery client
     * @param restClient named P8.4 scrape client
     * @param properties timer-percentile scrape properties
     */
    public TimerPercentileSloBudgetEngine(
            final DiscoveryClient discoveryClient,
            @Qualifier("timerPercentileSloRestClient")
            final RestClient restClient,
            final TimerPercentileSloProperties properties) {
        this.discoveryClient = discoveryClient;
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String backendId() {
        return SloSnapshot.BACKEND_TIMER_PERCENTILE;
    }

    @Override
    public boolean supports(final SloDefinition def) {
        return def != null && def.timer() != null;
    }

    @Override
    public SloSnapshot evaluate(final SloDefinition def) {
        try {
            if (def == null || def.timer() == null) {
                return SloSnapshot.permanentFailure(backendId(), def,
                        backendId() + ":missing-timer-source");
            }
            final ServiceInstance instance =
                    PrometheusTargetScrapeSupport.selectInstance(
                            this.discoveryClient, def);
            if (instance == null) {
                return SloSnapshot.transientFailure(backendId(), def,
                        backendId() + ":no-instance");
            }
            final String body = PrometheusTargetScrapeSupport.scrape(
                    this.restClient,
                    PrometheusTargetScrapeSupport.buildUri(instance,
                            this.properties.actuatorPath()));
            return evaluateBody(def, body);
        } catch (final RestClientResponseException ex) {
            return SloRemoteFailureClassifier.classifyHttp(
                    backendId(), def, ex);
        } catch (final ResourceAccessException ex) {
            return SloRemoteFailureClassifier.classifyTransport(
                    backendId(), def, ex);
        } catch (final RuntimeException ex) {
            return SloSnapshot.transientFailure(backendId(), def,
                    backendId() + ":exception:" + ex.getClass().getSimpleName());
        }
    }

    private SloSnapshot evaluateBody(final SloDefinition def,
                                     final String body) {
        final SloDefinition.TimerSource source = def.timer();
        final double thresholdSeconds =
                source.threshold().toNanos() / 1_000_000_000.0d;
        final Map<Map<String, String>, HistogramBuckets> groups =
                new HashMap<>();
        for (final Sample sample : PrometheusCounterFamilyParser.parse(
                body, bucketMetricName(source.metricName()))) {
            if (sample.value() < 0.0d
                    || !PrometheusTargetScrapeSupport.requiredTagsMatch(
                    source.requiredTags(), sample.tags())) {
                continue;
            }
            final String le = sample.tags().get(LE_TAG);
            if (le == null) {
                continue;
            }
            final Map<String, String> key = tagsWithoutLe(sample.tags());
            final HistogramBuckets buckets = groups.computeIfAbsent(
                    key, ignored -> new HistogramBuckets());
            if (INFINITE_BUCKET.equals(le)) {
                buckets.total = Math.max(buckets.total, sample.value());
                continue;
            }
            final Double upperBound = parseLe(le);
            if (upperBound != null && upperBound <= thresholdSeconds
                    && upperBound >= buckets.bestSuccessLe) {
                buckets.bestSuccessLe = upperBound;
                buckets.successes = sample.value();
            }
        }
        double successes = 0.0d;
        double failures = 0.0d;
        for (final HistogramBuckets buckets : groups.values()) {
            if (buckets.total <= 0.0d) {
                continue;
            }
            final double cappedSuccesses =
                    Math.max(0.0d, Math.min(buckets.successes, buckets.total));
            successes += cappedSuccesses;
            failures += buckets.total - cappedSuccesses;
        }
        return SloBudgetMath.snapshotFromCounts(backendId(), def,
                successes, failures);
    }

    private static String bucketMetricName(final String metricName) {
        if (metricName.endsWith(BUCKET_SUFFIX)) {
            return metricName;
        }
        return metricName + BUCKET_SUFFIX;
    }

    private static Map<String, String> tagsWithoutLe(
            final Map<String, String> tags) {
        final Map<String, String> copy = new LinkedHashMap<>(tags);
        copy.remove(LE_TAG);
        return Map.copyOf(copy);
    }

    private static Double parseLe(final String value) {
        try {
            return Double.valueOf(value);
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private static final class HistogramBuckets {
        private double bestSuccessLe = Double.NEGATIVE_INFINITY;
        private double successes;
        private double total;
    }
}
