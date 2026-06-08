package io.cortex.indexer.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Inbound HTTP request body for the tenant-scoped search endpoint
 * {@code POST /api/v1/search} (P9.1a / ADR-0042 Amendment 1).
 *
 * <p>The operator-facing shape carries only the three query inputs;
 * the fourth domain field ({@code tenantId}) is taken from the
 * required {@code X-Tenant-Id} request header by
 * {@link io.cortex.indexer.controller.SearchController} so a caller
 * can never spoof another tenant via the body. The controller maps
 * this DTO onto the validated domain
 * {@link io.cortex.indexer.search.SearchRequest} record.</p>
 *
 * <p>{@code maxHits} is intentionally a nullable {@link Integer}:
 * when omitted the controller applies a default ceiling; when
 * present it must be strictly positive. The controller clamps an
 * over-large value to the hard ceiling rather than rejecting it,
 * so a generous client cannot trip a 400.</p>
 *
 * @param indexId Quickwit index id to query; never blank. Must
 *                carry the canonical {@code cortex-<tenantId>-}
 *                prefix or the search SPI returns a tenant-mismatch
 *                verdict (ADR-0042 D3) which the controller maps to
 *                HTTP 403.
 * @param query   Quickwit query string forwarded verbatim; never
 *                blank.
 * @param maxHits optional upper bound on hits to return; when
 *                present must be strictly positive. {@code null}
 *                means "use the server default".
 */
public record SearchHttpRequest(
        @NotBlank(message = "indexId must not be blank") String indexId,
        @NotBlank(message = "query must not be blank") String query,
        @Positive(message = "maxHits must be strictly positive when present") Integer maxHits) {
}
