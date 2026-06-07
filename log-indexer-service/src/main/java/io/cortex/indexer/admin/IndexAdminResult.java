package io.cortex.indexer.admin;

/**
 * Immutable verdict returned by a {@link QuickwitIndexAdmin} for a
 * single admin call (P7.0 / ADR-0038 D1).
 *
 * <p>Carries the three pieces of information the caller needs in
 * order to bump the
 * {@code cortex.indexer.index_admin_total} counter with the right
 * tag values: {@code backend} (which admin backend handled it --
 * {@code quickwit}, or {@code noop} in P7.0), {@code outcome} (the
 * coarse-grained result -- {@code created}, {@code exists},
 * {@code dropped}, {@code noop}, {@code transient_failure},
 * {@code permanent_failure}), {@code reason} (short human-readable
 * explanation).</p>
 *
 * @param backend one of {@code quickwit} or {@code noop}; bounded
 *                enum-like string drives the metric tag cardinality
 *                cap per Part 17
 * @param outcome one of {@code created}, {@code exists},
 *                {@code dropped}, {@code noop},
 *                {@code transient_failure},
 *                {@code permanent_failure}; bounded enum-like string
 *                for the same reason
 * @param reason  short human-readable explanation, free-form;
 *                surfaces in the caller log line
 */
public record IndexAdminResult(String backend, String outcome,
                               String reason) {

    /** Backend value emitted by the no-op admin in P7.0. */
    public static final String BACKEND_NOOP = "noop";

    /** Backend value emitted by the Quickwit HTTP admin in P7.1+. */
    public static final String BACKEND_QUICKWIT = "quickwit";

    /** Outcome value: noop admin (P7.0 default) returned without action. */
    public static final String OUTCOME_NOOP = "noop";

    /** Outcome value: admin successfully created a new index. */
    public static final String OUTCOME_CREATED = "created";

    /** Outcome value: admin verified the index already existed (idempotent ensureIndex). */
    public static final String OUTCOME_EXISTS = "exists";

    /** Outcome value: admin successfully dropped the target index. */
    public static final String OUTCOME_DROPPED = "dropped";

    /**
     * Outcome value: admin successfully applied a retention policy
     * (P7.2 / ADR-0040). The downstream Quickwit Delete API accepted
     * the {@code DeleteQuery} and scheduled the cutoff-based
     * deletion task.
     */
    public static final String OUTCOME_RETENTION_APPLIED = "retention_applied";

    /** Outcome value: downstream returned a 4xx / unrecoverable error. */
    public static final String OUTCOME_PERMANENT_FAILURE = "permanent_failure";

    /** Outcome value: downstream timed out or returned a 5xx / retriable error. */
    public static final String OUTCOME_TRANSIENT_FAILURE = "transient_failure";

    /**
     * Convenience factory for the "no action taken" verdict returned
     * by the default {@link NoopQuickwitIndexAdmin}.
     *
     * @param reason short human-readable explanation
     * @return an {@link IndexAdminResult} with
     *         {@code backend=noop}, {@code outcome=noop}
     */
    public static IndexAdminResult noop(final String reason) {
        return new IndexAdminResult(BACKEND_NOOP, OUTCOME_NOOP,
                reason == null ? "" : reason);
    }

    /**
     * Convenience factory for the "index created" verdict.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @return an {@link IndexAdminResult} with
     *         {@code outcome=created}, blank reason
     */
    public static IndexAdminResult created(final String backend) {
        return new IndexAdminResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_CREATED, "");
    }

    /**
     * Convenience factory for the "index already existed" verdict
     * (idempotent {@link QuickwitIndexAdmin#ensureIndex(IndexSpec)}).
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @return an {@link IndexAdminResult} with {@code outcome=exists},
     *         blank reason
     */
    public static IndexAdminResult exists(final String backend) {
        return new IndexAdminResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_EXISTS, "");
    }

    /**
     * Convenience factory for the "index dropped" verdict.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @return an {@link IndexAdminResult} with
     *         {@code outcome=dropped}, blank reason
     */
    public static IndexAdminResult dropped(final String backend) {
        return new IndexAdminResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_DROPPED, "");
    }

    /**
     * Convenience factory for the "retention policy applied" verdict
     * (P7.2 / ADR-0040). Stamped when the downstream Quickwit Delete
     * API accepts the cutoff-based {@code DeleteQuery}.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @return an {@link IndexAdminResult} with
     *         {@code outcome=retention_applied}, blank reason
     */
    public static IndexAdminResult retentionApplied(final String backend) {
        return new IndexAdminResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_RETENTION_APPLIED, "");
    }

    /**
     * Convenience factory for a retriable downstream failure verdict
     * (5xx / 429 / IOException / timeout). Per ADR-0038 D6 the admin
     * MUST NOT throw on these; the caller decides retry policy and
     * the operator alerts on the failed-outcome metric.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param reason  short categorical explanation, e.g. {@code
     *                quickwit:500} or {@code quickwit:timeout}
     * @return an {@link IndexAdminResult} with
     *         {@code outcome=transient_failure}
     */
    public static IndexAdminResult transientFailure(final String backend,
                                                    final String reason) {
        return new IndexAdminResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_TRANSIENT_FAILURE,
                reason == null ? "" : reason);
    }

    /**
     * Convenience factory for a non-retriable downstream failure
     * verdict (4xx other than 429, e.g. 400 invalid body, 401
     * unauthorized). The caller logs + bumps the failed-outcome
     * metric; no retry.
     *
     * @param backend one of the {@code BACKEND_*} constants
     * @param reason  short categorical explanation, e.g. {@code
     *                quickwit:400} or {@code quickwit:401}
     * @return an {@link IndexAdminResult} with
     *         {@code outcome=permanent_failure}
     */
    public static IndexAdminResult permanentFailure(final String backend,
                                                    final String reason) {
        return new IndexAdminResult(
                backend == null ? BACKEND_NOOP : backend,
                OUTCOME_PERMANENT_FAILURE,
                reason == null ? "" : reason);
    }
}
