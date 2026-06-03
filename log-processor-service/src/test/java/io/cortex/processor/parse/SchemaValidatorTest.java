package io.cortex.processor.parse;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link SchemaValidator} (P5.1).
 *
 * <p>Covers the five fail-fast violations + the happy path. The
 * fail-fast order matches the validator: tenantId, ts, level,
 * service, message.</p>
 */
class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();

    /** Happy path: every field passes the allowlist + non-blank rules. */
    @Test
    void acceptsValidEvent() {
        final RawLogEvent event = sample("cortex-dev", Instant.now(),
                "INFO", "checkout", "hello");
        assertThatCode(() -> this.validator.validate(event))
                .doesNotThrowAnyException();
    }

    /** Blank tenantId triggers a schema violation on the tenantId field. */
    @Test
    void rejectsBlankTenantId() {
        final RawLogEvent event = sample("", Instant.now(),
                "INFO", "checkout", "hello");
        assertThatThrownBy(() -> this.validator.validate(event))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("tenantId");
    }

    /** Null timestamp triggers a schema violation on the ts field. */
    @Test
    void rejectsNullTimestamp() {
        final RawLogEvent event = sample("cortex-dev", null,
                "INFO", "checkout", "hello");
        assertThatThrownBy(() -> this.validator.validate(event))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("ts");
    }

    /** Level outside the allowlist triggers a schema violation on level. */
    @Test
    void rejectsUnknownLevel() {
        final RawLogEvent event = sample("cortex-dev", Instant.now(),
                "FATAL", "checkout", "hello");
        assertThatThrownBy(() -> this.validator.validate(event))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("level");
    }

    /** Blank service field triggers a schema violation on service. */
    @Test
    void rejectsBlankService() {
        final RawLogEvent event = sample("cortex-dev", Instant.now(),
                "INFO", "  ", "hello");
        assertThatThrownBy(() -> this.validator.validate(event))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("service");
    }

    /** Null message field triggers a schema violation on message. */
    @Test
    void rejectsNullMessage() {
        final RawLogEvent event = sample("cortex-dev", Instant.now(),
                "INFO", "checkout", null);
        assertThatThrownBy(() -> this.validator.validate(event))
                .isInstanceOf(SchemaViolationException.class)
                .hasMessageContaining("message");
    }

    /**
     * Construct a {@link RawLogEvent} with the supplied core fields
     * and stock values for the rest.
     *
     * @param tenantId tenant id
     * @param ts       event timestamp
     * @param level    log level (allowlist token)
     * @param service  originating service
     * @param message  log message body
     * @return event built for validator drive-by tests
     */
    private static RawLogEvent sample(
            final String tenantId, final Instant ts, final String level,
            final String service, final String message) {
        return new RawLogEvent(
                tenantId, "evt-1", ts, level, service, message,
                Map.of(), "idk-1", Instant.now());
    }
}
