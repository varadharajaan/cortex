package io.cortex.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LogEntry}.
 */
class LogEntryTest {

    /** Happy path: every field round-trips and labels are exposed. */
    @Test
    void happyPathPopulatesAllFields() {
        final Instant now = Instant.parse("2026-01-02T03:04:05Z");
        final Map<String, String> labels = Map.of("k", "v");
        final LogEntry entry = new LogEntry(now, LogLevel.WARN, "svc", "msg", labels);
        assertThat(entry.timestamp()).isEqualTo(now);
        assertThat(entry.level()).isEqualTo(LogLevel.WARN);
        assertThat(entry.service()).isEqualTo("svc");
        assertThat(entry.message()).isEqualTo("msg");
        assertThat(entry.labels()).containsEntry("k", "v");
    }

    /** Null required fields should raise {@link NullPointerException}. */
    @Test
    void requiredFieldsRejectNull() {
        assertThatThrownBy(() -> new LogEntry(null, LogLevel.INFO, "svc", "m", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LogEntry(Instant.now(), null, "svc", "m", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LogEntry(Instant.now(), LogLevel.INFO, "svc", null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    /** Blank or {@code null} service is rejected. */
    @Test
    void blankServiceRejected() {
        assertThatThrownBy(() -> new LogEntry(Instant.now(), LogLevel.INFO, "  ", "m", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service");
        assertThatThrownBy(() -> new LogEntry(Instant.now(), LogLevel.INFO, null, "m", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** {@code null} labels collapses to an empty unmodifiable map. */
    @Test
    void nullLabelsBecomeEmpty() {
        final LogEntry entry = new LogEntry(
                Instant.now(), LogLevel.INFO, "svc", "m", null);
        assertThat(entry.labels()).isEmpty();
    }

    /** Mutating the source map after construction must not affect the entry. */
    @Test
    void labelsAreDefensivelyCopied() {
        final Map<String, String> src = new HashMap<>();
        src.put("a", "1");
        final LogEntry entry = new LogEntry(
                Instant.now(), LogLevel.INFO, "svc", "m", src);
        src.put("b", "2");
        assertThat(entry.labels()).containsOnlyKeys("a");
        assertThatThrownBy(() -> entry.labels().put("c", "3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** {@link LogEntry#info(String, String)} fills sensible defaults. */
    @Test
    void infoFactoryHasSaneDefaults() {
        final LogEntry entry = LogEntry.info("svc", "hello");
        assertThat(entry.level()).isEqualTo(LogLevel.INFO);
        assertThat(entry.service()).isEqualTo("svc");
        assertThat(entry.message()).isEqualTo("hello");
        assertThat(entry.timestamp()).isNotNull();
        assertThat(entry.labels()).isEmpty();
    }

    /** Tenant id and trace id are pulled from conventional label keys. */
    @Test
    void tenantAndTraceIdLookupConvention() {
        final Map<String, String> labels = Map.of(
                LogEntry.LABEL_TENANT, "t1",
                LogEntry.LABEL_TRACE_ID, "trace-42");
        final LogEntry entry = new LogEntry(
                Instant.now(), LogLevel.INFO, "svc", "m", labels);
        assertThat(entry.tenantId()).isEqualTo("t1");
        assertThat(entry.traceId()).isEqualTo("trace-42");
    }

    /** Missing convention label keys return {@code null}. */
    @Test
    void tenantAndTraceIdMissingReturnsNull() {
        final LogEntry entry = LogEntry.info("svc", "m");
        assertThat(entry.tenantId()).isNull();
        assertThat(entry.traceId()).isNull();
    }
}
