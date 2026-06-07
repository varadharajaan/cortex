package io.cortex.indexer.admin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link QuickwitIndexAdmin} implementation that returns
 * {@link IndexAdminResult#noop(String)} for every call (P7.0 /
 * ADR-0038 D1).
 *
 * <p>The scaffold runs end-to-end without any Quickwit dependency:
 * callers invoke the SPI, this no-op returns the
 * {@code backend=noop, outcome=noop} verdict, and the bound
 * {@link io.cortex.indexer.metrics.IndexerMetrics} counter ticks
 * with bounded tag values. P7.1+ swaps the bean implementation
 * behind {@code cortex.indexer.admin.backend=quickwit}.</p>
 *
 * <p>Gated by {@code cortex.indexer.admin.backend=noop}
 * ({@code matchIfMissing=true}), so it's the default in every
 * profile until P7.1 introduces the real Quickwit HTTP admin
 * client.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.indexer.admin",
        name = "backend",
        havingValue = "noop",
        matchIfMissing = true)
public final class NoopQuickwitIndexAdmin implements QuickwitIndexAdmin {

    /** Reason stamped on every noop verdict from this scaffold backend. */
    private static final String NOOP_REASON =
            "noop admin (P7.0 scaffold); real Quickwit HTTP admin lands in P7.1+";

    @Override
    public String backendId() {
        return IndexAdminResult.BACKEND_NOOP;
    }

    @Override
    public IndexAdminResult ensureIndex(final IndexSpec spec) {
        return IndexAdminResult.noop(NOOP_REASON);
    }

    @Override
    public IndexAdminResult dropIndex(final String indexId) {
        return IndexAdminResult.noop(NOOP_REASON);
    }

    @Override
    public IndexAdminResult applyRetention(final IndexSpec spec,
                                           final RetentionPolicy policy) {
        return IndexAdminResult.noop(NOOP_REASON);
    }

    @Override
    public IndexAdminResult ensureIndex(final IndexSpec spec,
                                        final CardinalityBudget budget) {
        return IndexAdminResult.noop(NOOP_REASON);
    }
}
