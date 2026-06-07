package io.cortex.indexer.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default {@link LogSearchClient} implementation that returns
 * {@link SearchResult#noop(String)} for every call (P7.4 /
 * ADR-0042 D7).
 *
 * <p>The scaffold runs end-to-end without any Quickwit
 * dependency: callers invoke the SPI, this no-op returns the
 * {@code backend=noop, outcome=noop} verdict, and the bound
 * {@link io.cortex.indexer.metrics.IndexerMetrics} counter
 * ticks with bounded tag values. Production swaps the bean
 * implementation behind {@code cortex.indexer.search.backend=
 * quickwit}.</p>
 *
 * <p>Gated by {@code cortex.indexer.search.backend=noop}
 * ({@code matchIfMissing=true}), so it's the default in every
 * profile until the {@link io.cortex.indexer.search.quickwit
 * .QuickwitHttpSearch} bean is selected.</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.indexer.search",
        name = "backend",
        havingValue = "noop",
        matchIfMissing = true)
public final class NoopLogSearchClient implements LogSearchClient {

    /** Reason stamped on every noop verdict from this backend. */
    private static final String NOOP_REASON =
            "noop search backend (P7.4 default); real Quickwit search "
                    + "client is selected via "
                    + "cortex.indexer.search.backend=quickwit";

    @Override
    public String backendId() {
        return SearchResult.BACKEND_NOOP;
    }

    @Override
    public SearchResult search(final SearchRequest request) {
        return SearchResult.noop(NOOP_REASON);
    }
}
