package io.cortex.ingest.enrichment;

import io.cortex.agent.LogEntry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Server-side enrichment orchestrator (P4.3 / plan.md row 169).
 *
 * <p>Combines the four enrichment inputs into the final
 * {@link Map} that is persisted to {@code raw_logs.labels}:</p>
 * <ol>
 *   <li>{@link LabelNormalizer#normalize Normalised} inbound
 *       labels (lowercased keys, trimmed values, blanks
 *       dropped).</li>
 *   <li>Resolved tenant id under
 *       {@link LogEntry#LABEL_TENANT} (always present, last
 *       write wins over any client-supplied {@code tenant}
 *       label so callers cannot spoof tenant via the labels
 *       map).</li>
 *   <li>Resolved correlation id under
 *       {@link LogEntry#LABEL_TRACE_ID} when the inbound
 *       request carried {@code X-Request-Id} or
 *       {@code X-Correlation-Id} (the
 *       {@link io.cortex.ingest.filter.CorrelationIdFilter
 *       CorrelationIdFilter} guarantees a non-null value).</li>
 *   <li>Stub-resolved country code under
 *       {@link GeoEnricher#LABEL_GEO_COUNTRY} (P4.3 ships a
 *       constant; real GeoIP lookup is deferred to P5).</li>
 * </ol>
 *
 * <p>Server-owned label keys always overwrite any client-
 * supplied entry with the same canonical key. This is the
 * documented policy so multi-tenant operators cannot inject
 * misleading {@code tenant}/{@code trace_id}/{@code geo_country}
 * labels via a malicious agent (ADR-0024).</p>
 */
@Service
public class EnrichmentService {

    /** GeoIP enricher (stub in P4.3, real in P5). */
    private final GeoEnricher geoEnricher;

    /**
     * Constructor injection of the geo enricher collaborator.
     *
     * @param geoEnricher geo enricher; must not be {@code null}
     */
    public EnrichmentService(final GeoEnricher geoEnricher) {
        this.geoEnricher = geoEnricher;
    }

    /**
     * Returns the final, persisted {@code labels} map for one
     * inbound {@link LogEntry}.
     *
     * @param entry         original inbound entry; must not be
     *                      {@code null}
     * @param tenantId      resolved tenant id (already validated
     *                      by
     *                      {@link io.cortex.ingest.tenant.TenantResolver
     *                      TenantResolver}); must not be
     *                      {@code null} or blank
     * @param correlationId resolved correlation / trace id
     *                      (already minted by
     *                      {@link io.cortex.ingest.filter.CorrelationIdFilter
     *                      CorrelationIdFilter}); must not be
     *                      {@code null} or blank
     * @return enriched, normalised, mutable
     *         {@link LinkedHashMap} ready for the
     *         {@code raw_logs.labels} JSONB column
     */
    public Map<String, String> enrich(final LogEntry entry,
                                      final String tenantId,
                                      final String correlationId) {
        final Map<String, String> out = LabelNormalizer.normalize(entry.labels());
        out.put(LogEntry.LABEL_TENANT, tenantId);
        out.put(LogEntry.LABEL_TRACE_ID, correlationId);
        out.put(GeoEnricher.LABEL_GEO_COUNTRY, this.geoEnricher.resolveCountry());
        return out;
    }
}
