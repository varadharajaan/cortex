package io.cortex.indexer.constants;

/**
 * Shared HTTP constants used by every Quickwit admin
 * {@link io.cortex.indexer.admin.QuickwitIndexAdmin
 * io.cortex.indexer.admin.QuickwitIndexAdmin} adapter
 * (P7.1 / ADR-0039).
 *
 * <p>Centralised here per Rule A7 so the magic numbers {@code 429}
 * (Too Many Requests) and {@code 500} (the {@code 5xx} server-error
 * floor) live in exactly one place rather than scattered across
 * adapters and tests. Symmetric to
 * {@code io.cortex.remediation.constants.RemediationHttp}.</p>
 */
public final class IndexerHttp {

    /**
     * HTTP {@code 429} (Too Many Requests). Treated as a
     * {@code transient_failure} outcome by every admin adapter so
     * the rate-limited call can be retried by the caller (e.g.
     * P7.2 retention sweeper).
     */
    public static final int TOO_MANY_REQUESTS = 429;

    /**
     * HTTP {@code 5xx} server-error floor (inclusive). Any status
     * {@code >= 500} maps to a {@code transient_failure} outcome
     * per ADR-0039 D3.
     */
    public static final int SERVER_ERROR_FLOOR = 500;

    /**
     * HTTP {@code 404} (Not Found). Special-cased by callers:
     * {@code ensureIndex} treats GET 404 as "needs create";
     * {@code dropIndex} treats DELETE 404 as the success outcome
     * {@code dropped} per SPI idempotence contract.
     */
    public static final int NOT_FOUND = 404;

    private IndexerHttp() {
        throw new UnsupportedOperationException(
                "constants holder; do not instantiate");
    }
}
