package io.cortex.indexer.controller;

import io.cortex.indexer.controller.dto.SearchHttpRequest;
import io.cortex.indexer.controller.dto.SearchHttpResponse;
import io.cortex.indexer.search.LogSearchClient;
import io.cortex.indexer.search.SearchRequest;
import io.cortex.indexer.search.SearchResult;
import jakarta.validation.Valid;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped REST search surface for log-indexer-service
 * (P9.1a / ADR-0042 Amendment 1).
 *
 * <p>Thin HTTP adapter over the P7.4
 * {@link LogSearchClient} SPI: it does NOT own any search logic --
 * the active SPI implementation (noop default, or the Quickwit HTTP
 * adapter when {@code cortex.indexer.search.backend=quickwit})
 * performs the query, enforces the ADR-0042 D3 tenant-prefix
 * guardrail, and ticks the {@code cortex.indexer.search_total}
 * counter. This controller's sole job is the HTTP boundary: read
 * the required {@code X-Tenant-Id} header, validate the body, call
 * the SPI, and map the returned {@link SearchResult} verdict onto
 * an HTTP status.</p>
 *
 * <p>Because the SPI never throws (ADR-0042 D6), the mapping is
 * verdict-driven, not exception-driven:</p>
 * <ul>
 *   <li>{@code search_ok} / {@code noop} -&gt; 200 with a
 *       {@link SearchHttpResponse} body.</li>
 *   <li>{@code permanent_failure} + reason
 *       {@code quickwit:tenant-mismatch} -&gt; 403 Forbidden.</li>
 *   <li>{@code permanent_failure} + reason ending {@code :404}
 *       -&gt; 404 Not Found.</li>
 *   <li>other {@code permanent_failure} -&gt; 422 Unprocessable
 *       Entity.</li>
 *   <li>{@code transient_failure} + reason {@code quickwit:429}
 *       -&gt; 429 Too Many Requests with {@code Retry-After}.</li>
 *   <li>other {@code transient_failure} -&gt; 503 Service
 *       Unavailable.</li>
 * </ul>
 *
 * <p>Tenant scoping: the gateway (P9.1b) authenticates the caller
 * and forwards the resolved tenant via {@code X-Tenant-Id}. This
 * controller treats that header as the single source of truth for
 * {@code tenantId} so a body field can never spoof another tenant;
 * the body carries only {@code indexId}, {@code query}, and an
 * optional {@code maxHits}.</p>
 */
@RestController
public class SearchController {

    private static final Logger LOG =
            LoggerFactory.getLogger(SearchController.class);

    /** HTTP path of the tenant-scoped search endpoint. */
    static final String SEARCH_PATH = "/api/v1/search";

    /** Header carrying the resolved tenant id (set by the gateway). */
    static final String TENANT_HEADER = "X-Tenant-Id";

    /** Default hit ceiling applied when the body omits {@code maxHits}. */
    static final int DEFAULT_MAX_HITS = 50;

    /** Hard upper bound; a larger requested {@code maxHits} is clamped here. */
    static final int MAX_HITS_CEILING = 1000;

    /** Permanent-failure reason flagged as a cross-tenant guardrail trip. */
    static final String REASON_TENANT_MISMATCH = "quickwit:tenant-mismatch";

    /** Suffix of the permanent-failure reason for a missing index (HTTP 404). */
    static final String REASON_NOT_FOUND_SUFFIX = ":404";

    /** Transient-failure reason flagged as downstream rate limiting. */
    static final String REASON_RATE_LIMITED = "quickwit:429";

    /** Conservative {@code Retry-After} value (seconds) for a 429 passthrough. */
    static final String RETRY_AFTER_SECONDS = "1";

    /** Prefix for the {@code errorCode} problem property. */
    static final String ERROR_CODE_PREFIX = "SEARCH_";

    private final LogSearchClient searchClient;

    /**
     * Constructor injection of the active search SPI implementation
     * (rule 14.1). Exactly one of the two
     * {@code @ConditionalOnProperty}-gated implementations
     * (noop default or Quickwit) is present on the context.
     *
     * @param searchClient the active {@link LogSearchClient} bean
     */
    public SearchController(final LogSearchClient searchClient) {
        this.searchClient = searchClient;
    }

