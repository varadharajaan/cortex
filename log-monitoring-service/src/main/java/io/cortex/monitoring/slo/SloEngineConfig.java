package io.cortex.monitoring.slo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link SloProperties} into the Spring context (P8.2 /
 * ADR-0046 D2).
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
}
