package io.cortex.gateway.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the gateway service (rule A6.1, 15.1).
 *
 * <p>Bound from {@code cortex.gateway.*} properties. Validated at
 * startup; the application fails fast if a required value is missing
 * or blank.</p>
 *
 * @param service     logical service name reported in logs, traces, and metrics
 * @param environment deployment environment label (local, dev, staging, prod)
 */
@Validated
@ConfigurationProperties(prefix = "cortex.gateway")
public record GatewayProperties(
        @NotBlank String service,
        @NotBlank String environment) {
}
