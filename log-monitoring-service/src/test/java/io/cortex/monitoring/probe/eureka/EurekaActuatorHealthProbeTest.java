package io.cortex.monitoring.probe.eureka;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.monitoring.metrics.MonitoringMetrics;
import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.ProbeRequest;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.client.RestClient;

/**
 * Mockito-free unit guards for {@link EurekaActuatorHealthProbe}
 * (P8.1 / ADR-0045 D1+D4).
 *
 * <p>Covers the non-HTTP code paths -- {@code backendId()},
 * null-request guard, no-instance/empty-instance unreachable
 * outcome, instance-id selection logic, and ensures the
 * {@link MonitoringMetrics} counter is ticked on every exit.
 * The HTTP outcome table (2xx happy/degraded/unhealthy paths,
 * 429/5xx/4xx, transport faults) is exercised end-to-end by
 * {@link EurekaActuatorHealthProbeWireMockIT} against a real
 * {@link RestClient} talking to an in-process WireMock server
 * per LD104 (per-adapter slice of Leg D).</p>
 */
@DisplayName("EurekaActuatorHealthProbe")
class EurekaActuatorHealthProbeTest {

    private static final String SERVICE_ID = "log-indexer-service";

    private final ObjectMapper mapper = new ObjectMapper();
    private final EurekaActuatorProperties properties =
            new EurekaActuatorProperties(null, null);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MonitoringMetrics metrics =
            new MonitoringMetrics(this.registry, List.of());
    private final RestClient restClient = RestClient.builder().build();

    @Test
    @DisplayName("backendId reports eureka-actuator constant")
    void backendIdReportsEurekaActuator() {
        final EurekaActuatorHealthProbe probe = new EurekaActuatorHealthProbe(
                new StubDiscoveryClient(Collections.emptyList()),
                this.restClient, this.properties, this.metrics, this.mapper);

        assertThat(probe.backendId())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
    }

    @Test
    @DisplayName("null request -> permanent eureka-actuator:null-request")
    void nullRequestReturnsPermanentFailure() {
        final EurekaActuatorHealthProbe probe = new EurekaActuatorHealthProbe(
                new StubDiscoveryClient(Collections.emptyList()),
                this.restClient, this.properties, this.metrics, this.mapper);

        final HealthSnapshot result = probe.probe(null);

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("eureka-actuator:null-request");
        assertThat(result.backend())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
        assertCounter(HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                HealthSnapshot.OUTCOME_PERMANENT_FAILURE,
                MonitoringMetrics.UNKNOWN);
    }

    @Test
    @DisplayName("null instance list -> unreachable eureka-actuator:no-instance")
    void nullInstanceListReturnsUnreachable() {
        final EurekaActuatorHealthProbe probe = new EurekaActuatorHealthProbe(
                new StubDiscoveryClient(null),
                this.restClient, this.properties, this.metrics, this.mapper);

        final HealthSnapshot result = probe.probe(
                new ProbeRequest(SERVICE_ID, null));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_UNREACHABLE);
        assertThat(result.reason()).isEqualTo("eureka-actuator:no-instance");
        assertCounter(HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                HealthSnapshot.OUTCOME_UNREACHABLE, SERVICE_ID);
    }

    @Test
    @DisplayName("empty instance list -> unreachable eureka-actuator:no-instance")
    void emptyInstanceListReturnsUnreachable() {
        final EurekaActuatorHealthProbe probe = new EurekaActuatorHealthProbe(
                new StubDiscoveryClient(Collections.emptyList()),
                this.restClient, this.properties, this.metrics, this.mapper);

        final HealthSnapshot result = probe.probe(
                new ProbeRequest(SERVICE_ID, null));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_UNREACHABLE);
        assertThat(result.reason()).isEqualTo("eureka-actuator:no-instance");
    }

    @Test
    @DisplayName("instanceId selection misses every candidate -> unreachable")
    void instanceIdNotFoundReturnsUnreachable() {
        final StubDiscoveryClient discovery = new StubDiscoveryClient(List.of(
                instance("inst-a", URI.create("http://10.0.0.1:8081")),
                instance("inst-b", URI.create("http://10.0.0.2:8081"))));
        final EurekaActuatorHealthProbe probe = new EurekaActuatorHealthProbe(
                discovery, this.restClient, this.properties,
                this.metrics, this.mapper);

        final HealthSnapshot result = probe.probe(
                new ProbeRequest(SERVICE_ID, "inst-missing"));

        assertThat(result.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_UNREACHABLE);
        assertThat(result.reason()).isEqualTo("eureka-actuator:no-instance");
        assertCounter(HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                HealthSnapshot.OUTCOME_UNREACHABLE, SERVICE_ID);
    }

    @Test
    @DisplayName("DiscoveryClient is called with the requested serviceId")
    void discoveryClientReceivesServiceId() {
        final StubDiscoveryClient discovery = new StubDiscoveryClient(
                Collections.emptyList());
        final EurekaActuatorHealthProbe probe = new EurekaActuatorHealthProbe(
                discovery, this.restClient, this.properties,
                this.metrics, this.mapper);

        probe.probe(new ProbeRequest(SERVICE_ID, null));

        assertThat(discovery.lastServiceId.get()).isEqualTo(SERVICE_ID);
    }

    // -- helpers --------------------------------------------------------

    private void assertCounter(final String backend, final String outcome,
                               final String serviceId) {
        try {
            final double count = this.registry
                    .get(MonitoringMetrics.METRIC_PROBE_TOTAL)
                    .tag("backend", backend)
                    .tag("outcome", outcome)
                    .tag("service_id", serviceId)
                    .counter().count();
            assertThat(count).isEqualTo(1.0d);
        } catch (MeterNotFoundException ex) {
            throw new AssertionError(
                    "expected counter "
                            + MonitoringMetrics.METRIC_PROBE_TOTAL
                            + "{backend=" + backend
                            + ",outcome=" + outcome
                            + ",service_id=" + serviceId
                            + "} to be ticked", ex);
        }
    }

    private static ServiceInstance instance(final String instanceId,
                                            final URI uri) {
        return new DefaultServiceInstance(instanceId, SERVICE_ID,
                uri.getHost(), uri.getPort(), false, Map.of());
    }

    /**
     * Minimal hand-rolled {@link DiscoveryClient} stub that records
     * the last {@code serviceId} queried + returns a fixed instance
     * list. Avoids Mockito per Part 20.
     */
    private static final class StubDiscoveryClient implements DiscoveryClient {

        private final List<ServiceInstance> instances;
        private final AtomicReference<String> lastServiceId =
                new AtomicReference<>();

        StubDiscoveryClient(final List<ServiceInstance> instances) {
            this.instances = instances;
        }

        @Override public String description() {
            return "stub";
        }

        @Override public List<ServiceInstance> getInstances(
                final String serviceId) {
            this.lastServiceId.set(serviceId);
            if (this.instances == null) {
                return null;
            }
            return new ArrayList<>(this.instances);
        }

        @Override public List<String> getServices() {
            return Collections.emptyList();
        }
    }
}
