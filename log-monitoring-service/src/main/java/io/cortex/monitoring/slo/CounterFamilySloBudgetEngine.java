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
 * P8.3 {@link SloBudgetEngine} that evaluates a generic counter
 * family from a target service's actuator Prometheus exposition.
 *
 * <p>The backend discovers the target service via
 * {@link SloDefinition#serviceId()}, scrapes the first registered
 * instance's {@code /actuator/prometheus}, parses one configured
 * counter family, and classifies samples using the definition's
 * success/failure tag predicates. It is useful for ratio SLIs
 * such as remediation dispatch success
 * ({@code outcome=dispatched} vs
 * {@code outcome=transient_failure|permanent_failure}) without
 * waiting for the P8.5 central PromQL backend.</p>
 *
 * <p>MUST NOT throw. Misconfiguration returns
 * {@code permanent_failure}; remote scrape failures return
 * {@code transient_failure}; no matching counter samples returns
 * {@code unknown} with all-clear gauge defaults.</p>
 */
@Component
@ConditionalOnExpression(
        "'${cortex.monitoring.slo.backend:noop}' == 'counter-family'"
                + " || '${cortex.monitoring.slo.backend:noop}' == 'mixed'")
public final class CounterFamilySloBudgetEngine implements SloBudgetEngine {

    private final DiscoveryClient discoveryClient;
    private final RestClient restClient;
    private final CounterFamilySloProperties properties;

    /**
     * Spring constructor.
     *
     * @param discoveryClient Spring Cloud discovery client used to
     *                        resolve {@link SloDefinition#serviceId()}
     * @param restClient      named P8.3 scrape client
     * @param properties      counter-family scrape properties
     */
    public CounterFamilySloBudgetEngine(
            final DiscoveryClient discoveryClient,
            @Qualifier("counterFamilySloRestClient") final RestClient restClient,
            final CounterFamilySloProperties properties) {
        this.discoveryClient = discoveryClient;
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String backendId() {
        return SloSnapshot.BACKEND_COUNTER_FAMILY;
    }

    @Override
    public boolean supports(final SloDefinition def) {
        return def != null && def.counterFamily() != null;
    }

    @Override
    public SloSnapshot evaluate(final SloDefinition def) {
        try {
            if (def == null || def.counterFamily() == null) {
                return SloSnapshot.permanentFailure(backendId(), def,
                        backendId() + ":missing-counter-family-source");
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
        final SloDefinition.CounterFamilySource source = def.counterFamily();
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
