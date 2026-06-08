package io.cortex.monitoring.probe;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link ProbeProperties} into the Spring context (P8.2b /
 * ADR-0046 Amendment 3 2026-06-08) and adapts the
 * operator-friendly {@code Duration} cadence into the
 * {@code Long} (millis) form Spring's
 * {@code @Scheduled(fixedRateString=...)} processor accepts.
 *
 * <p>Mirror of {@link io.cortex.monitoring.slo.SloEngineConfig}.
 * Records bound to {@code @ConfigurationProperties} must be
 * enabled via either {@code @ConfigurationPropertiesScan} or
 * {@code @EnableConfigurationProperties}; this module uses the
 * latter so the binding is colocated with the package that owns
 * the property class (locality of behaviour).</p>
 *
 * <p>Unlike the binder-gate-conditional
 * {@link io.cortex.monitoring.probe.eureka.EurekaActuatorHttpConfig},
 * this class is NOT gated by a {@code @ConditionalOnProperty}:
 * the {@link ProbeProperties} bean must be available whether or
 * not the {@link ScheduledProbeEvaluator} fires (the noop probe
 * still reads the {@code backend} field for symmetry, and the
 * properties bean must exist for the SpEL adapter to resolve at
 * scheduler post-processing time). The gating happens on
 * {@link ScheduledProbeEvaluator} itself
 * ({@code cortex.monitoring.probe.enabled=true}).</p>
 *
 * <p>The Long adapter bean is the LD141 standing pattern: NEVER
 * write {@code @Scheduled(fixedRateString = "${prop:30s}")}
 * because Spring's
 * {@code ScheduledAnnotationBeanPostProcessor} resolves the
 * string via {@code Long.parseLong} with no
 * {@code Duration.parse} fallback in this Boot version --
 * operator-friendly values such as {@code 30s} / {@code 1h} fail
 * with {@code NumberFormatException} at bean-creation time.
 * Routing through this bean (referenced via the SpEL string
 * {@code "#{@probeEvaluationIntervalMillis}"} in
 * {@link ScheduledProbeEvaluator}) lets
 * {@code application.yml} keep the typed {@code Duration}
 * contract while {@code @Scheduled} reads the only form it
 * actually understands. The bean name is pinned via
 * {@link #PROBE_EVALUATION_INTERVAL_MILLIS_BEAN} so the SpEL
 * literal and the registered bean cannot drift independently
 * (typo on either side degrades to a bean-creation failure at
 * boot rather than silent breakage).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProbeProperties.class)
public class ProbeSchedulerConfig {

    /**
     * Bean name referenced from
     * {@link ScheduledProbeEvaluator#evaluateAll()}'s
     * {@code @Scheduled(fixedRateString="#{@<name>}")} SpEL
     * expression. Kept as a public constant so the SpEL string
     * literal in the {@code @Scheduled} annotation and the bean
     * registered here cannot drift independently.
     */
    public static final String PROBE_EVALUATION_INTERVAL_MILLIS_BEAN =
            "probeEvaluationIntervalMillis";

    /**
     * Adapts {@link ProbeProperties#evaluationInterval()} (a
     * {@link java.time.Duration}) into the long-as-string value
     * Spring's {@code ScheduledAnnotationBeanPostProcessor}
     * accepts for {@code fixedRateString} (LD141).
     *
     * @param properties typed config bean already published by
     *                   {@code @EnableConfigurationProperties}
     * @return scheduler cadence in milliseconds; always positive
     *         because the compact ctor on {@link ProbeProperties}
     *         clamps zero / negative / null to
     *         {@link ProbeProperties#DEFAULT_EVALUATION_INTERVAL}
     */
    @Bean(name = PROBE_EVALUATION_INTERVAL_MILLIS_BEAN)
    public Long probeEvaluationIntervalMillis(
            final ProbeProperties properties) {
        return properties.evaluationInterval().toMillis();
    }
}
