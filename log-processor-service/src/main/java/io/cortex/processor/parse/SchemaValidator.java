package io.cortex.processor.parse;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Enforces the post-parse schema contract on a {@link RawLogEvent}
 * (P5.1). Mirrors the {@code log-agent-lib} {@code LogEntry} +
 * {@code LogLevel} contract so a record that survives the agent's
 * ingest-time validation also survives the processor's
 * consume-time validation.
 *
 * <p>Throws {@link SchemaViolationException} on the first violation
 * (fail-fast). The consumer maps that to DLQ header
 * {@code x-failure-reason=schema_violation} per ADR-0027 contract.</p>
 *
 * <p>Five checks (kept narrow on purpose so the rule surface is
 * easy to audit and extend in P5.2+):</p>
 * <ul>
 *   <li>{@code level} in {TRACE, DEBUG, INFO, WARN, ERROR}</li>
 *   <li>{@code ts} non-null</li>
 *   <li>{@code service} non-blank</li>
 *   <li>{@code message} non-null</li>
 *   <li>{@code tenantId} non-blank</li>
 * </ul>
 */
@Component
public class SchemaValidator {

    /**
     * Allowed {@code level} values; mirrors
     * {@code io.cortex.agent.LogLevel} entries.
     */
    private static final Set<String> ALLOWED_LEVELS = Set.of(
            "TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    /**
     * Run the schema checks against the supplied event. Fast-fails
     * on the first violation.
     *
     * @param event the parsed event; must not be {@code null} (the
     *              parser never returns {@code null} on success)
     * @throws SchemaViolationException if any check fails
     */
    public void validate(final RawLogEvent event) {
        if (event.tenantId() == null || event.tenantId().isBlank()) {
            throw new SchemaViolationException("tenantId", "must not be blank");
        }
        if (event.ts() == null) {
            throw new SchemaViolationException("ts", "must not be null");
        }
        if (event.level() == null || !ALLOWED_LEVELS.contains(event.level())) {
            throw new SchemaViolationException(
                    "level",
                    "not in allowlist [TRACE, DEBUG, INFO, WARN, ERROR]");
        }
        if (event.service() == null || event.service().isBlank()) {
            throw new SchemaViolationException("service", "must not be blank");
        }
        if (event.message() == null) {
            throw new SchemaViolationException("message", "must not be null");
        }
    }
}
