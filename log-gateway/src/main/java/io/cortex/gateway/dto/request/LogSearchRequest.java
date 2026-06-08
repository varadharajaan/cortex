package io.cortex.gateway.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Query inputs for the {@code searchLogs} operation shared by the REST
 * surface ({@code GET /api/v1/logs/search}) and the GraphQL surface
 * ({@code searchLogs(input: LogSearchInput!)}) (P9.1b / ADR-0004 /
 * ADR-0049).
 *
 * <p>Carries only the three operator-facing query inputs. The fourth
 * domain field -- {@code tenantId} -- is intentionally absent: the
 * gateway resolves it from the authenticated request's
 * {@code X-Tenant-Id} header (ADR-0009) and forwards it to
 * log-indexer-service as the single source of truth, so a body / input
 * field can never spoof another tenant (mirrors the P9.1a indexer
 * contract where the controller trusts only the header).</p>
 *
 * <p>On the GraphQL surface the schema {@code input LogSearchInput}
 * field names match these record components verbatim so Spring for
 * GraphQL binds the argument to this record. On the REST surface the
 * controller constructs this record from the {@code index}, {@code q},
 * and {@code maxHits} query parameters.</p>
 *
 * @param indexId Quickwit index id to query; never blank. Must carry
 *                the canonical {@code cortex-<tenantId>-} prefix or the
 *                indexer returns a tenant-mismatch verdict (ADR-0042 D3)
 *                which the gateway maps to HTTP 403.
 * @param query   Quickwit query string forwarded verbatim; never blank.
 * @param maxHits optional upper bound on hits to return; when present
 *                must be strictly positive. {@code null} means "use the
 *                gateway default ceiling".
 */
public record LogSearchRequest(
        @NotBlank(message = "indexId must not be blank") String indexId,
        @NotBlank(message = "query must not be blank") String query,
        @Positive(message = "maxHits must be strictly positive when present") Integer maxHits) {
}
