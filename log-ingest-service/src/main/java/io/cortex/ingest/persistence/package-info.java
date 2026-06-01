/**
 * Persistence layer for log-ingest-service (P4.1 / ADR-0022).
 *
 * <p>Spring Data JDBC aggregates, repositories, and the JSONB
 * custom-converter pair backing the {@code raw_logs} table. JPA /
 * Hibernate types are forbidden in this package (and the whole
 * {@code io.cortex.ingest} tree) by the
 * {@code ArchitectureRulesTest#NO_JPA} rule.</p>
 */
package io.cortex.ingest.persistence;
