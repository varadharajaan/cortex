package io.cortex.gateway.service.impl;

import io.cortex.gateway.config.GetLogByIdProperties;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.dto.response.LogEntry;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.GetLogByIdService;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Default {@link GetLogByIdService} that forwards the lookup to
 * log-ingest-service's P9.2a REST read surface (P9.2b / ADR-0004 /
 * ADR-0049 / ADR-0022 Amendment 2).
 *
 * <p>Flow per call:</p>
 * <ol>
 *   <li>Resolve a concrete ingest instance via
 *       {@link LoadBalancerClient#choose(String)} against the discovery
 *       id ({@code log-ingest-service} by default). The blocking
 *       {@code LoadBalancerClient} + a plain {@link RestClient}
 *       deliberately avoids a {@code @LoadBalanced RestClient.Builder}
 *       bean (which would load-balance Spring AI's Ollama calls too --
 *       ADR-0049 Amendment 3).</li>
 *   <li>GET {@code /api/v1/logs/{eventId}} with the {@code X-Tenant-Id}
 *       header set to the resolved tenant (the ingest backer's single
 *       source of truth for tenant scoping). The {@code eventId} is
 *       encoded as a path segment.</li>
 *   <li>Map the response: {@code 200} -&gt; {@link LogEntry}; downstream
 *       {@code 404} -&gt; {@link ErrorCodes#NOT_FOUND}; everything else
 *       (other 4xx, 5xx, transport) -&gt;
 *       {@link ErrorCodes#GET_LOG_BY_ID_UPSTREAM_FAILED}.</li>
 * </ol>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cortex.gateway.get-log-by-id", name = "enabled", havingValue = "true")
public class GetLogByIdServiceImpl implements GetLogByIdService {

    /** Ingest REST read path template (P9.2a). */
    static final String LOG_BY_ID_PATH = "/api/v1/logs/{eventId}";

    /** Downstream HTTP 404: log row not found for the tenant + eventId. */
    private static final int HTTP_NOT_FOUND = 404;

    /** Resolves a concrete ingest instance from the discovery registry. */
    private final LoadBalancerClient loadBalancerClient;

    /** Plain (non-load-balanced) client used with an absolute resolved URI. */
    private final RestClient ingestRestClient;

    /** Typed configuration (service id, timeout). */
    private final GetLogByIdProperties properties;

    /**
     * Constructor injection of all collaborators.
     *
     * @param loadBalancerClient blocking load-balancer for instance selection
     * @param ingestRestClient   timeout-bounded plain {@link RestClient}
     * @param properties         typed configuration
     */
    public GetLogByIdServiceImpl(
            final LoadBalancerClient loadBalancerClient,
            final RestClient ingestRestClient,
            final GetLogByIdProperties properties) {
        this.loadBalancerClient = loadBalancerClient;
        this.ingestRestClient = ingestRestClient;
        this.properties = properties;
    }

    @Override
    public LogEntry getLogById(final String eventId, final String tenantId) {
        if (eventId == null || eventId.isBlank()) {
            throw new ApplicationException(ErrorCodes.VALIDATION_FAILED, "eventId is required");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id is required");
        }
        final URI target = resolveTarget(eventId);
        try {
            final LogEntry entry = this.ingestRestClient.get()
                    .uri(target)
                    .header(HeaderNames.X_TENANT_ID, tenantId)
                    .retrieve()
                    .body(LogEntry.class);
            if (entry == null) {
                throw new ApplicationException(
                        ErrorCodes.GET_LOG_BY_ID_UPSTREAM_FAILED, "ingest returned no body");
            }
            return entry;
        } catch (final RestClientResponseException ex) {
            throw mapStatus(ex);
        } catch (final ResourceAccessException ex) {
            log.warn("getLogById transport failure tenantId={} reason={}", tenantId, ex.getMessage());
            throw new ApplicationException(
                    ErrorCodes.GET_LOG_BY_ID_UPSTREAM_FAILED, "ingest unreachable", ex);
        }
    }

    /**
     * Resolves the absolute ingest get-by-id URI via the load balancer,
     * encoding {@code eventId} as a path segment.
     *
     * @param eventId the caller-facing event id
     * @return absolute {@code /api/v1/logs/{eventId}} URI on a live instance
     * @throws ApplicationException carrying
     *         {@link ErrorCodes#GET_LOG_BY_ID_UPSTREAM_FAILED} when no
     *         instance is available
     */
    private URI resolveTarget(final String eventId) {
        final ServiceInstance instance = this.loadBalancerClient.choose(this.properties.serviceId());
        if (instance == null) {
            log.warn("getLogById: no instance for serviceId={}", this.properties.serviceId());
            throw new ApplicationException(
                    ErrorCodes.GET_LOG_BY_ID_UPSTREAM_FAILED,
                    "no instance available for " + this.properties.serviceId());
        }
        return UriComponentsBuilder.fromUri(instance.getUri())
                .path(LOG_BY_ID_PATH)
                .build(eventId);
    }

    /**
     * Maps a downstream error status onto an {@link ApplicationException}.
     *
     * @param ex the downstream 4xx/5xx exception
     * @return the mapped application exception
     */
    private static ApplicationException mapStatus(final RestClientResponseException ex) {
        final int code = ex.getStatusCode().value();
        if (code == HTTP_NOT_FOUND) {
            return new ApplicationException(ErrorCodes.NOT_FOUND, "log not found");
        }
        return new ApplicationException(
                ErrorCodes.GET_LOG_BY_ID_UPSTREAM_FAILED, "ingest error status=" + code);
    }
}
