/**
 * Owns: best-effort PII redaction used by both the agent
 * (client-side pre-ship hook) and the log-ingest-service
 * (server-side trust boundary, ADR-0023 / spec Sec 5.3).
 *
 * <p>Never imports: org.springframework.*, io.cortex.agent.internal.*,
 * io.cortex.agent.logback.*.</p>
 *
 * <p>Owner: CORTEX :: log-agent-lib.</p>
 */
package io.cortex.agent.pii;
