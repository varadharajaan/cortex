package io.cortex.gateway.service.impl;

import io.cortex.gateway.config.SearchLogsProperties;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.dto.request.LogSearchRequest;
import io.cortex.gateway.dto.response.LogSearchResult;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.SearchLogsService;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Default {@link SearchLogsService} that forwards the query to
 * log-indexer-service's P9.1a REST search surface (P9.1b / ADR-0004 /
 * ADR-0049 / ADR-0042 Amendment 1).
 *
 * <p>Flow per call:</p>
 * <ol>
 *   <li>Resolve a concrete indexer instance via
 *       {@link LoadBalancerClient#choose(String)} against the
 *       discovery id ({@code log-indexer-service} by default). Using
 *       the blocking {@code LoadBalancerClient} + a plain
 *       {@link RestClient} deliberately avoids declaring a
 *       {@code @LoadBalanced RestClient.Builder} bean, which would be
 *       picked up by Spring AI's
 *       {@code ObjectProvider.getIfAvailable(RestClient::builder)}
 *       lookup and load-balance the Ollama calls too (ADR-0049
 *       Amendment 3 rejected alternative).</li>
 *   <li>POST {@code /api/v1/search} with the {@code X-Tenant-Id} header
 *       set to the resolved tenant (the indexer's single source of
 *       truth for tenant scoping) and a JSON body
 *       {@code {indexId, query, maxHits?}}.</li>
 *   <li>Map the response: {@code 200} -&gt; {@link LogSearchResult};
 *       the indexer's RFC 7807 failure statuses (per ADR-0042
 *       Amendment 1) -&gt; the matching
 *       {@link ApplicationException}.</li>
 * </ol>
 *
 * <p>The downstream-status mapping is: {@code 403} -&gt;
 * {@link ErrorCodes#FORBIDDEN} (cross-tenant guardrail); {@code 404}
 * -&gt; {@link ErrorCodes#NOT_FOUND} (missing index); {@code 422} -&gt;
 * {@link ErrorCodes#SEARCH_LOGS_INVALID} (permanent, unprocessable);
 * everything else (downstream {@code 429}/{@code 503}/{@code 5xx} +
 * transport failures) -&gt;
 * {@link ErrorCodes#SEARCH_LOGS_UPSTREAM_FAILED}.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "cortex.gateway.search-logs", name = "enabled", havingValue = "true")
public class SearchLogsServiceImpl implements SearchLogsService {

    /** Indexer REST search path (P9.1a). */
    static final String SEARCH_PATH = "/api/v1/search";

    /** Downstream HTTP 403: cross-tenant guardrail trip. */
    private static final int HTTP_FORBIDDEN = 403;

    /** Downstream HTTP 404: index not found. */
    private static final int HTTP_NOT_FOUND = 404;

    /** Downstream HTTP 422: permanent, unprocessable. */
    private static final int HTTP_UNPROCESSABLE = 422;

    /** Resolves a concrete indexer instance from the discovery registry. */
    private final LoadBalancerClient loadBalancerClient;

    /** Plain (non-load-balanced) client used with an absolute resolved URI. */
    private final RestClient indexerRestClient;

    /** Typed search configuration (service id, timeouts, ceilings). */
    private final SearchLogsProperties properties;

    /**
     * Constructor injection of all collaborators.
     *
     * @param loadBalancerClient blocking load-balancer for instance selection
     * @param indexerRestClient  timeout-bounded plain {@link RestClient}
     * @param properties         typed search configuration
     */
    public SearchLogsServiceImpl(
            final LoadBalancerClient loadBalancerClient,
            final RestClient indexerRestClient,
            final SearchLogsProperties properties) {
        this.loadBalancerClient = loadBalancerClient;
        this.indexerRestClient = indexerRestClient;
        this.properties = properties;
    }

    @Override
    public LogSearchResult search(final LogSearchRequest request, final String tenantId) {
        if (request == null) {
            throw new ApplicationException(ErrorCodes.VALIDATION_FAILED, "search request is null");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new ApplicationException(ErrorCodes.VALIDATION_FAILED, "X-Tenant-Id is required");
        }
        final URI target = resolveTarget();
        final Map<String, Object> body = buildBody(request);
        try {
            final LogSearchResult result = this.indexerRestClient.post()
                    .uri(target)
                    .header(HeaderNames.X_TENANT_ID, tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(LogSearchResult.class);
            if (result == null) {
                throw new ApplicationException(
                        ErrorCodes.SEARCH_LOGS_UPSTREAM_FAILED, "indexer returned no body");
            }
            return result;
        } catch (final RestClientResponseException ex) {
            throw mapStatus(ex);
        } catch (final ResourceAccessException ex) {
            log.warn("searchLogs transport failure tenantId={} reason={}", tenantId, ex.getMessage());
            throw new ApplicationException(
                    ErrorCodes.SEARCH_LOGS_UPSTREAM_FAILED, "indexer unreachable", ex);
        }
    }

    /**
     * Resolves the absolute indexer search URI via the load balancer.
     *
     * @return absolute {@code /api/v1/search} URI on a live instance
     * @throws ApplicationException carrying
     *         {@link ErrorCodes#SEARCH_LOGS_UPSTREAM_FAILED} when no
     *         instance is available
     */
    private URI resolveTarget() {
        final ServiceInstance instance = this.loadBalancerClient.choose(this.properties.serviceId());
        if (instance == null) {
            log.warn("searchLogs: no instance for serviceId={}", this.properties.serviceId());
            throw new ApplicationException(
                    ErrorCodes.SEARCH_LOGS_UPSTREAM_FAILED,
                    "no instance available for " + this.properties.serviceId());
        }
        return instance.getUri().resolve(SEARCH_PATH);
    }

    /**
     * Builds the indexer request body, omitting {@code maxHits} when the
     * caller did not supply it (so the indexer applies its own default)
     * and clamping a supplied value to the gateway ceiling.
     *
     * @param request validated query inputs
     * @return ordered JSON body map
     */
    private Map<String, Object> buildBody(final LogSearchRequest request) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("indexId", request.indexId());
        body.put("query", request.query());
        if (request.maxHits() != null) {
            body.put("maxHits", Math.min(request.maxHits(), this.properties.maxHitsCeiling()));
        }
        return body;
    }

    /**
     * Maps a downstream error status onto an {@link ApplicationException}.
     *
     * @param ex the downstream 4xx/5xx exception
     * @return the mapped application exception
     */
    private static ApplicationException mapStatus(final RestClientResponseException ex) {
        final int code = ex.getStatusCode().value();
        if (code == HTTP_FORBIDDEN) {
            return new ApplicationException(ErrorCodes.FORBIDDEN, "cross-tenant search rejected");
        }
        if (code == HTTP_NOT_FOUND) {
            return new ApplicationException(ErrorCodes.NOT_FOUND, "index not found");
        }
        if (code == HTTP_UNPROCESSABLE) {
            return new ApplicationException(
                    ErrorCodes.SEARCH_LOGS_INVALID, "search rejected by indexer");
        }
        return new ApplicationException(
                ErrorCodes.SEARCH_LOGS_UPSTREAM_FAILED, "indexer error status=" + code);
    }
}
