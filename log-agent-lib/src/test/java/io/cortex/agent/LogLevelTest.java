package io.cortex.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LogLevel}.
 */
class LogLevelTest {

    /** Each level should expose a strictly increasing numeric severity. */
    @Test
    void severityIsStrictlyMonotonic() {
        assertThat(LogLevel.TRACE.severity()).isLessThan(LogLevel.DEBUG.severity());
        assertThat(LogLevel.DEBUG.severity()).isLessThan(LogLevel.INFO.severity());
        assertThat(LogLevel.INFO.severity()).isLessThan(LogLevel.WARN.severity());
        assertThat(LogLevel.WARN.severity()).isLessThan(LogLevel.ERROR.severity());
    }

    /** {@link LogLevel#fromSlf4jInt(int)} maps every known SLF4J value. */
    @Test
    void fromSlf4jIntMapsAllKnownLevels() {
        assertThat(LogLevel.fromSlf4jInt(0)).isEqualTo(LogLevel.TRACE);
        assertThat(LogLevel.fromSlf4jInt(10)).isEqualTo(LogLevel.DEBUG);
        assertThat(LogLevel.fromSlf4jInt(20)).isEqualTo(LogLevel.INFO);
        assertThat(LogLevel.fromSlf4jInt(30)).isEqualTo(LogLevel.WARN);
        assertThat(LogLevel.fromSlf4jInt(40)).isEqualTo(LogLevel.ERROR);
    }

    /** Unknown SLF4J integer values should fall back to {@link LogLevel#INFO}. */
    @Test
    void fromSlf4jIntFallsBackToInfoOnUnknownValue() {
        assertThat(LogLevel.fromSlf4jInt(-99)).isEqualTo(LogLevel.INFO);
        assertThat(LogLevel.fromSlf4jInt(99)).isEqualTo(LogLevel.INFO);
    }
}
