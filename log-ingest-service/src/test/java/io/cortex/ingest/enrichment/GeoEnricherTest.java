package io.cortex.ingest.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GeoEnricher} (P4.3 / plan.md row 169).
 * Confirms the stub returns the constant configured under
 * {@code cortex.ingest.enrichment.geo.fixed-country} and that
 * the canonical label key matches the persisted-row contract.
 */
class GeoEnricherTest {

    /** Default props => {@link EnrichmentProperties#DEFAULT_FIXED_COUNTRY}. */
    @Test
    void resolveCountryReturnsDefaultWhenPropsBlank() {
        final GeoEnricher enricher =
                new GeoEnricher(new EnrichmentProperties(null));

        assertThat(enricher.resolveCountry())
                .isEqualTo(EnrichmentProperties.DEFAULT_FIXED_COUNTRY);
    }

    /** A custom country code flows through verbatim (after trim). */
    @Test
    void resolveCountryReturnsConfiguredValueVerbatim() {
        final GeoEnricher enricher = new GeoEnricher(
                new EnrichmentProperties(new EnrichmentProperties.Geo("IN")));

        assertThat(enricher.resolveCountry()).isEqualTo("IN");
    }

    /** Label-key constant is the canonical {@code geo_country}. */
    @Test
    void labelKeyConstantMatchesPersistedRowContract() {
        assertThat(GeoEnricher.LABEL_GEO_COUNTRY).isEqualTo("geo_country");
    }
}
