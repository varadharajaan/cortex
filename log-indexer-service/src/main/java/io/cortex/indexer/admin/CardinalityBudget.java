package io.cortex.indexer.admin;

/**
 * Immutable value type carrying a per-tenant cardinality budget
 * for the
 * {@link QuickwitIndexAdmin#ensureIndex(IndexSpec,
 * CardinalityBudget)} call (P7.3 / ADR-0041 D2).
 *
 * <p>Day-1 shape: a single integer ceiling on the number of
 * Quickwit indexes a tenant may own simultaneously. A request to
 * create a {@code (tenant_id + N+1)}-th index is rejected as
 * {@code permanent_failure / quickwit:budget-exceeded} when
 * {@code N >= maxIndexes}. The intent is to stop a misconfigured
 * agent (e.g. a tenant flapping {@code docMappingVersion} on every
 * boot) from blowing up the Quickwit metastore + the
 * {@code cortex.indexer.index_admin_total{tenant_id}} cardinality
 * surface.</p>
 *
 * <p>The ceiling MUST be strictly positive. Zero / negative values
 * would block every {@code ensureIndex} call -- an obvious
 * foot-gun. The canonical-constructor rejects them with
 * {@link IllegalArgumentException} so callers see the failure at
 * record construction time, not as a confusing
 * {@code budget-exceeded} verdict on the first call.</p>
 *
 * @param maxIndexes the per-tenant ceiling on simultaneous
 *                   Quickwit index count; must be strictly
 *                   positive
 */
public record CardinalityBudget(int maxIndexes) {

    /**
     * Compact validator-style canonical constructor. Defends
     * against zero / negative ceilings so the
     * {@link QuickwitIndexAdmin} adapter can compare the current
     * count to the ceiling without further defensive logic.
     *
     * @throws IllegalArgumentException when {@code maxIndexes}
     *                                  is zero or negative
     */
    public CardinalityBudget {
        if (maxIndexes <= 0) {
            throw new IllegalArgumentException(
                    "maxIndexes must be strictly positive; got "
                            + maxIndexes);
        }
    }
}
