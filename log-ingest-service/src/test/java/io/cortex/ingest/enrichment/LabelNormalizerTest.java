package io.cortex.ingest.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link LabelNormalizer} pure helper
 * (P4.3 / plan.md row 169). Covers the contract described in
 * the Javadoc: null drops, casing collapse, trimming, last-
 * write-wins on canonical-key collision, and idempotence on a
 * pre-normalised map.
 */
class LabelNormalizerTest {

    /** Asserts that lowercased + trimmed input passes through unchanged. */
    @Test
    void alreadyNormalisedMapPassesThroughIdempotently() {
        final Map<String, String> in = new LinkedHashMap<>();
        in.put("env", "prod");
        in.put("region", "eu-west");

        final Map<String, String> out = LabelNormalizer.normalize(in);

        assertThat(out).containsExactlyEntriesOf(in);
        assertThat(out).isNotSameAs(in);
    }

    /** Mixed-case keys collapse to the lowercase canonical key. */
    @Test
    void mixedCaseKeysCollapseToLowercaseCanonicalKey() {
        final Map<String, String> in = new LinkedHashMap<>();
        in.put("Env", "prod");

        final Map<String, String> out = LabelNormalizer.normalize(in);

        assertThat(out).containsExactly(Map.entry("env", "prod"));
    }

    /** Keys and values surrounded by whitespace are trimmed. */
    @Test
    void whitespacePaddedKeysAndValuesAreTrimmed() {
        final Map<String, String> in = new LinkedHashMap<>();
        in.put("  env  ", "  prod  ");

        final Map<String, String> out = LabelNormalizer.normalize(in);

        assertThat(out).containsExactly(Map.entry("env", "prod"));
    }

    /** Null keys and null values are silently dropped. */
    @Test
    void nullKeyOrNullValueIsDropped() {
        final Map<String, String> in = new HashMap<>();
        in.put(null, "v");
        in.put("k", null);
        in.put("keep", "yes");

        final Map<String, String> out = LabelNormalizer.normalize(in);

        assertThat(out).containsExactly(Map.entry("keep", "yes"));
    }

    /** Keys or values that are blank after trimming are dropped. */
    @Test
    void blankKeyOrBlankValueIsDroppedAfterTrim() {
        final Map<String, String> in = new LinkedHashMap<>();
        in.put("   ", "v");
        in.put("k", "   ");
        in.put("ok", "yes");

        final Map<String, String> out = LabelNormalizer.normalize(in);

        assertThat(out).containsExactly(Map.entry("ok", "yes"));
    }

    /** Two keys mapping to the same canonical key collapse last-write-wins. */
    @Test
    void collidingCanonicalKeysCollapseLastWriteWins() {
        final Map<String, String> in = new LinkedHashMap<>();
        in.put("Env", "first");
        in.put("env", "second");

        final Map<String, String> out = LabelNormalizer.normalize(in);

        assertThat(out).containsExactly(Map.entry("env", "second"));
    }

    /** An empty inbound map returns an empty result. */
    @Test
    void emptyMapReturnsEmptyMap() {
        final Map<String, String> out = LabelNormalizer.normalize(Map.of());

        assertThat(out).isEmpty();
    }
}