    /**
     * Executes a tenant-scoped search and maps the SPI verdict to
     * an HTTP response.
     *
     * @param tenantId resolved tenant id from the required
     *                 {@code X-Tenant-Id} header; a missing header
     *                 is mapped to 400 by
     *                 {@link SearchControllerAdvice}
     * @param request  validated query body
     * @return 200 with a {@link SearchHttpResponse} on success, or a
     *         status-specific RFC 7807 {@link ProblemDetail} on a
     *         failure verdict
     */
    @PostMapping(
            value = SEARCH_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> search(
            @RequestHeader(TENANT_HEADER) final String tenantId,
            @Valid @RequestBody final SearchHttpRequest request) {
        final int maxHits = effectiveMaxHits(request.maxHits());
        final SearchRequest spiRequest = new SearchRequest(
                tenantId, request.indexId(), request.query(), maxHits);
        final SearchResult result = this.searchClient.search(spiRequest);
        return toResponse(result, tenantId);
    }

    /**
     * Resolves the effective hit ceiling: the server default when
     * the body omits {@code maxHits}, otherwise the requested value
     * clamped to {@link #MAX_HITS_CEILING}.
     *
     * @param requested nullable requested ceiling from the body
     * @return a strictly-positive effective ceiling
     */
    private static int effectiveMaxHits(final Integer requested) {
        if (requested == null) {
            return DEFAULT_MAX_HITS;
        }
        return Math.min(requested, MAX_HITS_CEILING);
    }

    /**
     * Maps a {@link SearchResult} verdict onto an HTTP response.
     *
     * @param result   the SPI verdict
     * @param tenantId the request tenant id (echoed into the problem
     *                 body for operator correlation)
     * @return the HTTP response entity
     */
    private static ResponseEntity<Object> toResponse(
            final SearchResult result, final String tenantId) {
        final String outcome = result.outcome();
        if (SearchResult.OUTCOME_SEARCH_OK.equals(outcome)
                || SearchResult.OUTCOME_NOOP.equals(outcome)) {
            return ResponseEntity.ok(
                    new SearchHttpResponse(result.numHits(), result.hits()));
        }
        final HttpStatus status = mapFailureStatus(result);
        LOG.warn("search failed tenantId={} backend={} outcome={} reason={} -> http={}",
                tenantId, result.backend(), outcome, result.reason(), status.value());
        final ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return builder.body(problem(status, result, tenantId));
    }

    /**
     * Maps a failure verdict's {@code outcome} + {@code reason} to
     * the matching HTTP status per the ADR-0042 Amendment 1 table.
     *
     * @param result the failure verdict (never a success outcome)
     * @return the HTTP status
     */
    private static HttpStatus mapFailureStatus(final SearchResult result) {
        final String reason = result.reason() == null ? "" : result.reason();
        if (SearchResult.OUTCOME_PERMANENT_FAILURE.equals(result.outcome())) {
            if (REASON_TENANT_MISMATCH.equals(reason)) {
                return HttpStatus.FORBIDDEN;
            }
            if (reason.endsWith(REASON_NOT_FOUND_SUFFIX)) {
                return HttpStatus.NOT_FOUND;
            }
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        if (REASON_RATE_LIMITED.equals(reason)) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        return HttpStatus.SERVICE_UNAVAILABLE;
    }

    /**
     * Builds an RFC 7807 problem body for a failure verdict.
     *
     * @param status   resolved HTTP status
     * @param result   the failure verdict
     * @param tenantId the request tenant id
     * @return a populated {@link ProblemDetail}
     */
    private static ProblemDetail problem(final HttpStatus status,
            final SearchResult result, final String tenantId) {
        final ProblemDetail detail =
                ProblemDetail.forStatusAndDetail(status, result.reason());
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("errorCode",
                ERROR_CODE_PREFIX + result.outcome().toUpperCase(Locale.ROOT));
        detail.setProperty("backend", result.backend());
        detail.setProperty("tenantId", tenantId);
        return detail;
    }
}
