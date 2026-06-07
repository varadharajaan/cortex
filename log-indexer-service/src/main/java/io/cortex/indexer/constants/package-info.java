/**
 * Shared HTTP constants for the indexer service (P7.1 / ADR-0039).
 *
 * <p>Mirror of the {@code io.cortex.remediation.constants} package
 * pattern (Rule A7) -- magic numbers like {@code 429} (Too Many
 * Requests) and {@code 500} (the {@code 5xx} server-error floor)
 * live here in one place so adapters and tests share the same
 * source-of-truth.</p>
 */
package io.cortex.indexer.constants;
