package io.cortex.indexer.search.quickwit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.indexer.admin.quickwit.QuickwitHttpConfig;
import io.cortex.indexer.admin.quickwit.QuickwitProperties;
import io.cortex.indexer.constants.IndexerHttp;
import io.cortex.indexer.metrics.IndexerMetrics;
import io.cortex.indexer.search.LogSearchClient;
import io.cortex.indexer.search.SearchRequest;
import io.cortex.indexer.search.SearchResult;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Quickwit-backed {@link LogSearchClient} adapter
 * (P7.4 / ADR-0042).
 *
 * <p>Forwards a {@link SearchRequest} to the Quickwit search API
 * via {@code POST /api/v1/{indexId}/search} with the canonical
 * JSON body {@code {"query": "...", "max_hits": N}} and parses the
 * response shape {@code {"num_hits": N, "hits": [{...}, ...]}}.</p>
 *
 * <p>Outbound HTTP shares the {@link RestClient} bean published by
 * {@link QuickwitHttpConfig#quickwitAdminRestClient(QuickwitProperties)}:
 * HTTP/1.1 pinned (LD42) + dual connect/read timeout (LD121). The
 * adapter is wire-format identical to the P7.1 / P7.2 / P7.3 admin
 * adapter -- only the verb + path + body differ.</p>
 *
 * <p><strong>Tenant-routing guardrail (ADR-0042 D3):</strong> the
 * adapter enforces a strict client-side prefix invariant on
 * {@code req.indexId()}. The id MUST begin with
 * {@code cortex-<tenantId>-} (mirror of the P7.3
 * {@code QuickwitHttpAdmin#INDEX_ID_PREFIX} contract). A mismatch
 * returns a permanent {@code quickwit:tenant-mismatch} verdict
 * <em>without</em> contacting Quickwit -- this stops a tenant from
 * accidentally or maliciously querying another tenant's splits.</p>
 *
 * <p><strong>Outcome table</strong> (mirror of ADR-0039 D3):</p>
 * <ul>
 *   <li>HTTP 200 + parseable body -&gt; {@code search_ok}.</li>
 *   <li>HTTP {@code 429} -&gt; transient {@code quickwit:429}.</li>
 *   <li>HTTP {@code 5xx} -&gt; transient {@code quickwit:5xx:&lt;n&gt;}.</li>
 *   <li>HTTP other {@code 4xx} (incl. 404) -&gt; permanent
 *       {@code quickwit:4xx:&lt;n&gt;}.</li>
 *   <li>{@link HttpTimeoutException}/{@link TimeoutException}
 *       cause -&gt; transient {@code quickwit:timeout}.</li>
 *   <li>other {@link ResourceAccessException} -&gt; transient
 *       {@code quickwit:transport}.</li>
 *   <li>unexpected {@link RuntimeException} -&gt; transient
 *       {@code quickwit:unknown}.</li>
 * </ul>
 *
 * <p>Per ADR-0042 D6 the SPI MUST NOT throw -- every error path
 * is funneled into a {@link SearchResult#transientFailure(String,
 * String)} or {@link SearchResult#permanentFailure(String, String)}
 * verdict and ticked into
 * {@link IndexerMetrics#incSearch(String, String, String)}.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.indexer.search",
        name = "backend",
        havingValue = "quickwit")
public final class QuickwitHttpSearch implements LogSearchClient {

    private static final Logger LOG =
            LoggerFactory.getLogger(QuickwitHttpSearch.class);

    /**
     * Quickwit search API path template (P7.4 / ADR-0042 D4);
     * consumes one URI variable. POST body is
     * {@code {"query": "...", "max_hits": N}}.
     */
    static final String SEARCH_PATH = "/api/v1/{indexId}/search";

    /**
     * Prefix every cortex Quickwit index id starts with
     * (P7.3 / ADR-0041 D3). Combined with the request's
     * {@code tenantId} into {@code cortex-<tenantId>-} for
     * client-side tenant-routing guardrail enforcement in
     * {@link #search(SearchRequest)} (ADR-0042 D3).
     */
    static final String INDEX_ID_PREFIX = "cortex-";

    /** JSON key carrying the user query string in the request body. */
    static final String BODY_KEY_QUERY = "query";

    /** JSON key carrying the hit limit in the request body. */
    static final String BODY_KEY_MAX_HITS = "max_hits";

    /** JSON key carrying the total hit count in the response body. */
    static final String RESPONSE_KEY_NUM_HITS = "num_hits";

    /** JSON key carrying the hits array in the response body. */
    static final String RESPONSE_KEY_HITS = "hits";

    private final QuickwitProperties properties;
    private final RestClient restClient;
    private final IndexerMetrics metrics;
    private final ObjectMapper mapper;

    /**
     * Spring constructor.
     *
     * @param properties bound configuration block
     * @param restClient the {@link QuickwitHttpConfig#quickwitAdminRestClient
     *                   quickwitAdminRestClient} bean (HTTP/1.1 + dual timeout);
     *                   shared with the P7.1+ admin adapter
     * @param metrics    shared indexer metrics registry
     * @param mapper     shared Jackson mapper (autoconfigured by Spring Boot)
     */
    @Autowired public QuickwitHttpSearch(final QuickwitProperties properties,
                                         final RestClient restClient,
                                         final IndexerMetrics metrics,
                                         final ObjectMapper mapper) {
        this.properties = properties;
        this.restClient = restClient;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @Override
    public String backendId() {
        return SearchResult.BACKEND_QUICKWIT;
    }

    @Override
    public SearchResult search(final SearchRequest request) {
        if (request == null) {
            final SearchResult result = SearchResult.permanentFailure(
                    SearchResult.BACKEND_QUICKWIT, "quickwit:null-request");
            tick(result, null);
            return result;
        }

        // Tenant-routing guardrail (ADR-0042 D3): refuse to forward
        // any query whose indexId does not carry the canonical
        // cortex-<tenantId>- prefix. Stops cross-tenant leak.
        final String expectedPrefix =
                INDEX_ID_PREFIX + request.tenantId() + "-";
        if (!request.indexId().startsWith(expectedPrefix)) {
            LOG.warn("quickwit search tenant-mismatch tenantId={} indexId={} "
                            + "expectedPrefix={}",
                    request.tenantId(), request.indexId(), expectedPrefix);
            final SearchResult result = SearchResult.permanentFailure(
                    SearchResult.BACKEND_QUICKWIT, "quickwit:tenant-mismatch");
            tick(result, request.tenantId());
            return result;
        }

        final String body;
        try {
            body = this.mapper.writeValueAsString(renderRequestBody(request));
        } catch (final JsonProcessingException ex) {
            LOG.warn("quickwit search body serialisation failed tenantId={} "
                            + "indexId={}: {}",
                    request.tenantId(), request.indexId(), ex.getMessage());
            final SearchResult result = SearchResult.transientFailure(
                    SearchResult.BACKEND_QUICKWIT, "quickwit:unknown");
            tick(result, request.tenantId());
            return result;
        }

        try {
            final String responseBody = this.restClient.post()
                    .uri(SEARCH_PATH, request.indexId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            final SearchResult result = parseResponse(responseBody);
            tick(result, request.tenantId());
            return result;
        } catch (final RestClientResponseException ex) {
            LOG.warn("quickwit search non-2xx tenantId={} indexId={} "
                            + "status={}: {}",
                    request.tenantId(), request.indexId(),
                    ex.getStatusCode().value(), ex.getMessage());
            final SearchResult result = classifyHttp(ex);
            tick(result, request.tenantId());
            return result;
        } catch (final ResourceAccessException ex) {
            LOG.warn("quickwit search transport failure tenantId={} "
                            + "indexId={}: {}",
                    request.tenantId(), request.indexId(), ex.getMessage());
            final SearchResult result = classifyTransport(ex);
            tick(result, request.tenantId());
            return result;
        } catch (final RuntimeException ex) {
            LOG.warn("quickwit search unexpected failure tenantId={} "
                            + "indexId={}: {}",
                    request.tenantId(), request.indexId(), ex.getMessage());
            final SearchResult result = SearchResult.transientFailure(
                    SearchResult.BACKEND_QUICKWIT, "quickwit:unknown");
            tick(result, request.tenantId());
            return result;
        }
    }

    /**
     * Build the JSON request body sent to
     * {@code POST /api/v1/{indexId}/search}. Package-private so the
     * pure-unit test can verify the body shape without going through
     * the {@link RestClient}.
     *
     * @param request the validated search request
     * @return a {@link Map} ready to be serialized by Jackson
     */
    Map<String, Object> renderRequestBody(final SearchRequest request) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put(BODY_KEY_QUERY, request.query());
        body.put(BODY_KEY_MAX_HITS, request.maxHits());
        return body;
    }

    /**
     * Parse the Quickwit search response body into a
     * {@link SearchResult}. Tolerates a missing {@code num_hits}
     * (treats as 0) and a missing {@code hits} array (treats as
     * empty) so partial Quickwit responses don't trip the SPI
     * "MUST NOT throw" contract.
     *
     * @param responseBody the raw JSON body returned by Quickwit;
     *                     may be {@code null}
     * @return a {@code search_ok} verdict on success; a
     *         {@code transient_failure / quickwit:unknown} verdict
     *         if the body cannot be parsed
     */
    private SearchResult parseResponse(final String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return SearchResult.searchOk(SearchResult.BACKEND_QUICKWIT,
                    0L, List.of());
        }
        try {
            final JsonNode root = this.mapper.readTree(responseBody);
            final long numHits = root.path(RESPONSE_KEY_NUM_HITS).asLong(0L);
            final List<Map<String, Object>> hits = new ArrayList<>();
            final JsonNode hitsNode = root.path(RESPONSE_KEY_HITS);
            if (hitsNode.isArray()) {
                for (final JsonNode hit : hitsNode) {
                    hits.add(jsonNodeToMap(hit));
                }
            }
            return SearchResult.searchOk(SearchResult.BACKEND_QUICKWIT,
                    numHits, hits);
        } catch (final JsonProcessingException ex) {
            LOG.warn("quickwit search response parse failed: {}",
                    ex.getMessage());
            return SearchResult.transientFailure(
                    SearchResult.BACKEND_QUICKWIT, "quickwit:unknown");
        }
    }

    /**
     * Convert a single hit {@link JsonNode} into a flat
     * {@code Map<String, Object>} so the result envelope stays
     * Jackson-free (callers can choose their own JSON binding).
     */
    private static Map<String, Object> jsonNodeToMap(final JsonNode node) {
        final Map<String, Object> map = new HashMap<>();
        if (node == null || !node.isObject()) {
            return map;
        }
        final Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            final JsonNode value = field.getValue();
            if (value.isTextual()) {
                map.put(field.getKey(), value.asText());
            } else if (value.isNumber()) {
                map.put(field.getKey(), value.numberValue());
            } else if (value.isBoolean()) {
                map.put(field.getKey(), value.booleanValue());
            } else if (value.isNull()) {
                map.put(field.getKey(), null);
            } else {
                // Object / array / binary: keep as string for safety;
                // callers can re-parse if they need structured access.
                map.put(field.getKey(), value.toString());
            }
        }
        return map;
    }

    /**
     * Classify a non-2xx HTTP response surfaced as
     * {@link RestClientResponseException}. Mirror of
     * {@code RestAdminTemplate#classifyHttp} but returning a
     * {@link SearchResult} envelope. Note: 404 is treated as
     * permanent here (unlike the admin {@code dropIndex} contract
     * which special-cases 404 -&gt; success); a missing index at
     * search time is a permanent caller-side bug.
     */
    private SearchResult classifyHttp(final RestClientResponseException ex) {
        final int code = ex.getStatusCode().value();
        if (code == IndexerHttp.TOO_MANY_REQUESTS) {
            return SearchResult.transientFailure(
                    SearchResult.BACKEND_QUICKWIT, "quickwit:429");
        }
        if (code >= IndexerHttp.SERVER_ERROR_FLOOR) {
            return SearchResult.transientFailure(
                    SearchResult.BACKEND_QUICKWIT,
                    "quickwit:5xx:" + code);
        }
        return SearchResult.permanentFailure(
                SearchResult.BACKEND_QUICKWIT,
                "quickwit:4xx:" + code);
    }

    /**
     * Classify a transport-layer failure surfaced as
     * {@link ResourceAccessException}. Cause-based discrimination
     * separates {@code timeout} (JDK {@link HttpTimeoutException}
     * or {@link TimeoutException}) from generic {@code transport}
     * (connection reset, DNS, TLS handshake, etc.).
     */
    private SearchResult classifyTransport(final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        if (cause instanceof HttpTimeoutException
                || cause instanceof TimeoutException) {
            return SearchResult.transientFailure(
                    SearchResult.BACKEND_QUICKWIT, "quickwit:timeout");
        }
        return SearchResult.transientFailure(
                SearchResult.BACKEND_QUICKWIT, "quickwit:transport");
    }

    /**
     * Tick the {@code cortex.indexer.search_total} counter for this
     * search verdict. Centralised here so every return path through
     * {@link #search(SearchRequest)} produces a metric.
     */
    private void tick(final SearchResult result, final String tenantId) {
        this.metrics.incSearch(result.backend(), result.outcome(), tenantId);
    }

    /**
     * Field accessor used only by tests to assert the bean is
     * wired against the production base URL (no compile-time
     * leakage of {@link QuickwitProperties} internals).
     *
     * @return the configured base URL of the Quickwit cluster
     */
    String baseUrl() {
        return this.properties.baseUrl();
    }
}
