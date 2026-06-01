package io.cortex.ingest.enrichment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the P4.3 server-side enrichment
 * pipeline (plan.md row 169).
 *
 * <p>P4.3 ships the GeoIP enricher as a constant-output stub so
 * the wiring (config block, label key, smoke / Postman / Newman
 * coverage) is in place before the real MaxMind GeoLite2 lookup
 * lands in P5. The fixed country code is exposed under
 * {@code cortex.ingest.enrichment.geo.fixed-country} so
 * integration tests and smoke scripts can pin a deterministic
 * value.</p>
 *
 * @param geo nested geo-enricher configuration; never
 *            {@code null} after binding (canonical constructor
 *            substitutes a default record when Spring leaves it
 *            blank)
 */
@ConfigurationProperties(prefix = "cortex.ingest.enrichment")
public record EnrichmentProperties(Geo geo) {

    /** Default GeoIP country code returned by the P4.3 stub. */
    public static final String DEFAULT_FIXED_COUNTRY = "unknown";

    /**
     * Canonical constructor that substitutes a default
     * {@link Geo} when the {@code geo} sub-block is omitted from
     * the property source. Keeps downstream code free of null
     * checks.
     */
    public EnrichmentProperties {
        if (geo == null) {
            geo = new Geo(DEFAULT_FIXED_COUNTRY);
        }
    }

    /**
     * GeoIP enricher configuration.
     *
     * @param fixedCountry constant country code stamped onto
     *                     every enriched event by the P4.3
     *                     {@link GeoEnricher} stub; trimmed by
     *                     the canonical constructor and replaced
     *                     with {@link #DEFAULT_FIXED_COUNTRY}
     *                     when blank
     */
    public record Geo(String fixedCountry) {

        /**
         * Canonical constructor that trims the inbound country
         * code and falls back to
         * {@link EnrichmentProperties#DEFAULT_FIXED_COUNTRY}
         * when the operator leaves the property blank.
         */
        public Geo {
            if (fixedCountry == null || fixedCountry.isBlank()) {
                fixedCountry = DEFAULT_FIXED_COUNTRY;
            } else {
                fixedCountry = fixedCountry.trim();
            }
        }
    }
}
