package io.cortex.monitoring.probe.eureka;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration class for the
 * {@link EurekaActuatorHealthProbe} HTTP probe adapter
 * (P8.1 / ADR-0045).
 *
 * <p>Activated only when
 * {@code cortex.monitoring.probe.backend=eureka-actuator} so the
 * default-dev profile (which leaves the property at {@code noop},
 * the {@link io.cortex.monitoring.probe.NoopServiceHealthProbe}
 * {@code matchIfMissing=true} variant wins) does not allocate a
 * JDK {@link HttpClient} or a {@link RestClient} pool.</p>
 *
 * <p>The published {@link RestClient} is pinned to HTTP/1.1 via
 * {@link JdkClientHttpRequestFactory} (LD42) so the wire-format
 * matches what every cortex service's embedded servlet engine
 * serves on {@code /actuator/health}. The {@code connect} and
 * {@code read} legs share the
 * {@link EurekaActuatorProperties#requestTimeout()} value (LD121
 * dual-timeout). No {@code baseUrl} is set on the builder -- the
 * adapter assembles the full per-instance URI per call from the
 * Eureka {@code ServiceInstance.getUri()} + the configured
 * {@link EurekaActuatorProperties#actuatorPath()}.</p>
 */
@Configuration
@ConditionalOnProperty(
        prefix = "cortex.monitoring.probe",
        name = "backend",
        havingValue = "eureka-actuator")
@EnableConfigurationProperties(EurekaActuatorProperties.class)
public class EurekaActuatorHttpConfig {

    /**
     * Publishes the HTTP/1.1-pinned {@link RestClient} consumed by
     * {@link EurekaActuatorHealthProbe}. The bean is created
     * lazily by Spring's standard bean lifecycle; the field
     * references in {@link EurekaActuatorHealthProbe} are
     * constructor-injected so the adapter is fully built before
     * {@link io.cortex.monitoring.metrics.MonitoringMetrics}'s OCP
     * bootstrap loop iterates over the
     * {@code List<ServiceHealthProbe>}.
     *
     * @param properties bound configuration block
     * @return the production {@link RestClient} for actuator
     *         scrapes
     */
    @Bean
    public RestClient eurekaActuatorRestClient(
            final EurekaActuatorProperties properties) {
        final JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(properties.requestTimeout())
                                .build());
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
