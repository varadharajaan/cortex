package io.cortex.monitoring.slo;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * P8.7+ backend for span-derived OTel counter ratios exposed in
 * Prometheus text format.
 */
@Component
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'otel'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
public final class OtelSloBudgetEngine implements SloBudgetEngine {

    private final DiscoveryClient discoveryClient;
    private final RestClient restClient;
    private final OtelSloProperties properties;

    /**
     * Spring constructor.
     *
     * @param discoveryClient Spring Cloud discovery client
     * @param restClient named P8.7 scrape client
     * @param properties OTel scrape properties
     */
    public OtelSloBudgetEngine(
            final DiscoveryClient discoveryClient,
            @Qualifier("otelSloRestClient") final RestClient restClient,
            final OtelSloProperties properties) {
        this.discoveryClient = discoveryClient;
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String backendId() {
        return SloSnapshot.BACKEND_OTEL;
    }

    @Override
    public boolean supports(final SloDefinition def) {
        return def != null && def.otel() != null;
    }

    @Override
    public SloSnapshot evaluate(final SloDefinition def) {
        try {
            if (def == null || def.otel() == null) {
                return SloSnapshot.permanentFailure(backendId(), def,
                        backendId() + ":missing-otel-source");
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
        final SloDefinition.OtelSource source = def.otel();
        return SloCounterRatioEvaluator.evaluate(
                backendId(), def,
                new SloCounterRatioEvaluator.CounterRatioSource(
                source.metricName(),
                source.successTagPredicate(),
                source.failureTagPredicate(),
                source.requiredTags()),
                body);
    }
}
