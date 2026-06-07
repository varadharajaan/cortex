package io.cortex.indexer.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CardinalityBudget} (P7.3 / ADR-0041 D2).
 * Covers the canonical-constructor validation contract: only
 * strictly-positive ceilings are accepted; zero / negative are
 * rejected with {@link IllegalArgumentException} at construction
 * time so the {@link QuickwitIndexAdmin} adapter never has to
 * defend against a non-sense budget.
 */
class CardinalityBudgetTest {

    @Test
    @DisplayName("positive maxIndexes is accepted and exposed via "
            + "the record accessor")
    void positiveMaxIndexesIsAccepted() {
        final CardinalityBudget budget = new CardinalityBudget(7);
        assertThat(budget.maxIndexes()).isEqualTo(7);
    }

    @Test
    @DisplayName("maxIndexes = 1 is the smallest legal value and is "
            + "accepted")
    void oneIsLegal() {
        final CardinalityBudget budget = new CardinalityBudget(1);
        assertThat(budget.maxIndexes()).isOne();
    }

    @Test
    @DisplayName("zero maxIndexes is rejected by the compact "
            + "canonical-constructor")
    void zeroMaxIndexesIsRejected() {
        assertThatThrownBy(() -> new CardinalityBudget(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "maxIndexes must be strictly positive");
    }

    @Test
    @DisplayName("negative maxIndexes is rejected by the compact "
            + "canonical-constructor")
    void negativeMaxIndexesIsRejected() {
        assertThatThrownBy(() -> new CardinalityBudget(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "maxIndexes must be strictly positive");
    }

    @Test
    @DisplayName("Integer.MIN_VALUE is rejected (defends against "
            + "arithmetic-overflow callers)")
    void minIntegerIsRejected() {
        assertThatThrownBy(
                () -> new CardinalityBudget(Integer.MIN_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
