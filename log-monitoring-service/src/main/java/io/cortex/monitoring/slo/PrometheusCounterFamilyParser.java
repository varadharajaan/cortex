package io.cortex.monitoring.slo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Prometheus text exposition parser for P8.3 counter-family
 * SLOs.
 *
 * <p>The backend only needs sample lines for one counter family,
 * not HELP/TYPE metadata or histograms. This parser therefore
 * skips comments, accepts samples with or without labels, reads
 * the first numeric value token, and returns immutable sample
 * records for matching metric names. Micrometer meter names are
 * accepted in either Java form ({@code cortex.foo.bar_total}) or
 * Prometheus exposition form ({@code cortex_foo_bar_total}).</p>
 */
final class PrometheusCounterFamilyParser {

    private PrometheusCounterFamilyParser() {
    }

    static List<Sample> parse(final String body, final String metricName) {
        if (body == null || body.isBlank() || metricName == null
                || metricName.isBlank()) {
            return List.of();
        }
        final Set<String> acceptedNames = acceptedNames(metricName);
        final List<Sample> samples = new ArrayList<>();
        for (final String line : body.split("\\R")) {
            final Sample sample = parseLine(line);
            if (sample != null && acceptedNames.contains(sample.metricName())) {
                samples.add(sample);
            }
        }
        return List.copyOf(samples);
    }

    private static Set<String> acceptedNames(final String metricName) {
        final Set<String> names = new LinkedHashSet<>();
        names.add(metricName);
        names.add(metricName.replace('.', '_'));
        return Set.copyOf(names);
    }

    private static Sample parseLine(final String rawLine) {
        final String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        final int boundary = firstWhitespace(line);
        if (boundary < 0) {
            return null;
        }
        final String head = line.substring(0, boundary);
        final Double value = parseValue(line.substring(boundary).trim());
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        final ParsedHead parsedHead = parseHead(head);
        if (parsedHead == null) {
            return null;
        }
        return new Sample(parsedHead.metricName(), parsedHead.tags(), value);
    }

    private static int firstWhitespace(final String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static Double parseValue(final String tail) {
        if (tail.isBlank()) {
            return null;
        }
        final String[] tokens = tail.split("\\s+", 2);
        try {
            return Double.valueOf(tokens[0]);
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private static ParsedHead parseHead(final String head) {
        final int open = head.indexOf('{');
        if (open < 0) {
            return head.isBlank() ? null : new ParsedHead(head, Map.of());
        }
        final int close = head.lastIndexOf('}');
        if (close < open) {
            return null;
        }
        final String metricName = head.substring(0, open);
        if (metricName.isBlank()) {
            return null;
        }
        final Map<String, String> tags = parseTags(head.substring(open + 1, close));
        if (tags == null) {
            return null;
        }
        return new ParsedHead(metricName, tags);
    }

    private static Map<String, String> parseTags(final String rawTags) {
        final Map<String, String> tags = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < rawTags.length()) {
            cursor = skipSeparators(rawTags, cursor);
            final int equals = rawTags.indexOf('=', cursor);
            if (equals < 0) {
                return null;
            }
            final String key = rawTags.substring(cursor, equals).trim();
            final QuotedValue quoted = readQuotedValue(rawTags, equals + 1);
            if (key.isBlank() || quoted == null) {
                return null;
            }
            tags.put(key, quoted.value());
            cursor = quoted.nextIndex();
        }
        return Map.copyOf(tags);
    }

    private static int skipSeparators(final String rawTags, final int start) {
        int cursor = start;
        while (cursor < rawTags.length()) {
            final char ch = rawTags.charAt(cursor);
            if (ch != ',' && !Character.isWhitespace(ch)) {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private static QuotedValue readQuotedValue(final String rawTags,
                                               final int start) {
        int cursor = start;
        while (cursor < rawTags.length()
                && Character.isWhitespace(rawTags.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= rawTags.length() || rawTags.charAt(cursor) != '"') {
            return null;
        }
        cursor++;
        final StringBuilder value = new StringBuilder();
        boolean escaping = false;
        while (cursor < rawTags.length()) {
            final char ch = rawTags.charAt(cursor);
            cursor++;
            if (escaping) {
                value.append(unescape(ch));
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                return new QuotedValue(value.toString(), cursor);
            } else {
                value.append(ch);
            }
        }
        return null;
    }

    private static char unescape(final char ch) {
        if (ch == 'n') {
            return '\n';
        }
        return ch;
    }

    record Sample(String metricName, Map<String, String> tags, double value) {
    }

    private record ParsedHead(String metricName, Map<String, String> tags) {
    }

    private record QuotedValue(String value, int nextIndex) {
    }
}
