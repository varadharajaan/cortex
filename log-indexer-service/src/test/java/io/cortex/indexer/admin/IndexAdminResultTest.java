package io.cortex.indexer.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link IndexAdminResult} value object (P7.0).
 * Exercises every factory method + the constant surface so the
 * JaCoCo line + branch budget on the record body is met.
 */
class IndexAdminResultTest {

    @Test
    void constantsAreStable() {
        assertThat(IndexAdminResult.BACKEND_NOOP).isEqualTo("noop");
        assertThat(IndexAdminResult.BACKEND_QUICKWIT).isEqualTo("quickwit");
        assertThat(IndexAdminResult.OUTCOME_NOOP).isEqualTo("noop");
        assertThat(IndexAdminResult.OUTCOME_CREATED).isEqualTo("created");
        assertThat(IndexAdminResult.OUTCOME_EXISTS).isEqualTo("exists");
        assertThat(IndexAdminResult.OUTCOME_DROPPED).isEqualTo("dropped");
        assertThat(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE)
                .isEqualTo("transient_failure");
        assertThat(IndexAdminResult.OUTCOME_PERMANENT_FAILURE)
                .isEqualTo("permanent_failure");
    }

    @Test
    void noopFactoryStampsNoopBackendAndOutcome() {
        final IndexAdminResult r = IndexAdminResult.noop("scaffold-default");
        assertThat(r.backend()).isEqualTo(IndexAdminResult.BACKEND_NOOP);
        assertThat(r.outcome()).isEqualTo(IndexAdminResult.OUTCOME_NOOP);
        assertThat(r.reason()).isEqualTo("scaffold-default");
    }

    @Test
    void noopFactoryCoercesNullReasonToEmpty() {
        final IndexAdminResult r = IndexAdminResult.noop(null);
        assertThat(r.reason()).isEmpty();
    }

    @Test
    void createdFactoryStampsCreatedOutcome() {
        final IndexAdminResult r =
                IndexAdminResult.created(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(r.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(r.outcome()).isEqualTo(IndexAdminResult.OUTCOME_CREATED);
        assertThat(r.reason()).isEmpty();
    }

    @Test
    void existsFactoryStampsExistsOutcome() {
        final IndexAdminResult r =
                IndexAdminResult.exists(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(r.outcome()).isEqualTo(IndexAdminResult.OUTCOME_EXISTS);
    }

    @Test
    void droppedFactoryStampsDroppedOutcome() {
        final IndexAdminResult r =
                IndexAdminResult.dropped(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(r.outcome()).isEqualTo(IndexAdminResult.OUTCOME_DROPPED);
    }

    @Test
    void transientFailureFactoryStampsTransientOutcome() {
        final IndexAdminResult r = IndexAdminResult.transientFailure(
                IndexAdminResult.BACKEND_QUICKWIT, "quickwit:500");
        assertThat(r.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(r.reason()).isEqualTo("quickwit:500");
    }

    @Test
    void permanentFailureFactoryStampsPermanentOutcome() {
        final IndexAdminResult r = IndexAdminResult.permanentFailure(
                IndexAdminResult.BACKEND_QUICKWIT, "quickwit:400");
        assertThat(r.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(r.reason()).isEqualTo("quickwit:400");
    }

    @Test
    void factoriesCoerceNullBackendToNoop() {
        assertThat(IndexAdminResult.created(null).backend())
                .isEqualTo(IndexAdminResult.BACKEND_NOOP);
        assertThat(IndexAdminResult.exists(null).backend())
                .isEqualTo(IndexAdminResult.BACKEND_NOOP);
        assertThat(IndexAdminResult.dropped(null).backend())
                .isEqualTo(IndexAdminResult.BACKEND_NOOP);
        assertThat(IndexAdminResult.transientFailure(null, null).backend())
                .isEqualTo(IndexAdminResult.BACKEND_NOOP);
        assertThat(IndexAdminResult.permanentFailure(null, null).backend())
                .isEqualTo(IndexAdminResult.BACKEND_NOOP);
    }

    @Test
    void failureFactoriesCoerceNullReasonToEmpty() {
        assertThat(IndexAdminResult.transientFailure(
                IndexAdminResult.BACKEND_QUICKWIT, null).reason()).isEmpty();
        assertThat(IndexAdminResult.permanentFailure(
                IndexAdminResult.BACKEND_QUICKWIT, null).reason()).isEmpty();
    }
}
