package io.cortex.monitoring.slo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link SloProperties} into the Spring context (P8.2 /
 * ADR-0046 D2) and adapts the operator-friendly
 * {@code Duration} cadence into the {@code Long} (millis) form
 * Spring's {@code @Scheduled(fixedRateString=...)} processor
 * actually accepts (ADR-0046 Amendment 2026-06-08 / issue #120
 * / LD137).
 *
 * <p>Mirrors the {@code EurekaActuatorHttpConfig} pattern used in
 * P8.1 for typed property registration. Records bound to
 * {@code @ConfigurationProperties} must be enabled via either
 * {@code @ConfigurationPropertiesScan} or
 * {@code @EnableConfigurationProperties}; this module uses the
 * latter so the binding is colocated with the package that owns
 * the property class (locality of behaviour).</p>
 *
 * <p>Unlike the P8.1 HTTP config, this class is NOT gated by a
 * {@code @ConditionalOnProperty}: the properties bean must be
 * available whether or not the {@link SloEvaluator} fires (the
 * noop engine still reads it for symmetry). The gating happens on
 * the {@link SloEvaluator} itself
 * ({@code cortex.monitoring.slo.enabled=true}) and on the engine
 * implementations
 * ({@code cortex.monitoring.slo.backend=noop|micrometer-derivation}).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SloProperties.class)
public class SloEngineConfig {

    /**
     * Bean name referenced from
     * {@link SloEvaluator#evaluateAll()}'s
     * {@code @Scheduled(fixedRateString="#{@<name>}")} SpEL
     * expression. Kept as a public constant so the SpEL string
     * literal in the {@code @Scheduled} annotation and the bean
     * registered here cannot drift independently (a typo in
     * either side would degrade to a bean-creation failure at
     * boot rather than silent breakage).
     */
    public static final String SLO_EVALUATION_INTERVAL_MILLIS_BEAN =
            "sloEvaluationIntervalMillis";

    /**
     * Adapts {@link SloProperties#evaluationInterval()} (a
     * {@link java.time.Duration}) into the long-as-string value
     * Spring's {@code ScheduledAnnotationBeanPostProcessor}
     * accepts for {@code fixedRateString}.
     *
     * <p>{@code @Scheduled(fixedRateString=...)} resolves the
     * string via {@code Long.parseLong} directly -- there is no
     * {@code Duration.parse} fallback in this Boot version. The
     * operator-friendly {@code 30s} / {@code 1h} forms therefore
     * fail with {@code NumberFormatException} at bean-creation
     * time when {@code slo.enabled=true}. Routing the value
     * through this bean lets {@code application.yml} keep the
     * typed {@code Duration} contract (and the compact-ctor
     * validation in {@link SloProperties}) while
     * {@code @Scheduled} reads the only form it actually
     * understands.</p>
     *
     * <p>The bean name is pinned via
     * {@link #SLO_EVALUATION_INTERVAL_MILLIS_BEAN} so the SpEL
     * reference in {@link SloEvaluator#evaluateAll()}
     * ({@code "#{@sloEvaluationIntervalMillis}"}) and the bean
     * defined here cannot drift.</p>
     *
     * @param properties typed config bean already published by
     *                   {@code @EnableConfigurationProperties}
     * @return scheduler cadence in milliseconds; always positive
     *         because the compact ctor on {@link SloProperties}
     *         clamps zero / negative / null to
     *         {@link SloProperties#DEFAULT_EVALUATION_INTERVAL}
     */
    @Bean(name = SLO_EVALUATION_INTERVAL_MILLIS_BEAN)
    public Long sloEvaluationIntervalMillis(
            final SloProperties properties) {
        return properties.evaluationInterval().toMillis();
    }
}
