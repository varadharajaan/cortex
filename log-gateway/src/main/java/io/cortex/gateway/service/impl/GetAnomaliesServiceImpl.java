package io.cortex.gateway.service.impl;

import io.cortex.gateway.config.GetAnomaliesProperties;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.Anomaly;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.GetAnomaliesService;
import java.net.URI;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Default {@link GetAnomaliesService} that forwards the listing to
 * log-remediation-service's P9.3a REST read surface (P9.3b / ADR-0004 /
 * ADR-0049 / ADR-0052).
 *
 * <p>Flow per call:</p>
 * <ol>
 *   <li>Resolve a concrete remediation instance via
 *       {@link LoadBalancerClient#choose(String)} against the discovery
 *       id ({@code log-remediation-service} by default). The blocking
 *       {@code LoadBalancerClient} + a plain {@link RestClient}
 *       deliberately avoids a {@code @LoadBalanced RestClient.Builder}
 *       bean (which would load-balance Spring AI's Ollama calls too --
 *       ADR-0049 Amendment 3).</li>
 *   <li>GET {@code /api/v1/anomalies?tenantId=&since=&until=&limit=} with
 *       {@code tenantId} as the single source of truth for tenant scoping
 *       (the remediation backer reads it from the query parameter, not a
 *       header) and the optional filters appended when present. Query
 *       values are URL-encoded so ISO-8601 timestamps (which carry
 *       {@code :} and possibly {@code +}) survive transit.</li>
 *   <li>Map the response: {@code 200} -> the {@link Anomaly} list (an
 *       empty list is a valid result, not a miss); downstream {@code 4xx}
 *       -> {@link ErrorCodes#GET_ANOMALIES_INVALID}; everything else
 *       ({@code 5xx}, transport) ->
 *       {@link ErrorCodes#GET_ANOMALIES_UPSTREAM_FAILED}.</li>
 * </ol>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cortex.gateway.get-anomalies", name = "enabled", havingValue = "true")
public class GetAnomaliesServiceImpl implements GetAnomaliesService {

    /** Remediation REST read path (P9.3a). */
    static final String ANOMALIES_PATH = "/api/v1/anomalies";

    /** Response body type: a JSON array of anomaly projections. */
    private static final ParameterizedTypeReference<List<Anomaly>> ANOMALY_LIST =
            new ParameterizedTypeReference<>() { };

    /** Resolves a concrete remediation instance from the discovery registry. */
    private final LoadBalancerClient loadBalancerClient;

    /** Plain (non-load-balanced) client used with an absolute resolved URI. */
    private final RestClient remediationRestClient;

    /** Typed configuration (service id, timeout). */
    private final GetAnomaliesProperties properties;

    /**
     * Constructor injection of all collaborators.
     *
     * @param loadBalancerClient    blocking load-balancer for instance selection
     * @param remediationRestClient timeout-bounded plain {@link RestClient}
     * @param properties            typed configuration
     */
    public GetAnomaliesServiceImpl(
            final LoadBalancerClient loadBalancerClient,
            final RestClient remediationRestClient,
            final GetAnomaliesProperties properties) {
        this.loadBalancerClient = loadBalancerClient;
        this.remediationRestClient = remediationRestClient;
        this.properties = properties;
    }

    @Override
    public List<Anomaly> getAnomalies(
            final String tenantId, final String since, final String until, final Integer limit) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id is required");
        }
        if (limit != null && limit <= 0) {
            throw new ApplicationException(
                    ErrorCodes.VALIDATION_FAILED, "limit must be strictly positive");
        }
        final URI target = resolveTarget(tenantId, since, until, limit);
        try {
            final List<Anomaly> body = this.remediationRestClient.get()
                    .uri(target)
                    .retrieve()
                    .body(ANOMALY_LIST);
            return body == null ? List.of() : List.copyOf(body);
        } catch (final RestClientResponseException ex) {
            throw mapStatus(ex);
        } catch (final ResourceAccessException ex) {
            log.warn("getAnomalies transport failure tenantId={} reason={}", tenantId, ex.getMessage());
            throw new ApplicationException(
                    ErrorCodes.GET_ANOMALIES_UPSTREAM_FAILED, "remediation unreachable", ex);
        }
    }

    /**
     * Resolves the absolute remediation anomalies-read URI via the load
     * balancer, appending {@code tenantId} (always) and the optional
     * {@code since} / {@code until} / {@code limit} filters, URL-encoded.
     *
     * @param tenantId resolved tenant id
     * @param since    optional lower bound (verbatim when present)
     * @param until    optional upper bound (verbatim when present)
     * @param limit    optional row limit (when present)
     * @return absolute {@code /api/v1/anomalies?...} URI on a live instance
     * @throws ApplicationException carrying
     *         {@link ErrorCodes#GET_ANOMALIES_UPSTREAM_FAILED} when no
     *         instance is available
     */
    private URI resolveTarget(
            final String tenantId, final String since, final String until, final Integer limit) {
        final ServiceInstance instance = this.loadBalancerClient.choose(this.properties.serviceId());
        if (instance == null) {
            log.warn("getAnomalies: no instance for serviceId={}", this.properties.serviceId());
            throw new ApplicationException(
                    ErrorCodes.GET_ANOMALIES_UPSTREAM_FAILED,
                    "no instance available for " + this.properties.serviceId());
        }
        final UriComponentsBuilder builder = UriComponentsBuilder.fromUri(instance.getUri())
                .path(ANOMALIES_PATH)
                .queryParam("tenantId", tenantId);
        if (since != null && !since.isBlank()) {
            builder.queryParam("since", since);
        }
        if (until != null && !until.isBlank()) {
            builder.queryParam("until", until);
        }
        if (limit != null) {
            builder.queryParam("limit", limit);
        }
        return builder.encode().build().toUri();
    }

    /**
     * Maps a downstream error status onto an {@link ApplicationException}.
     * A {@code 4xx} is a permanently-unprocessable query (e.g. a malformed
     * {@code since} / {@code until}); any other status is treated as an
     * upstream failure.
     *
     * @param ex the downstream 4xx/5xx exception
     * @return the mapped application exception
     */
    private static ApplicationException mapStatus(final RestClientResponseException ex) {
        final int code = ex.getStatusCode().value();
        if (ex.getStatusCode().is4xxClientError()) {
            return new ApplicationException(
                    ErrorCodes.GET_ANOMALIES_INVALID, "remediation rejected query status=" + code);
        }
        return new ApplicationException(
                ErrorCodes.GET_ANOMALIES_UPSTREAM_FAILED, "remediation error status=" + code);
    }
}
