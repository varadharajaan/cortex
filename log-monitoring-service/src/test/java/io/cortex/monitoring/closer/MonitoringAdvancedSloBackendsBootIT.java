package io.cortex.monitoring.closer;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.monitoring.slo.SloBudgetEngine;
import io.cortex.monitoring.slo.SloEvaluator;
import io.cortex.monitoring.slo.SloProperties;
import io.cortex.monitoring.slo.SloSnapshot;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestConstructor;

/**
 * Spring bootstrap proof for the P8.4-P8.7 advanced SLO backend
 * binder surface.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.MOCK,
        properties = {
                "cortex.monitoring.slo.enabled=true",
                "cortex.monitoring.slo.backend=mixed",
                "cortex.monitoring.slo.evaluation-interval=1h",
                "cortex.monitoring.slo.counter-family.request-timeout=30s",
                "cortex.monitoring.slo.timer-percentile.request-timeout=30s",
                "cortex.monitoring.slo.promql.request-timeout=30s",
                "cortex.monitoring.slo.promql.base-url=http://localhost:9090",
                "cortex.monitoring.slo.otel.request-timeout=30s",
                "cortex.monitoring.slo.definitions[0].service-id=log-gateway",
                "cortex.monitoring.slo.definitions[0].slo-name=availability",
                "cortex.monitoring.slo.definitions[0].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[0].window=PT1H",
                "cortex.monitoring.slo.definitions[1].service-id=log-remediation-service",
                "cortex.monitoring.slo.definitions[1].slo-name=slack-dispatch-success",
                "cortex.monitoring.slo.definitions[1].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[1].window=PT1H",
                "cortex.monitoring.slo.definitions[1].counter-family.metric-name="
                        + "cortex.remediation.dispatched_total",
                "cortex.monitoring.slo.definitions[1].counter-family.required-tags.channel=slack",
                "cortex.monitoring.slo.definitions[1].counter-family.success-tag-predicate.tag-name=outcome",
                "cortex.monitoring.slo.definitions[1].counter-family.success-tag-predicate.tag-values[0]="
                        + "dispatched",
                "cortex.monitoring.slo.definitions[1].counter-family.failure-tag-predicate.tag-name=outcome",
                "cortex.monitoring.slo.definitions[1].counter-family.failure-tag-predicate.tag-values[0]="
                        + "transient_failure",
                "cortex.monitoring.slo.definitions[2].service-id=log-gateway",
                "cortex.monitoring.slo.definitions[2].slo-name=gateway-route-latency-p95",
                "cortex.monitoring.slo.definitions[2].target-success-ratio=0.95",
                "cortex.monitoring.slo.definitions[2].window=PT5M",
                "cortex.monitoring.slo.definitions[2].timer.metric-name="
                        + "http.server.requests.seconds",
                "cortex.monitoring.slo.definitions[2].timer.threshold=300ms",
                "cortex.monitoring.slo.definitions[2].timer.required-tags.uri=/api/v1/logs",
                "cortex.monitoring.slo.definitions[3].service-id=log-indexer-service",
                "cortex.monitoring.slo.definitions[3].slo-name=quickwit-search-success",
                "cortex.monitoring.slo.definitions[3].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[3].window=PT5M",
                "cortex.monitoring.slo.definitions[3].prom-ql.success-query="
                        + "sum(success_total)",
                "cortex.monitoring.slo.definitions[3].prom-ql.failure-query="
                        + "sum(failure_total)",
                "cortex.monitoring.slo.definitions[4].service-id=log-gateway",
                "cortex.monitoring.slo.definitions[4].slo-name=server-span-success",
                "cortex.monitoring.slo.definitions[4].target-success-ratio=0.98",
                "cortex.monitoring.slo.definitions[4].window=PT5M",
                "cortex.monitoring.slo.definitions[4].otel.metric-name="
                        + "otel.span.server.requests_total",
                "cortex.monitoring.slo.definitions[4].otel.required-tags.span-kind=server",
                "cortex.monitoring.slo.definitions[4].otel.success-tag-predicate.tag-name=status_code",
                "cortex.monitoring.slo.definitions[4].otel.success-tag-predicate.tag-values[0]=OK",
                "cortex.monitoring.slo.definitions[4].otel.failure-tag-predicate.tag-name=status_code",
                "cortex.monitoring.slo.definitions[4].otel.failure-tag-predicate.tag-values[0]=ERROR",
                "cortex.monitoring.slo.definitions[5].service-id=cortex-system",
                "cortex.monitoring.slo.definitions[5].slo-name=system-availability",
                "cortex.monitoring.slo.definitions[5].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[5].window=PT5M",
                "cortex.monitoring.slo.definitions[5].composite.mode=worst-of",
                "cortex.monitoring.slo.definitions[5].composite.components[0].service-id=log-gateway",
                "cortex.monitoring.slo.definitions[5].composite.components[0].slo-name=availability",
                "cortex.monitoring.slo.definitions[5].composite.components[0].weight=1.0",
                "cortex.monitoring.slo.definitions[5].composite.components[1].service-id=log-remediation-service",
                "cortex.monitoring.slo.definitions[5].composite.components[1].slo-name=slack-dispatch-success",
                "cortex.monitoring.slo.definitions[5].composite.components[1].weight=1.0",
                "eureka.client.enabled=false",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.cloud.netflix.eureka."
                        + "EurekaClientAutoConfiguration,"
                        + "org.springframework.cloud.client.discovery.composite."
                        + "CompositeDiscoveryClientAutoConfiguration,"
                        + "org.springframework.cloud.client.discovery.simple."
                        + "SimpleDiscoveryClientAutoConfiguration"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("Monitoring advanced SLO backend mixed-mode boot IT")
class MonitoringAdvancedSloBackendsBootIT {

    @TestConfiguration
    static class StubDiscoveryClientConfig {

        @Bean
        @Primary
        DiscoveryClient stubDiscoveryClient() {
            return new DiscoveryClient() {
                @Override
                public String description() {
                    return "p8-4-through-p8-7-boot-stub-discovery-client";
                }

                @Override
                public List<ServiceInstance> getInstances(final String serviceId) {
                    return List.of();
                }

                @Override
                public List<String> getServices() {
                    return List.of();
                }
            };
        }
    }

    private final List<SloBudgetEngine> engines;
    private final SloEvaluator evaluator;
    private final SloProperties sloProperties;

    MonitoringAdvancedSloBackendsBootIT(
            final List<SloBudgetEngine> sloEngines,
            final SloEvaluator sloEvaluator,
            final SloProperties properties) {
        this.engines = sloEngines;
        this.evaluator = sloEvaluator;
        this.sloProperties = properties;
    }

    @Test
    @DisplayName("mixed mode wires every advanced SLO backend")
    void mixedModeWiresEveryAdvancedSloBackend() {
        assertThat(this.evaluator).isNotNull();
        assertThat(this.engines).extracting(SloBudgetEngine::backendId)
                .containsExactlyInAnyOrder(
                        SloSnapshot.BACKEND_MICROMETER_DERIVATION,
                        SloSnapshot.BACKEND_COUNTER_FAMILY,
                        SloSnapshot.BACKEND_TIMER_PERCENTILE,
                        SloSnapshot.BACKEND_PROMQL,
                        SloSnapshot.BACKEND_OTEL,
                        SloSnapshot.BACKEND_COMPOSITE);
    }

    @Test
    @DisplayName("mixed mode binds each nested SLO source shape")
    void mixedModeBindsEveryNestedSourceShape() {
        assertThat(this.sloProperties.definitions()).hasSize(6);
        assertThat(def("slack-dispatch-success").counterFamily()).isNotNull();
        assertThat(def("gateway-route-latency-p95").timer()).isNotNull();
        assertThat(def("quickwit-search-success").promQl()).isNotNull();
        assertThat(def("server-span-success").otel()).isNotNull();
        assertThat(def("system-availability").composite()).isNotNull();
    }

    @Test
    @DisplayName("source-aware engines only support their owned definitions")
    void sourceAwareEnginesOnlySupportOwnedDefinitions() {
        assertThat(engine(SloSnapshot.BACKEND_TIMER_PERCENTILE)
                .supports(def("gateway-route-latency-p95"))).isTrue();
        assertThat(engine(SloSnapshot.BACKEND_TIMER_PERCENTILE)
                .supports(def("slack-dispatch-success"))).isFalse();
        assertThat(engine(SloSnapshot.BACKEND_COMPOSITE)
                .supports(def("system-availability"))).isTrue();
        assertThat(engine(SloSnapshot.BACKEND_MICROMETER_DERIVATION)
                .supports(def("availability"))).isTrue();
    }

    private SloBudgetEngine engine(final String backend) {
        return this.engines.stream()
                .filter(candidate -> backend.equals(candidate.backendId()))
                .findFirst()
                .orElseThrow();
    }

    private io.cortex.monitoring.slo.SloDefinition def(final String sloName) {
        return this.sloProperties.definitions().stream()
                .filter(candidate -> sloName.equals(candidate.sloName()))
                .findFirst()
                .orElseThrow();
    }
}
