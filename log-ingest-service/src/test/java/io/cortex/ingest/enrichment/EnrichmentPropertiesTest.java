package io.cortex.ingest.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EnrichmentProperties} (P4.3 / plan.md
 * row 169). Verifies the canonical-constructor defaulting
 * contract so that an operator who omits the
 * {@code cortex.ingest.enrichment.geo} block (or leaves
 * {@code fixed-country} blank) still receives a non-null,
 * non-blank country code downstream.
 */
class EnrichmentPropertiesTest {

    /** When {@code geo} is null, the canonical constructor substitutes a default. */
    @Test
    void nullGeoSubBlockSubstitutesDefault() {
        final EnrichmentProperties props = new EnrichmentProperties(null);

        assertThat(props.geo()).isNotNull();
        assertThat(props.geo().fixedCountry())
                .isEqualTo(EnrichmentProperties.DEFAULT_FIXED_COUNTRY);
    }

    /** Blank {@code fixedCountry} falls back to the default constant. */
    @Test
    void blankFixedCountryFallsBackToDefault() {
        final EnrichmentProperties.Geo geo =
                new EnrichmentProperties.Geo("   ");

        assertThat(geo.fixedCountry())
                .isEqualTo(EnrichmentProperties.DEFAULT_FIXED_COUNTRY);
    }

    /** Null {@code fixedCountry} falls back to the default constant. */
    @Test
    void nullFixedCountryFallsBackToDefault() {
        final EnrichmentProperties.Geo geo =
                new EnrichmentProperties.Geo(null);

        assertThat(geo.fixedCountry())
                .isEqualTo(EnrichmentProperties.DEFAULT_FIXED_COUNTRY);
    }

    /** Non-blank {@code fixedCountry} is trimmed and preserved verbatim otherwise. */
    @Test
    void nonBlankFixedCountryIsTrimmedAndPreserved() {
        final EnrichmentProperties.Geo geo =
                new EnrichmentProperties.Geo("  IN  ");

        assertThat(geo.fixedCountry()).isEqualTo("IN");
    }
}
