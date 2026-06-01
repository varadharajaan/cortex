package io.cortex.ingest.enrichment;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure utility that canonicalises an inbound
 * {@link io.cortex.agent.LogEntry#labels() labels} map so the
 * dedupe pre-image is stable across clients that vary only in
 * casing or whitespace (P4.3 / plan row 169).
 *
 * <p>Rules applied in order:</p>
 * <ol>
 *   <li>{@code null} entries (key OR value) are dropped.</li>
 *   <li>Keys are trimmed and lowercased to {@link Locale#ROOT}
 *       so {@code "Env"} and {@code " env "} both collapse to
 *       {@code "env"}.</li>
 *   <li>Values are trimmed; entries whose key OR value is blank
 *       after trimming are dropped.</li>
 *   <li>When two inbound keys normalise to the same canonical
 *       key, the LAST one wins (matches
 *       {@link LinkedHashMap#put(Object, Object)} semantics so
 *       insertion order is preserved for the surviving entry).</li>
 * </ol>
 *
 * <p>Length capping and reserved-key rejection are intentionally
 * out of scope: bean validation on {@code LogEntry} already
 * bounds key / value sizes, and reserved-key collisions are
 * handled by {@link EnrichmentService#enrich} which overwrites
 * server-owned keys after normalisation.</p>
 */
public final class LabelNormalizer {

    /** Utility holder; not intended to be instantiated. */
    private LabelNormalizer() {
        // no instances
    }

    /**
     * Returns a new {@link LinkedHashMap} containing the
     * canonicalised entries of {@code source}.
     *
     * @param source verbatim inbound labels; may be empty but
     *               must not be {@code null} (the
     *               {@link io.cortex.agent.LogEntry} canonical
     *               constructor guarantees this)
     * @return mutable {@link LinkedHashMap} of normalised
     *         entries; never {@code null}; empty when every
     *         inbound entry was rejected
     */
    public static Map<String, String> normalize(final Map<String, String> source) {
        final Map<String, String> out = new LinkedHashMap<>(source.size());
        for (final Map.Entry<String, String> e : source.entrySet()) {
            final String rawKey = e.getKey();
            final String rawVal = e.getValue();
            if (rawKey == null || rawVal == null) {
                continue;
            }
            final String key = rawKey.trim().toLowerCase(Locale.ROOT);
            final String val = rawVal.trim();
            if (key.isEmpty() || val.isEmpty()) {
                continue;
            }
            out.put(key, val);
        }
        return out;
    }
}
