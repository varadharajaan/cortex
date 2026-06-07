package io.cortex.indexer.admin;

import java.time.Duration;

/**
 * Immutable value type carrying a retention policy for the
 * {@link QuickwitIndexAdmin#applyRetention(IndexSpec, RetentionPolicy)}
 * call (P7.2 / ADR-0040 D2).
 *
 * <p>Day-1 shape: a single time-to-live {@link Duration}. Documents
 * with {@code timestamp_field &lt; (now - ttl)} are eligible for
 * deletion by the downstream Quickwit Delete API. The cutoff is
 * computed by the {@link QuickwitIndexAdmin} adapter at call time,
 * not stored on the policy, so the policy is a pure declarative
 * value and can be reused across multiple {@code applyRetention}
 * calls without rebinding.</p>
 *
 * <p>The {@code ttl} MUST be strictly positive. Zero / negative
 * values would translate to {@code end_timestamp = now} or
 * {@code end_timestamp &gt; now} in the underlying Quickwit
 * {@code DeleteQuery}, which would delete every document
 * indefinitely into the future -- an obvious foot-gun. The
 * canonical-constructor rejects them with
 * {@link IllegalArgumentException} so callers see the failure at
 * record construction time, not as a confusing 4xx from Quickwit
 * many layers downstream.</p>
 *
 * @param ttl how long a document is retained before becoming
 *            eligible for deletion; never {@code null}, never zero,
 *            never negative
 */
public record RetentionPolicy(Duration ttl) {

    /**
     * Compact validator-style canonical constructor. Defends against
     * null + non-positive durations so the
     * {@link QuickwitIndexAdmin} adapter can compute
     * {@code now() - ttl} without further defensive logic.
     *
     * @throws IllegalArgumentException when {@code ttl} is
     *                                  {@code null}, zero, or
     *                                  negative
     */
    public RetentionPolicy {
        if (ttl == null) {
            throw new IllegalArgumentException("ttl must not be null");
        }
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "ttl must be strictly positive; got " + ttl);
        }
    }
}
