package io.cortex.monitoring.closer;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.ServiceHealthProbe;
import io.cortex.monitoring.slo.CounterFamilySloProperties;
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
 * P8.3 Spring bootstrap proof for the combined
 * {@code eureka-actuator} probe + {@code counter-family} SLO
 * profile.
 *
 * <p>This closer pins the wiring risk introduced by P8.3: the
 * monitoring service can now publish two named {@code RestClient}
 * beans when operators enable the real probe and the counter-family
 * SLO backend together. The test boots that exact profile and
 * asserts both binder gates resolve to the intended beans.</p>
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.MOCK,
        properties = {
                "cortex.monitoring.probe.backend=eureka-actuator",
                "cortex.monitoring.slo.enabled=true",
                "cortex.monitoring.slo.backend=counter-family",
                "cortex.monitoring.slo.evaluation-interval=1h",
                "cortex.monitoring.slo.counter-family.request-timeout=30s",
                "cortex.monitoring.slo.counter-family.actuator-path=/actuator/prometheus",
                "cortex.monitoring.slo.definitions[0].service-id=log-remediation-service",
                "cortex.monitoring.slo.definitions[0].slo-name=slack-dispatch-success",
                "cortex.monitoring.slo.definitions[0].target-success-ratio=0.99",
                "cortex.monitoring.slo.definitions[0].window=PT1H",
                "cortex.monitoring.slo.definitions[0].counter-family.metric-name="
                        + "cortex.remediation.dispatched_total",
                "cortex.monitoring.slo.definitions[0].counter-family.required-tags.channel=slack",
                "cortex.monitoring.slo.definitions[0].counter-family.success-tag-predicate.tag-name=outcome",
                "cortex.monitoring.slo.definitions[0].counter-family.success-tag-predicate.tag-values[0]="
                        + "dispatched",
                "cortex.monitoring.slo.definitions[0].counter-family.failure-tag-predicate.tag-name=outcome",
                "cortex.monitoring.slo.definitions[0].counter-family.failure-tag-predicate.tag-values[0]="
                        + "transient_failure",
                "cortex.monitoring.slo.definitions[0].counter-family.failure-tag-predicate.tag-values[1]="
                        + "permanent_failure",
                "cortex.monitoring.eureka.request-timeout=30s",
                "cortex.monitoring.eureka.actuator-path=/actuator/health",
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
@DisplayName("Monitoring counter-family SLO combined backend boot IT (P8.3)")
class MonitoringCounterFamilySloBootIT {

    @TestConfiguration
    static class StubDiscoveryClientConfig {

        @Bean
        @Primary
        DiscoveryClient stubDiscoveryClient() {
            return new DiscoveryClient() {
                @Override
                public String description() {
                    return "p8-3-counter-family-boot-stub-discovery-client";
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

    private final ServiceHealthProbe probe;
    private final SloBudgetEngine engine;
    private final SloEvaluator evaluator;
    private final SloProperties sloProperties;
    private final CounterFamilySloProperties counterFamilyProperties;

    MonitoringCounterFamilySloBootIT(
            final ServiceHealthProbe probe,
            final SloBudgetEngine engine,
            final SloEvaluator evaluator,
            final SloProperties sloProperties,
            final CounterFamilySloProperties counterFamilyProperties) {
        this.probe = probe;
        this.engine = engine;
        this.evaluator = evaluator;
        this.sloProperties = sloProperties;
        this.counterFamilyProperties = counterFamilyProperties;
    }

    @Test
    @DisplayName("combined profile wires eureka probe and counter-family SLO backend")
    void combinedProfileWiresExpectedBackends() {
        assertThat(this.probe.backendId())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
        assertThat(this.engine.backendId())
                .isEqualTo(SloSnapshot.BACKEND_COUNTER_FAMILY);
        assertThat(this.evaluator).isNotNull();
        assertThat(this.counterFamilyProperties.actuatorPath())
                .isEqualTo("/actuator/prometheus");
    }

    @Test
    @DisplayName("counter-family SLO definition binds nested source shape")
    void counterFamilyDefinitionBindsNestedSource() {
        assertThat(this.sloProperties.definitions()).singleElement()
                .satisfies(def -> {
                    assertThat(def.counterFamily()).isNotNull();
                    assertThat(def.counterFamily().requiredTags())
                            .containsEntry("channel", "slack");
                    assertThat(def.counterFamily().successTagPredicate()
                            .tagValues()).containsExactly("dispatched");
                    assertThat(def.counterFamily().failureTagPredicate()
                            .tagValues()).containsExactly(
                                    "transient_failure", "permanent_failure");
                });
    }
}
