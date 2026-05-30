package io.cortex.agent;

/**
 * Severity level of a {@link LogEntry}.
 *
 * <p>Levels follow the conventional ordering used by SLF4J and Logback,
 * with {@link #TRACE} being the least severe and {@link #ERROR} the most
 * severe. Numeric severities are aligned with SLF4J's
 * {@code org.slf4j.event.Level} integer values to make interop with
 * Logback events cheap.</p>
 */
public enum LogLevel {

    /** Very fine-grained debugging information. */
    TRACE(0),

    /** Fine-grained debugging information. */
    DEBUG(10),

    /** Routine informational events. */
    INFO(20),

    /** Warnings that do not stop processing. */
    WARN(30),

    /** Errors that warrant operator attention. */
    ERROR(40);

    /** Numeric severity, monotonically increasing with severity. */
    private final int severity;

    /**
     * Creates a level with the given numeric severity.
     *
     * @param severity monotonic severity, larger values are more severe
     */
    LogLevel(final int severity) {
        this.severity = severity;
    }

    /**
     * Returns the numeric severity associated with this level.
     *
     * @return monotonic severity (larger values are more severe)
     */
    public int severity() {
        return this.severity;
    }

    /**
     * Maps an SLF4J integer level (such as
     * {@code org.slf4j.event.Level#toInt()}) to a {@link LogLevel}.
     *
     * <p>SLF4J integer levels are: TRACE=00, DEBUG=10, INFO=20, WARN=30,
     * ERROR=40. Any unknown value falls back to {@link #INFO}.</p>
     *
     * @param slf4jLevelInt SLF4J numeric level
     * @return matching {@link LogLevel}; never {@code null}
     */
    public static LogLevel fromSlf4jInt(final int slf4jLevelInt) {
        switch (slf4jLevelInt) {
            case 0:
                return TRACE;
            case 10:
                return DEBUG;
            case 20:
                return INFO;
            case 30:
                return WARN;
            case 40:
                return ERROR;
            default:
                return INFO;
        }
    }
}
