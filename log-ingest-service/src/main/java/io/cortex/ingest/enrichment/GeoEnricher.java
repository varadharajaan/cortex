package io.cortex.ingest.enrichment;

import org.springframework.stereotype.Component;

/**
 * GeoIP enrichment stub (P4.3 / plan.md row 169).
 *
 * <p>Returns the constant country code configured under
 * {@code cortex.ingest.enrichment.geo.fixed-country} for every
 * inbound caller, irrespective of source IP. P4.3 ships the
 * stub so the {@code geo_country} label is wired through the
 * persisted row, the smoke script, the Postman collection, and
 * the Prometheus surface without requiring a MaxMind GeoLite2
 * file in the developer's working tree.</p>
 *
 * <p>P5 will replace this class with a real
 * {@code DatabaseReader}-backed implementation that resolves
 * the caller's source IP (X-Forwarded-For-aware) to a country
 * + region pair. The label key set and method signature stay
 * stable so callers do not churn.</p>
 */
@Component
public class GeoEnricher {

    /** Canonical label key for the resolved country code. */
    public static final String LABEL_GEO_COUNTRY = "geo_country";

    /** Bound configuration; constructor-injected. */
    private final EnrichmentProperties properties;

    /**
     * Constructor injection of the typed configuration.
     *
     * @param properties bound enrichment configuration; must not
     *                   be {@code null}
     */
    public GeoEnricher(final EnrichmentProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the country code stamped onto every enriched
     * event by this stub. Always non-null.
     *
     * @return non-null, non-blank country code (e.g.
     *         {@code "unknown"} by default)
     */
    public String resolveCountry() {
        return this.properties.geo().fixedCountry();
    }
}
