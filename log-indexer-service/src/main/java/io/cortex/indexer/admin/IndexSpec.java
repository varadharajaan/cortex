package io.cortex.indexer.admin;

/**
 * Immutable input record describing a Quickwit index to materialise
 * (P7.0 / ADR-0038 D2).
 *
 * <p>Minimal Day-1 shape -- carries only the three fields the SPI
 * needs to identify the per-tenant index slot. The full Quickwit
 * doc-mapping JSON is generated downstream from
 * {@code docMappingVersion} (a stable schema-id) by the P7.1 HTTP
 * admin client; this record stays small + immutable so it can be
 * passed across thread boundaries without defensive copying.</p>
 *
 * @param tenantId          tenant the index belongs to; never
 *                          {@code null}, never blank
 * @param indexId           the Quickwit index id (per ADR-0038 D2:
 *                          {@code cortex-<tenantId>-<docMappingVersion>});
 *                          never {@code null}, never blank
 * @param docMappingVersion the doc-mapping schema version, e.g.
 *                          {@code v1}; bounded by the P5.3
 *                          QuickwitSink schema enum
 */
public record IndexSpec(String tenantId, String indexId,
                        String docMappingVersion) {

    /**
     * Compact validator-style canonical constructor. Defends against
     * null + blank values so downstream HTTP/JSON code paths can
     * skip the same defensive checks.
     *
     * @throws IllegalArgumentException when any of the three fields
     *                                  is {@code null} or blank
     */
    public IndexSpec {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (indexId == null || indexId.isBlank()) {
            throw new IllegalArgumentException("indexId must not be blank");
        }
        if (docMappingVersion == null || docMappingVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "docMappingVersion must not be blank");
        }
    }
}
