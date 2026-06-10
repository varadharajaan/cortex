package io.cortex.monitoring.probe.eureka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.monitoring.metrics.MonitoringMetrics;
import io.cortex.monitoring.probe.HealthSnapshot;
import io.cortex.monitoring.probe.ProbeRequest;
import io.cortex.monitoring.probe.ServiceHealthProbe;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real {@link ServiceHealthProbe} implementation backed by
 * Spring Cloud's {@link DiscoveryClient} (Eureka in production)
 * + an HTTP/1.1-pinned {@link RestClient} (P8.1 / ADR-0045).
 *
 * <p>Activated only when
 * {@code cortex.monitoring.probe.backend=eureka-actuator}. The
 * default-dev profile leaves the property at {@code noop}, which
 * means the {@link io.cortex.monitoring.probe.NoopServiceHealthProbe}
 * {@code matchIfMissing=true} variant wins and this bean stays
 * out of the context. The two adapters are mutually exclusive at
 * the {@code @ConditionalOnProperty} level.</p>
 *
 * <p><strong>Probe flow</strong> (ADR-0045 D4):</p>
 * <ol>
 *   <li>Null {@link ProbeRequest} -&gt;
 *       {@code permanent_failure eureka-actuator:null-request}.</li>
 *   <li>{@link DiscoveryClient#getInstances(String)} on
 *       {@code request.serviceId()}; null/empty list -&gt;
 *       {@code unreachable eureka-actuator:no-instance}.</li>
 *   <li>If {@code request.instanceId()} is non-blank, pick the
 *       matching instance; missing -&gt;
 *       {@code unreachable eureka-actuator:no-instance}. Else
 *       pick the first instance.</li>
 *   <li>Build URI = {@code instance.getUri() +
 *       properties.actuatorPath()}; {@code GET} via
 *       {@link RestClient}.</li>
 *   <li>Parse the JSON body for the {@code status} field:
 *     <ul>
 *       <li>{@code UP} -&gt; {@link HealthSnapshot#healthy}</li>
 *       <li>{@code OUT_OF_SERVICE} -&gt;
 *           {@link HealthSnapshot#degraded}</li>
 *       <li>{@code DOWN} -&gt; {@link HealthSnapshot#unhealthy}</li>
 *       <li>missing / blank / unknown value -&gt;
 *           {@code degraded(eureka-actuator, "unknown:<raw>")}</li>
 *     </ul>
 *   </li>
 *   <li>Non-2xx responses + transport faults are classified by
 *       {@link RestProbeTemplate} per the ADR-0045 D3 outcome
 *       table.</li>
 * </ol>
 *
 * <p>Every call ends with a
 * {@link MonitoringMetrics#incProbe(String, String, String)} tick
 * so the Micrometer counter family the P8.0 bootstrap loop
 * pre-registered is kept current. {@link RuntimeException}s are
 * caught and downgraded to {@code transient_failure} per the SPI
 * contract that probes MUST NOT throw (ADR-0044 D1).</p>
 *
 * <p>Outbound HTTP is pinned to HTTP/1.1 via the
 * {@link RestClient} bean published by
 * {@link EurekaActuatorHttpConfig} (LD42 + LD121).</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.monitoring.probe",
        name = "backend",
        havingValue = "eureka-actuator")
public final class EurekaActuatorHealthProbe implements ServiceHealthProbe {

    private static final Logger LOG =
            LoggerFactory.getLogger(EurekaActuatorHealthProbe.class);

    /** Spring Boot actuator JSON {@code status} field name. */
    static final String STATUS_FIELD = "status";

    /** Status value indicating the service is fully healthy. */
    static final String STATUS_UP = "UP";

    /**
     * Status value indicating the service is intentionally out of
     * rotation (e.g. maintenance mode); mapped to
     * {@code degraded}.
     */
    static final String STATUS_OUT_OF_SERVICE = "OUT_OF_SERVICE";

    /** Status value indicating the service is reporting itself down. */
    static final String STATUS_DOWN = "DOWN";

    private final DiscoveryClient discoveryClient;
    private final RestClient restClient;
    private final EurekaActuatorProperties properties;
    private final MonitoringMetrics metrics;
    private final ObjectMapper mapper;
    private final RestProbeTemplate template =
            new RestProbeTemplate(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);

    /**
     * Spring constructor.
     *
     * <p><strong>{@code @Lazy} on {@code metrics}</strong> (LD131
     * / ADR-0043 D2 / P8.0 bootstrap pattern):
     * {@link MonitoringMetrics} injects
     * {@code List<ServiceHealthProbe>} for the OCP bootstrap loop,
     * which would otherwise close the cycle
     * {@code MonitoringMetrics -> EurekaActuatorHealthProbe ->
     * MonitoringMetrics} and trip Spring's
     * {@link
     * org.springframework.beans.factory.BeanCurrentlyInCreationException}
     * the moment
     * {@code cortex.monitoring.probe.backend=eureka-actuator} is
     * set. {@code @Lazy} hands this adapter a JDK proxy for
     * {@link MonitoringMetrics} that resolves the real bean on
     * first method call -- by then both ends of the cycle are
     * fully constructed.</p>
     *
     * @param discoveryClient Spring Cloud discovery client
     *                        (Eureka in production)
     * @param restClient      the
     *                        {@link EurekaActuatorHttpConfig#eurekaActuatorRestClient
     *                        eurekaActuatorRestClient} bean
     *                        (HTTP/1.1 + dual timeout)
     * @param properties      bound configuration block
     * @param metrics         shared monitoring metrics registry;
     *                        {@code @Lazy} to break the cycle
     *                        (LD131)
     * @param mapper          shared Jackson mapper (autoconfigured
     *                        by Spring Boot)
     */
    @Autowired public EurekaActuatorHealthProbe(
            final DiscoveryClient discoveryClient,
            @Qualifier("eurekaActuatorRestClient") final RestClient restClient,
            final EurekaActuatorProperties properties,
            @Lazy final MonitoringMetrics metrics,
            final ObjectMapper mapper) {
        this.discoveryClient = discoveryClient;
        this.restClient = restClient;
        this.properties = properties;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @Override
    public String backendId() {
        return HealthSnapshot.BACKEND_EUREKA_ACTUATOR;
    }

    @Override
    public HealthSnapshot probe(final ProbeRequest request) {
        if (request == null) {
            final HealthSnapshot result = HealthSnapshot.permanentFailure(
                    HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                    "eureka-actuator:null-request");
            tick(result, null);
            return result;
        }

        final String serviceId = request.serviceId();
        final List<ServiceInstance> instances =
                this.discoveryClient.getInstances(serviceId);
        if (instances == null || instances.isEmpty()) {
            final HealthSnapshot result = HealthSnapshot.unreachable(
                    HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                    "eureka-actuator:no-instance");
            tick(result, serviceId);
            return result;
        }

        final ServiceInstance instance = selectInstance(
                instances, request.instanceId());
        if (instance == null) {
            final HealthSnapshot result = HealthSnapshot.unreachable(
                    HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                    "eureka-actuator:no-instance");
            tick(result, serviceId);
            return result;
        }

        final URI uri = URI.create(
                instance.getUri().toString() + this.properties.actuatorPath());
        final HealthSnapshot result = scrape(uri);
        tick(result, serviceId);
        return result;
    }

    /**
     * Pick the instance matching the requested
     * {@code instanceId}, or the first instance when the request
     * omits the field.
     */
    private static ServiceInstance selectInstance(
            final List<ServiceInstance> instances,
            final String requestedInstanceId) {
        if (StringUtils.isBlank(requestedInstanceId)) {
            return instances.get(0);
        }
        for (final ServiceInstance candidate : instances) {
            if (requestedInstanceId.equals(candidate.getInstanceId())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Issue the actuator GET and map the response to a
     * {@link HealthSnapshot}. All failures are translated to
     * envelope verdicts; this method must not throw.
     */
    private HealthSnapshot scrape(final URI uri) {
        try {
            final byte[] body = this.restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(byte[].class);
            return classifyBody(body);
        } catch (RestClientResponseException ex) {
            LOG.debug("eureka-actuator HTTP failure uri={} status={}",
                    uri, ex.getStatusCode().value());
            return this.template.classifyHttp(ex);
        } catch (ResourceAccessException ex) {
            LOG.debug("eureka-actuator transport failure uri={} cause={}",
                    uri, ex.getCause() == null
                            ? "(none)" : ex.getCause().getClass().getName());
            return this.template.classifyTransport(ex);
        } catch (RuntimeException ex) {
            LOG.debug("eureka-actuator unexpected failure uri={}", uri, ex);
            return this.template.classifyUnknown(ex);
        }
    }

    /**
     * Parse the actuator JSON body for the {@code status} field
     * and map to a {@link HealthSnapshot}.
     */
    private HealthSnapshot classifyBody(final byte[] body) {
        if (body == null || body.length == 0) {
            return HealthSnapshot.degraded(
                    HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                    "unknown:empty-body");
        }
        try {
            final JsonNode root = this.mapper.readTree(body);
            final JsonNode statusNode = root.path(STATUS_FIELD);
            if (statusNode.isMissingNode() || !statusNode.isTextual()) {
                return HealthSnapshot.degraded(
                        HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                        "unknown:no-status-field");
            }
            final String status = statusNode.asText();
            switch (status) {
                case STATUS_UP:
                    return HealthSnapshot.healthy(
                            HealthSnapshot.BACKEND_EUREKA_ACTUATOR, STATUS_UP);
                case STATUS_OUT_OF_SERVICE:
                    return HealthSnapshot.degraded(
                            HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                            STATUS_OUT_OF_SERVICE);
                case STATUS_DOWN:
                    return HealthSnapshot.unhealthy(
                            HealthSnapshot.BACKEND_EUREKA_ACTUATOR, STATUS_DOWN);
                default:
                    return HealthSnapshot.degraded(
                            HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                            "unknown:" + status);
            }
        } catch (RuntimeException | java.io.IOException ex) {
            LOG.debug("eureka-actuator body parse failure body={}",
                    new String(body, StandardCharsets.UTF_8), ex);
            return HealthSnapshot.degraded(
                    HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                    "unknown:parse-error");
        }
    }

    /** Tick the bound Micrometer counter for the verdict + serviceId. */
    private void tick(final HealthSnapshot result, final String serviceId) {
        this.metrics.incProbe(result.backend(), result.outcome(), serviceId);
    }
}
